import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.tools.ToolProvider;

/** Factory-owned acceptance probe. Executed only by the fixed isolated Docker command. */
public final class FactoryAcceptanceProbe {
    private static final String API = /*FACTORY_API*/;
    private static final String METHOD = /*FACTORY_METHOD*/;
    private static final String SOURCE_HASH = /*FACTORY_SOURCE_HASH*/;
    private static final String MAIN_SOURCE_HASH = /*FACTORY_MAIN_SOURCE_HASH*/;
    private static final String INPUT_HASH = /*FACTORY_INPUT_HASH*/;

    public static void main(String[] ignored) throws Exception {
        var results = new ArrayList<Object>();
        boolean passed = false;
        try {
            Path root = Path.of("/workspace/src/main/java");
            Path classes = Path.of("/workspace/target/factory-acceptance-classes");
            Files.createDirectories(classes);
            var sources = new ArrayList<String>();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted().toList()) {
                    if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("source link");
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (!path.toString().endsWith(".java")) throw new IllegalArgumentException("non-Java source");
                        sources.add(path.toString());
                    }
                }
            }
            if (sources.isEmpty() || sources.size() > 2048) throw new IllegalArgumentException("source count");
            var jars = new ArrayList<Path>();
            try (var paths = Files.walk(Path.of("/m2"))) {
                for (Path path : paths.sorted().toList()) {
                    if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("classpath link");
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && path.toString().endsWith(".jar")) jars.add(path);
                }
            }
            if (jars.size() > 4096) throw new IllegalArgumentException("classpath count");
            String classpath = String.join(File.pathSeparator, jars.stream().map(Path::toString).toList());
            var compile = new ArrayList<>(List.of(
                    "-proc:none", "--release", "21", "-encoding", "UTF-8", "-d", classes.toString(),
                    "-classpath", classpath, "-sourcepath", root.toString()));
            compile.addAll(sources);
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null || compiler.run(null, System.out, System.err, compile.toArray(String[]::new)) != 0) {
                throw new IllegalArgumentException("candidate main compilation failed");
            }
            // Inspect compiled names too: Unicode escapes must not disguise a reserved package.
            if (Files.exists(classes.resolve("FactoryAcceptanceProbe.class"))
                    || Files.exists(classes.resolve("io/github/flowerjvm/factory"))) {
                throw new IllegalArgumentException("candidate compiled into a Factory-owned namespace");
            }
            var urls = new ArrayList<java.net.URL>();
            urls.add(classes.toUri().toURL());
            for (Path jar : jars) urls.add(jar.toUri().toURL());
            try (var loader = new URLClassLoader(urls.toArray(java.net.URL[]::new),
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> api = Class.forName(API, true, loader);
                Method method = api.getMethod(METHOD, Map.class);
                if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Map.class) {
                    throw new IllegalArgumentException("acceptance facade must be public static Map -> Map");
                }
                for (ProbeCase probeCase : cases()) {
                    for (int invocation = 0; invocation < probeCase.repeatInvocations(); invocation++) {
                        Object input = copy(probeCase.input(), 0);
                        String inputBefore = json(input);
                        Object actual = method.invoke(null, input);
                        if (!inputBefore.equals(json(input))) throw new IllegalArgumentException("candidate mutated input");
                        if (!(actual instanceof Map<?, ?>)) throw new IllegalArgumentException("actual is not a Map");
                        // Serialize immediately: later candidate calls cannot mutate an earlier observation.
                        String actualJson = json(actual);
                        if (actualJson.getBytes(StandardCharsets.UTF_8).length > 131072) {
                            throw new IllegalArgumentException("per-invocation output bound");
                        }
                        results.add(obj("caseId", probeCase.caseId(), "invocation", invocation,
                                "actual", new RawJson(actualJson)));
                    }
                }
                passed = true;
            }
        } catch (Throwable failure) {
            System.err.println("Factory acceptance probe failed: " + failure.getClass().getName());
        }
        String report = json(obj("schemaVersion", "factory.maintenance-actual.v1",
                "candidateSourceHash", SOURCE_HASH, "mainSourceHash", MAIN_SOURCE_HASH,
                "inputHash", INPUT_HASH, "cases", results));
        Path output = Path.of("/workspace/.factory-evidence/maintenance-acceptance.json");
        Files.writeString(output, report, StandardCharsets.UTF_8);
        if (!passed) System.exit(1);
    }

    private static List<ProbeCase> cases() {
        return List.of(/*FACTORY_CASES*/);
    }

    private record ProbeCase(String caseId, Map<String, Object> input, int repeatInvocations) {}
    private record RawJson(String value) {}

    private static String str(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        char[] chars = new char[bytes.length / 2];
        for (int index = 0; index < chars.length; index++) {
            chars[index] = (char) (((bytes[index * 2] & 255) << 8) | (bytes[index * 2 + 1] & 255));
        }
        return new String(chars);
    }

    private static Map<String, Object> obj(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static Object copy(Object value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("input nesting");
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put((String) key, copy(item, depth + 1)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> copy(item, depth + 1)).toList();
        }
        return value;
    }

    private static String json(Object value) {
        StringBuilder result = new StringBuilder();
        appendJson(result, value, 0);
        if (result.length() > 1_048_576) throw new IllegalArgumentException("actual evidence bound");
        return result.toString();
    }

    private static void appendJson(StringBuilder out, Object value, int depth) {
        if (depth > 32 || out.length() > 1_048_576) throw new IllegalArgumentException("actual structure bound");
        if (value == null) out.append("null");
        else if (value instanceof RawJson raw) out.append(raw.value());
        else if (value instanceof String string) quote(out, string);
        else if (value instanceof Boolean) out.append(value);
        else if (value instanceof Number number) {
            String text = number.toString();
            if (!text.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
                    || text.length() > 128) throw new IllegalArgumentException("actual number");
            out.append(text);
        } else if (value instanceof Map<?, ?> map) {
            if (map.size() > 1024) throw new IllegalArgumentException("actual Map bound");
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("actual Map key");
                sorted.put(key, entry.getValue());
            }
            out.append('{');
            boolean first = true;
            for (var entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, entry.getKey()); out.append(':'); appendJson(out, entry.getValue(), depth + 1);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.size() > 4096) throw new IllegalArgumentException("actual list bound");
            out.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) out.append(',');
                appendJson(out, list.get(index), depth + 1);
            }
            out.append(']');
        } else throw new IllegalArgumentException("unsupported actual value");
    }

    private static void quote(StringBuilder out, String value) {
        if (value.length() > 65536) throw new IllegalArgumentException("actual string bound");
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 32 || Character.isSurrogate(ch)) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        out.append('"');
    }
}
