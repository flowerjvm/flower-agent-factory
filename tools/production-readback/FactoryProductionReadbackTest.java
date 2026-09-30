import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Standalone synthetic control tests. No database, full-gate success fixture, model or production process. */
public final class FactoryProductionReadbackTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static int passed;

    public static void main(String[] ignored) throws Exception {
        defaultProbeOptionsRemainReadOnlyAndExportRequiresSelectedAssembly();
        untrustedOrAmbiguousOptionsFailBeforeAnyIo();
        sourceAllowlistDoesNotExposeWorkerWorkspaceOrInputs();
        relativePathsAndCaseCollisionsFailClosed();
        inputBytesAreCopiedAndIndexedExactly();
        existingDestinationIsNeverOverwritten();
        broadRelativeAndTraversalDestinationsAreRejected();
        quotasAndKnownSecretMaterialFailBeforeWrites();
        actualSampleIsStrictlyJoinedToPersistedSuiteNotSynthesized();
        mismatchedDuplicateOrWrongInvocationCannotBecomeDemoSuccess();
        linkedDestinationAncestorIsRejectedWhenPlatformAllowsLinkCreation();
        System.out.println("PRODUCTION_READBACK_STANDALONE_TESTS_PASSED:" + passed);
    }

    private static void defaultProbeOptionsRemainReadOnlyAndExportRequiresSelectedAssembly() throws Exception {
        var plain = FactoryProductionReadback.Options.parse(options());
        check(plain.exportDirectory().isEmpty() && plain.assembly().isEmpty());
        reject(() -> FactoryProductionReadback.Options.parse(plus(options(), "--export-dir", "C:/example/export")));
        var selected = FactoryProductionReadback.Options.parse(plus(options(), "--assembly-id", "ra-1"));
        check(selected.assembly().orElseThrow().equals("ra-1") && selected.exportDirectory().isEmpty());
        var exporting = FactoryProductionReadback.Options.parse(plus(options(), "--assembly-id", "ra-1", "--export-dir", "C:/example/export"));
        check(exporting.exportDirectory().isPresent()); passed++;
    }

    private static void untrustedOrAmbiguousOptionsFailBeforeAnyIo() throws Exception {
        reject(() -> FactoryProductionReadback.Options.parse(plus(options(), "--tenant", "other")));
        reject(() -> FactoryProductionReadback.Options.parse(plus(options(), "--unknown", "value")));
        var remote = options(); remote[1] = "jdbc:postgresql://example.com:5432/database";
        reject(() -> FactoryProductionReadback.Options.parse(remote));
        var properties = options(); properties[1] += "?readOnly=false";
        reject(() -> FactoryProductionReadback.Options.parse(properties)); passed++;
    }

    private static void sourceAllowlistDoesNotExposeWorkerWorkspaceOrInputs() {
        check(FactoryProductionReadback.ExportPlan.allowedSource("pom.xml"));
        check(FactoryProductionReadback.ExportPlan.allowedSource("src/main/java/io/github/demo/Probe.java"));
        check(FactoryProductionReadback.ExportPlan.allowedSource("src/test/java/io/github/demo/ProbeTest.java"));
        for (String path : List.of(".codex/config.toml", "auth.json", ".env", "worker/input.json", "transcript.json",
                "src/main/resources/credential.json", "src/main/java/../../auth.json", "run.ps1")) {
            check(!FactoryProductionReadback.ExportPlan.allowedSource(path));
        }
        passed++;
    }

    private static void relativePathsAndCaseCollisionsFailClosed() throws Exception {
        for (String path : List.of("../escape", "/absolute", "C:/absolute", "a\\b", "a//b", "a/./b", "a/../b",
                "a/NUL.txt", "a/file.", "a/file:ads", ".codex/config.toml")) {
            reject(() -> new FactoryProductionReadback.ExportPlan().add(path, bytes("test"), "synthetic:test"));
        }
        var plan = new FactoryProductionReadback.ExportPlan();
        plan.add("a/File.java", bytes("one"), "synthetic:test");
        reject(() -> plan.add("a/file.java", bytes("two"), "synthetic:test"));
        reject(() -> plan.add("a", bytes("parent"), "synthetic:test"));
        reject(() -> plan.add("handoff-index.json", bytes("pretend success"), "synthetic:test")); passed++;
    }

    private static void inputBytesAreCopiedAndIndexedExactly() throws Exception {
        withTemp(root -> {
            var plan = new FactoryProductionReadback.ExportPlan();
            byte[] original = bytes("original bytes\r\n");
            plan.add("product-source/pom.xml", original, "artifact:synthetic-public-source");
            original[0] = 'X';
            plan.add("README.md", bytes("Synthetic test, not production evidence.\n"), "synthetic:test");
            byte[] index = plan.index(MAPPER); plan.seal(index);
            Path target = root.resolve("new-export"); plan.write(target);
            check(Arrays.equals(Files.readAllBytes(target.resolve("product-source/pom.xml")), bytes("original bytes\r\n")));
            check(Arrays.equals(Files.readAllBytes(target.resolve("handoff-index.json")), index));
            var tree = MAPPER.readTree(index);
            check(tree.path("fileCount").intValue() == 2);
            for (var entry : tree.path("files")) {
                byte[] actual = Files.readAllBytes(target.resolve(entry.path("path").textValue()));
                check(actual.length == entry.path("sizeBytes").intValue());
                String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(actual));
                check(hash.equals(entry.path("sha256").textValue()));
            }
            reject(() -> plan.add("after-seal.txt", bytes("no"), "synthetic:test"));
        }); passed++;
    }

    private static void existingDestinationIsNeverOverwritten() throws Exception {
        withTemp(root -> {
            Path existing = Files.createDirectory(root.resolve("existing"));
            Files.writeString(existing.resolve("keep.txt"), "user-owned");
            var plan = samplePlan();
            reject(() -> plan.write(existing));
            check(Files.readString(existing.resolve("keep.txt")).equals("user-owned"));
            check(!Files.exists(existing.resolve("handoff-index.json")));
            Path successful = root.resolve("successful"); plan.write(successful);
            byte[] firstIndex = Files.readAllBytes(successful.resolve("handoff-index.json"));
            reject(() -> samplePlan().write(successful));
            check(Arrays.equals(firstIndex, Files.readAllBytes(successful.resolve("handoff-index.json"))));
        }); passed++;
    }

    private static void broadRelativeAndTraversalDestinationsAreRejected() throws Exception {
        withTemp(root -> {
            reject(() -> FactoryProductionReadback.ExportPlan.validateDestination(Path.of("relative")));
            reject(() -> FactoryProductionReadback.ExportPlan.validateDestination(root.getRoot()));
            reject(() -> FactoryProductionReadback.ExportPlan.validateDestination(root.resolve("missing/../escape")));
            reject(() -> FactoryProductionReadback.ExportPlan.validateDestination(root.resolve("missing/child")));
            Path privateConfig = Files.createDirectory(root.resolve(".codex"));
            reject(() -> FactoryProductionReadback.ExportPlan.validateDestination(privateConfig.resolve("export")));
        }); passed++;
    }

    private static void quotasAndKnownSecretMaterialFailBeforeWrites() throws Exception {
        reject(() -> new FactoryProductionReadback.ExportPlan().add("large.txt", new byte[8 * 1024 * 1024 + 1], "synthetic:test"));
        reject(() -> new FactoryProductionReadback.ExportPlan().add("secret.txt", bytes("FACTORY_" + "TEST_SECRET"), "synthetic:test"));
        passed++;
    }

    private static void actualSampleIsStrictlyJoinedToPersistedSuiteNotSynthesized() throws Exception {
        // This synthetic actual is ONLY a join unit fixture. The live exporter separately calls the real full gate.
        var normal = MaintenanceInvestigationProductContract.cases().stream().filter(value -> value.caseId().equals("NORMAL")).findFirst().orElseThrow();
        withTemp(root -> {
            var plan = new FactoryProductionReadback.ExportPlan();
            FactoryProductionReadback.addDemonstration(plan, MaintenanceInvestigationProductContract.caseSuiteBytes(), actual(normal.expectedOutput(), 0, false), MAPPER);
            plan.seal(plan.index(MAPPER)); Path target = root.resolve("sample"); plan.write(target);
            check(MAPPER.readTree(Files.readAllBytes(target.resolve("demonstration/input.json"))).equals(MAPPER.valueToTree(normal.input())));
            check(MAPPER.readTree(Files.readAllBytes(target.resolve("demonstration/actual-output.json"))).equals(MAPPER.valueToTree(normal.expectedOutput())));
            check(Files.readString(target.resolve("demonstration/report.md")).equals(normal.expectedOutput().get("reportMarkdown")));
            check(!Files.readString(target.resolve("demonstration/input.json")).contains("expectedOutput"));
        }); passed++;
    }

    private static void mismatchedDuplicateOrWrongInvocationCannotBecomeDemoSuccess() throws Exception {
        var normal = MaintenanceInvestigationProductContract.cases().stream().filter(value -> value.caseId().equals("NORMAL")).findFirst().orElseThrow();
        byte[] suite = MaintenanceInvestigationProductContract.caseSuiteBytes();
        reject(() -> FactoryProductionReadback.addDemonstration(new FactoryProductionReadback.ExportPlan(), suite, actual(Map.of("reportMarkdown", "fabricated"), 0, false), MAPPER));
        reject(() -> FactoryProductionReadback.addDemonstration(new FactoryProductionReadback.ExportPlan(), suite, actual(normal.expectedOutput(), 1, false), MAPPER));
        reject(() -> FactoryProductionReadback.addDemonstration(new FactoryProductionReadback.ExportPlan(), suite, actual(normal.expectedOutput(), 0, true), MAPPER));
        byte[] changed = suite.clone(); changed[0] = ' ';
        reject(() -> FactoryProductionReadback.addDemonstration(new FactoryProductionReadback.ExportPlan(), changed, actual(normal.expectedOutput(), 0, false), MAPPER));
        reject(() -> FactoryProductionReadback.addDemonstration(new FactoryProductionReadback.ExportPlan(), suite, bytes("{\"cases\":[]}"), MAPPER)); passed++;
    }

    private static void linkedDestinationAncestorIsRejectedWhenPlatformAllowsLinkCreation() throws Exception {
        withTemp(root -> {
            Path actual = Files.createDirectory(root.resolve("actual"));
            Path link = root.resolve("link");
            try { Files.createSymbolicLink(link, actual); }
            catch (UnsupportedOperationException | java.io.IOException unavailable) {
                if (Boolean.getBoolean("factory.readback.test.requireLinks")) throw unavailable;
                System.out.println("PRODUCTION_READBACK_LINK_TEST_SKIPPED:PLATFORM_PRIVILEGE_REQUIRED"); return;
            }
            reject(() -> samplePlan().write(link.resolve("export")));
            check(!Files.exists(actual.resolve("export"))); passed++;
        });
    }

    private static FactoryProductionReadback.ExportPlan samplePlan() throws Exception {
        var plan = new FactoryProductionReadback.ExportPlan();
        plan.add("README.md", bytes("Synthetic test."), "synthetic:test"); plan.seal(plan.index(MAPPER)); return plan;
    }
    private static byte[] actual(Map<String, Object> value, int invocation, boolean duplicate) throws Exception {
        var entry = Map.of("caseId", "NORMAL", "invocation", invocation, "actual", value);
        return MAPPER.writeValueAsBytes(Map.of("cases", duplicate ? List.of(entry, entry) : List.of(entry)));
    }
    private static String[] options() {
        return new String[]{"--db-url", "jdbc:postgresql://127.0.0.1:5432/synthetic", "--db-user", "reader",
                "--password-file", "C:/synthetic/password", "--tenant", "synthetic-tenant", "--certification-id", "cert-synthetic"};
    }
    private static String[] plus(String[] base, String... extra) {
        var values = new ArrayList<>(List.of(base)); values.addAll(List.of(extra)); return values.toArray(String[]::new);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("synthetic control failed"); }
    private static void reject(Checked action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("expected fail-closed rejection");
    }
    private static void withTemp(PathCheck action) throws Exception {
        Path root = Files.createTempDirectory("factory-readback-test-").toRealPath();
        try { action.run(root); }
        finally {
            check(root.getFileName().toString().startsWith("factory-readback-test-") && root.getParent() != null && !Files.isSymbolicLink(root));
            try (var tree = Files.walk(root)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface PathCheck { void run(Path root) throws Exception; }
}
