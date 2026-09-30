package io.github.flowerjvm.factory.infrastructure.verification;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Product-specific host golden comparison; candidate code is executed only through the sandbox. */
public final class MaintenanceAcceptanceGate {
    public static final String ACTUAL_FILE = "maintenance-acceptance.json";
    public static final String SUMMARY_SCHEMA = "factory.maintenance-acceptance-summary.v1";
    private static final String RESOURCE = "/factory-verification/maintenance/FactoryAcceptanceProbe.java";
    private static final int MAX_EVIDENCE_BYTES = 1_048_576;
    private static final OrdinalSourceTreeHasher HASHER = new OrdinalSourceTreeHasher();
    private final VerificationSandbox sandbox;
    private final ObjectMapper mapper;

    public MaintenanceAcceptanceGate(VerificationSandbox sandbox, ObjectMapper mapper) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.mapper = strict(Objects.requireNonNull(mapper, "mapper"));
    }

    public Result verify(Path candidateWorkspace, Path curatedMavenRepository, ContentHash candidateSourceHash)
            throws IOException, SandboxExecutionException {
        Objects.requireNonNull(candidateSourceHash, "candidateSourceHash");
        Path candidate = checkedDirectory(candidateWorkspace);
        Path repository = checkedDirectory(curatedMavenRepository);
        if (Files.exists(candidate.resolve("factory-probe"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("candidate occupies the Factory probe namespace");
        }
        Path main = checkedDirectory(candidate.resolve("src/main/java"));
        if (!main.startsWith(candidate)) throw new IOException("candidate main source escaped workspace");
        Path parent = checkedDirectory(candidate.getParent());
        Path probeWorkspace = Files.createTempDirectory(parent, "maintenance-acceptance-");
        try {
            ContentHash mainSourceHash = copyMainSources(main, probeWorkspace);
            byte[] probe = probeBytes(candidateSourceHash, mainSourceHash, mapper);
            Path driver = probeWorkspace.resolve("factory-probe/FactoryAcceptanceProbe.java");
            Files.createDirectories(driver.getParent());
            Files.write(driver, probe, StandardOpenOption.CREATE_NEW);
            SandboxExecutionResult execution = sandbox.execute(
                    probeWorkspace, repository, VerificationCommand.MAINTENANCE_ACCEPTANCE);
            byte[] actual = execution.evidenceFiles().getOrDefault(ACTUAL_FILE, new byte[0]);
            if (actual.length > MAX_EVIDENCE_BYTES) throw new IOException("acceptance actual evidence exceeds bound");
            boolean exactExecution = execution.commandId().equals(VerificationCommand.MAINTENANCE_ACCEPTANCE.commandId())
                    && execution.arguments().equals(VerificationCommand.MAINTENANCE_ACCEPTANCE.arguments())
                    && execution.exitCode() == 0
                    && execution.evidenceFiles().keySet().equals(Set.of(ACTUAL_FILE));
            Comparison comparison = compare(actual, candidateSourceHash, mainSourceHash, mapper);
            boolean passed = exactExecution && comparison.passed();
            byte[] summary = mapper.writeValueAsBytes(summary(
                    candidateSourceHash, mainSourceHash, actual, passed, comparison.failedCaseIds(), mapper));
            return new Result(passed, execution, summary, actual,
                    HASHER.sha256(probe), MaintenanceInvestigationProductContract.caseSuiteLock().hash());
        } finally {
            deleteOwnedWorkspace(parent, probeWorkspace);
        }
    }

    /** Recomputes the Factory summary and golden comparison from immutable raw evidence. */
    public static boolean evidenceMatches(
            byte[] summaryBytes, byte[] actualBytes, ContentHash candidateSourceHash, ObjectMapper objectMapper) {
        try {
            if (summaryBytes == null || actualBytes == null || candidateSourceHash == null
                    || summaryBytes.length > MAX_EVIDENCE_BYTES || actualBytes.length > MAX_EVIDENCE_BYTES) return false;
            ObjectMapper mapper = strict(Objects.requireNonNull(objectMapper, "objectMapper"));
            JsonNode actualSummary = mapper.readTree(summaryBytes);
            String mainHash = actualSummary.path("mainSourceHash").asText();
            if (!mainHash.matches("[0-9a-f]{64}")) return false;
            ContentHash mainSourceHash = new ContentHash(mainHash);
            Comparison comparison = compare(actualBytes, candidateSourceHash, mainSourceHash, mapper);
            if (!comparison.passed()) return false;
            JsonNode expectedSummary = mapper.valueToTree(summary(
                    candidateSourceHash, mainSourceHash, actualBytes, true, comparison.failedCaseIds(), mapper));
            return expectedSummary.equals(actualSummary);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static Map<String, Object> summary(ContentHash sourceHash, ContentHash mainSourceHash,
            byte[] actual, boolean passed, List<String> failedCaseIds, ObjectMapper mapper) throws IOException {
        var summary = new TreeMap<String, Object>();
        summary.put("schemaVersion", SUMMARY_SCHEMA);
        summary.put("gateProfile", MaintenanceInvestigationProductContract.GATE_PROFILE);
        summary.put("productContractHash", MaintenanceInvestigationProductContract.lock().hash().sha256());
        summary.put("requirementsHash", MaintenanceInvestigationProductContract.requirementsLock().hash().sha256());
        summary.put("apiSignatureIndexHash", MaintenanceInvestigationProductContract.apiSignatureIndexLock().hash().sha256());
        summary.put("requirementTestMatrixHash", MaintenanceInvestigationProductContract.requirementTestMatrixLock().hash().sha256());
        summary.put("caseSuiteHash", MaintenanceInvestigationProductContract.caseSuiteLock().hash().sha256());
        summary.put("candidateSourceHash", sourceHash.sha256());
        summary.put("mainSourceHash", mainSourceHash.sha256());
        summary.put("inputHash", HASHER.sha256(inputBytes(mapper)).sha256());
        summary.put("driverHash", HASHER.sha256(templateBytes()).sha256());
        summary.put("probeHash", HASHER.sha256(probeBytes(sourceHash, mainSourceHash, mapper)).sha256());
        summary.put("actualHash", HASHER.sha256(actual).sha256());
        summary.put("passed", passed);
        summary.put("failedCaseIds", List.copyOf(failedCaseIds));
        summary.put("cases", MaintenanceInvestigationProductContract.cases().stream()
                .map(value -> Map.of("caseId", value.caseId(), "repeatInvocations", value.repeatInvocations()))
                .toList());
        return summary;
    }

    private static Comparison compare(byte[] bytes, ContentHash sourceHash,
            ContentHash mainSourceHash, ObjectMapper mapper) {
        try {
            if (bytes.length == 0 || bytes.length > MAX_EVIDENCE_BYTES) return Comparison.invalid();
            JsonNode root = mapper.readTree(bytes);
            if (!fields(root).equals(Set.of("schemaVersion", "candidateSourceHash", "mainSourceHash", "inputHash", "cases"))
                    || !root.path("schemaVersion").isTextual() || !root.path("candidateSourceHash").isTextual()
                    || !root.path("mainSourceHash").isTextual() || !root.path("inputHash").isTextual()
                    || !"factory.maintenance-actual.v1".equals(root.path("schemaVersion").asText())
                    || !sourceHash.sha256().equals(root.path("candidateSourceHash").asText())
                    || !mainSourceHash.sha256().equals(root.path("mainSourceHash").asText())
                    || !HASHER.sha256(inputBytes(mapper)).sha256().equals(root.path("inputHash").asText())
                    || !root.path("cases").isArray()) return Comparison.invalid();
            JsonNode observations = root.get("cases");
            int expectedCount = MaintenanceInvestigationProductContract.cases().stream()
                    .mapToInt(value -> value.repeatInvocations()).sum();
            if (observations.size() != expectedCount) return Comparison.invalid();
            var failed = new ArrayList<String>();
            int index = 0;
            for (var testCase : MaintenanceInvestigationProductContract.cases()) {
                JsonNode expected = mapper.readTree(testCase.expectedOutputBytes());
                boolean casePassed = true;
                for (int invocation = 0; invocation < testCase.repeatInvocations(); invocation++) {
                    JsonNode observation = observations.get(index++);
                    if (!fields(observation).equals(Set.of("caseId", "invocation", "actual"))
                            || !observation.path("caseId").isTextual()
                            || !testCase.caseId().equals(observation.path("caseId").asText())
                            || !observation.path("invocation").isInt()
                            || observation.path("invocation").intValue() != invocation) return Comparison.invalid();
                    JsonNode actual = observation.get("actual");
                    if (actual == null || !actual.isObject()
                            || mapper.writeValueAsBytes(actual).length > MaintenanceInvestigationProductContract.MAX_OUTPUT_BYTES
                            || !expected.equals(actual)) casePassed = false;
                }
                if (!casePassed) failed.add(testCase.caseId());
            }
            return new Comparison(failed.isEmpty(), failed);
        } catch (IOException | RuntimeException failure) {
            return Comparison.invalid();
        }
    }

    private static Set<String> fields(JsonNode node) {
        if (node == null || !node.isObject()) return Set.of();
        var result = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(result::add);
        return Set.copyOf(result);
    }

    static byte[] inputBytes(ObjectMapper mapper) throws IOException {
        var input = new ArrayList<String>();
        for (var value : MaintenanceInvestigationProductContract.cases()) {
            if (value.inputBytes().length > MaintenanceInvestigationProductContract.MAX_INPUT_BYTES) {
                throw new IOException("Factory-owned acceptance input exceeds product bound");
            }
            // The product contract owns these canonical bytes; caller mapper flags cannot change
            // the Factory input identity. caseId is constrained to [A-Z][A-Z0-9_]{0,63}.
            input.add("{\"caseId\":\"" + value.caseId() + "\",\"input\":"
                    + new String(value.inputBytes(), StandardCharsets.UTF_8)
                    + ",\"repeatInvocations\":" + value.repeatInvocations() + "}");
        }
        return ("[" + String.join(",", input) + "]").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] probeBytes(ContentHash sourceHash, ContentHash mainSourceHash, ObjectMapper mapper) throws IOException {
        var cases = new ArrayList<String>();
        for (var value : MaintenanceInvestigationProductContract.cases()) {
            cases.add("new ProbeCase(" + stringLiteral(value.caseId()) + ","
                    + literal(value.input()) + "," + value.repeatInvocations() + ")");
        }
        String template = new String(templateBytes(), StandardCharsets.UTF_8);
        String probe = template.replace("/*FACTORY_API*/", stringLiteral(MaintenanceInvestigationProductContract.API_CLASS_NAME))
                .replace("/*FACTORY_METHOD*/", stringLiteral(MaintenanceInvestigationProductContract.API_METHOD_NAME))
                .replace("/*FACTORY_SOURCE_HASH*/", stringLiteral(sourceHash.sha256()))
                .replace("/*FACTORY_MAIN_SOURCE_HASH*/", stringLiteral(mainSourceHash.sha256()))
                .replace("/*FACTORY_INPUT_HASH*/", stringLiteral(HASHER.sha256(inputBytes(mapper)).sha256()))
                .replace("/*FACTORY_CASES*/", String.join(",\n", cases));
        if (probe.contains("/*FACTORY_")) throw new IOException("unresolved Factory probe placeholder");
        return probe.getBytes(StandardCharsets.UTF_8);
    }

    private static String literal(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return stringLiteral(text);
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Number number) {
            return "new java.math.BigDecimal(" + stringLiteral(number.toString()) + ")";
        }
        if (value instanceof List<?> list) {
            return "java.util.Arrays.asList(new Object[]{" + String.join(",", list.stream().map(MaintenanceAcceptanceGate::literal).toList()) + "})";
        }
        if (value instanceof Map<?, ?> map) {
            var pairs = new ArrayList<String>();
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put((String) key, item));
            sorted.forEach((key, item) -> { pairs.add(stringLiteral(key)); pairs.add(literal(item)); });
            return "obj(" + String.join(",", pairs) + ")";
        }
        throw new IllegalArgumentException("unsupported Factory-owned case input");
    }

    private static String stringLiteral(String value) {
        byte[] codeUnits = new byte[value.length() * 2];
        for (int index = 0; index < value.length(); index++) {
            codeUnits[index * 2] = (byte) (value.charAt(index) >>> 8);
            codeUnits[index * 2 + 1] = (byte) value.charAt(index);
        }
        return "str(\"" + Base64.getEncoder().encodeToString(codeUnits) + "\")";
    }

    private static byte[] templateBytes() throws IOException {
        try (var stream = MaintenanceAcceptanceGate.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Factory-owned probe resource is missing");
            return stream.readAllBytes();
        }
    }

    private static ObjectMapper strict(ObjectMapper original) {
        ObjectMapper mapper = original.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(48).maxStringLength(131072).maxNumberLength(128).build());
        return mapper;
    }

    private static Path checkedDirectory(Path path) throws IOException {
        Path absolute = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(absolute)
                || !absolute.toRealPath().equals(absolute)) throw new IOException("unsafe acceptance directory");
        return absolute;
    }

    private static ContentHash copyMainSources(Path main, Path destination) throws IOException {
        var manifest = new ArrayList<String>();
        long total = 0;
        int count = 0;
        try (var paths = Files.walk(main)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("candidate main contains link or special file");
                if (attributes.isDirectory()) continue;
                String relative = main.relativize(path).toString().replace('\\', '/');
                if (!attributes.isRegularFile() || !relative.endsWith(".java") || relative.length() > 240
                        || relative.contains("FactoryAcceptanceProbe") || relative.startsWith("io/github/flowerjvm/factory/")) {
                    throw new IOException("candidate main source violates Factory probe boundary");
                }
                if (++count > 2048 || attributes.size() > 8L * 1024 * 1024
                        || (total += attributes.size()) > 64L * 1024 * 1024) throw new IOException("acceptance source quota");
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length != attributes.size()) throw new IOException("candidate source changed during copy");
                String source = new String(bytes, StandardCharsets.UTF_8);
                if (source.matches("(?s).*\\b(?:class|record|interface|enum)\\s+FactoryAcceptanceProbe\\b.*")
                        || source.matches("(?s).*\\bpackage\\s+io\\.github\\.flowerjvm\\.factory(?:\\.|\\s*;).*")) {
                    throw new IOException("candidate declares a Factory-owned namespace");
                }
                Path target = destination.resolve("src/main/java").resolve(relative).normalize();
                if (!target.startsWith(destination)) throw new IOException("acceptance source path escaped");
                Files.createDirectories(target.getParent());
                Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                manifest.add("src/main/java/" + relative + "\t" + HASHER.sha256(bytes).sha256());
            }
        }
        if (manifest.isEmpty()) throw new IOException("candidate has no main Java sources");
        manifest.sort(String::compareTo);
        return HASHER.sha256(String.join("\n", manifest).getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteOwnedWorkspace(Path parent, Path workspace) throws IOException {
        Path absolute = workspace.toAbsolutePath().normalize();
        if (!absolute.getParent().equals(parent) || !absolute.getFileName().toString().startsWith("maintenance-acceptance-")
                || Files.isSymbolicLink(absolute)) throw new IOException("unsafe acceptance cleanup target");
        Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record Comparison(boolean passed, List<String> failedCaseIds) {
        private static Comparison invalid() { return new Comparison(false, List.of("ACTUAL_EVIDENCE_INVALID")); }
    }

    public record Result(boolean passed, SandboxExecutionResult execution, byte[] summaryBytes,
            byte[] actualBytes, ContentHash probeHash, ContentHash caseSuiteHash) {
        public Result {
            Objects.requireNonNull(execution, "execution");
            summaryBytes = summaryBytes.clone();
            actualBytes = actualBytes.clone();
            Objects.requireNonNull(probeHash, "probeHash");
            Objects.requireNonNull(caseSuiteHash, "caseSuiteHash");
        }
        @Override public byte[] summaryBytes() { return summaryBytes.clone(); }
        @Override public byte[] actualBytes() { return actualBytes.clone(); }
    }
}
