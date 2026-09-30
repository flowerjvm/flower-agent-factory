package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.verification.VerificationSandboxEvidence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceAcceptanceGateTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OrdinalSourceTreeHasher HASHER = new OrdinalSourceTreeHasher();
    private static final ContentHash SOURCE_HASH = HASHER.sha256("immutable-candidate".getBytes(StandardCharsets.UTF_8));
    private static final String SOURCE = "package candidate; public final class Candidate {}";
    @TempDir Path temporaryDirectory;

    @Test
    void hostComparesGoldenAndCopiesOnlyMainSourceIntoFreshProbeWorkspace() throws Exception {
        Path candidate = candidate();
        Files.writeString(candidate.resolve("pom.xml"), "<malicious-plugin/>");
        Files.createDirectories(candidate.resolve("src/test/java"));
        Files.writeString(candidate.resolve("src/test/java/ForgedTest.java"), "factory golden forged");
        Files.createDirectories(candidate.resolve("src/main/resources"));
        Files.writeString(candidate.resolve("src/main/resources/forged-acceptance.json"), "forged");
        var sandbox = new FakeSandbox(root -> {});

        var result = new MaintenanceAcceptanceGate(sandbox, MAPPER).verify(candidate, repository(), SOURCE_HASH);

        assertTrue(result.passed());
        assertEquals(1, sandbox.calls);
        assertFalse(Files.exists(sandbox.workspace));
        assertTrue(MaintenanceAcceptanceGate.evidenceMatches(result.summaryBytes(), result.actualBytes(), SOURCE_HASH, MAPPER));
        assertFalse(MaintenanceAcceptanceGate.evidenceMatches(result.summaryBytes(), result.actualBytes(),
                HASHER.sha256(new byte[0]), MAPPER));
        var summary = MAPPER.readTree(result.summaryBytes());
        assertEquals(16, summary.get("cases").size());
        assertEquals(MaintenanceInvestigationProductContract.caseSuiteLock().hash().sha256(), summary.get("caseSuiteHash").asText());
        assertTrue(Files.exists(candidate.resolve("pom.xml")));
    }

    @Test
    void semanticMutationFailsEvenWhenSandboxExitCodeIsZero() throws Exception {
        var sandbox = new FakeSandbox(root -> {
            ObjectNode output = (ObjectNode) root.withArray("cases").get(0).get("actual");
            output.put("reportMarkdown", "looks plausible but is not the exact required report");
        });
        var result = new MaintenanceAcceptanceGate(sandbox, MAPPER).verify(candidate(), repository(), SOURCE_HASH);

        assertEquals(0, result.execution().exitCode());
        assertFalse(result.passed());
        assertFalse(MAPPER.readTree(result.summaryBytes()).get("failedCaseIds").isEmpty());
        assertFalse(MaintenanceAcceptanceGate.evidenceMatches(result.summaryBytes(), result.actualBytes(), SOURCE_HASH, MAPPER));
    }

    @Test
    void laterRepeatedInvocationMustStillEqualTheGoldenOutput() throws Exception {
        var sandbox = new FakeSandbox(root -> {
            for (var observation : root.withArray("cases")) {
                if (observation.path("caseId").asText().equals("DETERMINISM")
                        && observation.path("invocation").asInt() == 2) {
                    ((ObjectNode) observation.get("actual")).put("incidentId", "leaked-state");
                }
            }
        });
        var result = new MaintenanceAcceptanceGate(sandbox, MAPPER).verify(candidate(), repository(), SOURCE_HASH);

        assertFalse(result.passed());
        assertEquals("DETERMINISM", MAPPER.readTree(result.summaryBytes()).get("failedCaseIds").get(0).asText());
    }

    @Test
    void goldenLookingActualCannotOverrideANonzeroSandboxExit() throws Exception {
        var sandbox = new FakeSandbox(root -> {});
        sandbox.exitCode = 1;
        var result = new MaintenanceAcceptanceGate(sandbox, MAPPER).verify(candidate(), repository(), SOURCE_HASH);

        assertFalse(result.passed());
        assertFalse(MaintenanceAcceptanceGate.evidenceMatches(result.summaryBytes(), result.actualBytes(), SOURCE_HASH, MAPPER));
    }

    @Test
    void rejectsMissingDuplicateReorderedUnknownAndWrongInvocationEvidence() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
                root -> root.withArray("cases").remove(0),
                root -> root.withArray("cases").set(1, root.withArray("cases").get(0).deepCopy()),
                root -> ((ObjectNode) root.withArray("cases").get(0)).put("caseId", "unknown-case"),
                root -> ((ObjectNode) root.withArray("cases").get(0)).put("invocation", 1),
                root -> ((ObjectNode) root.withArray("cases").get(0)).put("invocation", "0"),
                root -> root.put("inputHash", "a".repeat(64)),
                root -> root.put("unexpected", true));
        Path candidate = candidate();
        Path repository = repository();
        for (var mutation : mutations) {
            var result = new MaintenanceAcceptanceGate(new FakeSandbox(mutation), MAPPER)
                    .verify(candidate, repository, SOURCE_HASH);
            assertFalse(result.passed());
            assertFalse(MaintenanceAcceptanceGate.evidenceMatches(result.summaryBytes(), result.actualBytes(), SOURCE_HASH, MAPPER));
        }
    }

    @Test
    void storedEvidenceRechecksEveryKnownContractAndDriverLock() throws Exception {
        var result = new MaintenanceAcceptanceGate(new FakeSandbox(root -> {}), MAPPER)
                .verify(candidate(), repository(), SOURCE_HASH);
        for (String field : List.of("productContractHash", "requirementsHash", "apiSignatureIndexHash",
                "requirementTestMatrixHash", "caseSuiteHash", "candidateSourceHash", "mainSourceHash",
                "inputHash", "driverHash", "probeHash", "actualHash")) {
            ObjectNode summary = (ObjectNode) MAPPER.readTree(result.summaryBytes());
            summary.put(field, "f".repeat(64));
            assertFalse(MaintenanceAcceptanceGate.evidenceMatches(MAPPER.writeValueAsBytes(summary),
                    result.actualBytes(), SOURCE_HASH, MAPPER), field);
        }
        String summary = new String(result.summaryBytes(), StandardCharsets.UTF_8);
        assertFalse(MaintenanceAcceptanceGate.evidenceMatches((summary + "{}").getBytes(StandardCharsets.UTF_8),
                result.actualBytes(), SOURCE_HASH, MAPPER));
        assertFalse(MaintenanceAcceptanceGate.evidenceMatches(summary.replace("\"passed\":true", "\"passed\":true,\"passed\":true")
                .getBytes(StandardCharsets.UTF_8), result.actualBytes(), SOURCE_HASH, MAPPER));
    }

    @Test
    void candidateCannotOccupyTrustedProbeNamespaceAndNoSandboxRunsOnRejection() throws Exception {
        Path candidate = candidate();
        Files.createDirectory(candidate.resolve("factory-probe"));
        var sandbox = new FakeSandbox(root -> {});
        Path repository = repository();

        assertThrows(IOException.class, () -> new MaintenanceAcceptanceGate(sandbox, MAPPER)
                .verify(candidate, repository, SOURCE_HASH));
        assertEquals(0, sandbox.calls);
    }

    @Test
    void candidateFactoryPackageIsRejectedWithoutExecutingAndPartialWorkspaceIsCleaned() throws Exception {
        Path candidate = candidate();
        Files.writeString(candidate.resolve("src/main/java/candidate/Candidate.java"),
                "package io.github.flowerjvm.factory.forged; class Candidate {} ");
        var sandbox = new FakeSandbox(root -> {});
        Path repository = repository();
        assertThrows(IOException.class, () -> new MaintenanceAcceptanceGate(sandbox, MAPPER)
                .verify(candidate, repository, SOURCE_HASH));
        assertEquals(0, sandbox.calls);
        try (var children = Files.list(temporaryDirectory)) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith("maintenance-acceptance-")));
        }
    }

    @Test
    void fixedCommandBypassesCandidateMavenLifecycleAndExportsOneBoundedActualFile() {
        assertEquals(List.of("java", "/workspace/factory-probe/FactoryAcceptanceProbe.java"),
                VerificationCommand.MAINTENANCE_ACCEPTANCE.arguments());
        var command = new DockerCliVerificationSandbox().dockerExecArguments("probe", VerificationCommand.MAINTENANCE_ACCEPTANCE);
        assertTrue(command.contains("FACTORY_COMMAND=MAINTENANCE_ACCEPTANCE"));
        assertTrue(command.stream().anyMatch(value -> value.contains("MAINTENANCE_ACCEPTANCE) evidence=maintenance-acceptance.json")));
    }

    private Path candidate() throws IOException {
        Path candidate = Files.createDirectory(temporaryDirectory.resolve("candidate"));
        Path source = candidate.resolve("src/main/java/candidate/Candidate.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
        return candidate;
    }

    private Path repository() throws IOException {
        return Files.createDirectory(temporaryDirectory.resolve("curated-m2"));
    }

    private static final class FakeSandbox implements VerificationSandbox {
        private final Consumer<ObjectNode> mutation;
        private int calls;
        private int exitCode;
        private Path workspace;
        private FakeSandbox(Consumer<ObjectNode> mutation) { this.mutation = mutation; }

        @Override
        public SandboxExecutionResult execute(Path workspace, Path repository, VerificationCommand command) {
            calls++;
            this.workspace = workspace;
            assertEquals(VerificationCommand.MAINTENANCE_ACCEPTANCE, command);
            assertFalse(Files.exists(workspace.resolve("pom.xml")));
            assertFalse(Files.exists(workspace.resolve("src/test")));
            assertFalse(Files.exists(workspace.resolve("src/main/resources")));
            try {
                String probe = Files.readString(workspace.resolve("factory-probe/FactoryAcceptanceProbe.java"));
                assertTrue(probe.contains("-proc:none"));
                assertFalse(probe.contains("/*FACTORY_"));
                assertFalse(probe.contains("expectedOutput"));
                assertFalse(probe.contains("reportMarkdown"));
                ContentHash mainHash = HASHER.sha256(("src/main/java/candidate/Candidate.java\t"
                        + HASHER.sha256(SOURCE.getBytes(StandardCharsets.UTF_8)).sha256()).getBytes(StandardCharsets.UTF_8));
                var actual = new java.util.LinkedHashMap<String, Object>();
                actual.put("schemaVersion", "factory.maintenance-actual.v1");
                actual.put("candidateSourceHash", SOURCE_HASH.sha256());
                actual.put("mainSourceHash", mainHash.sha256());
                actual.put("inputHash", HASHER.sha256(MaintenanceAcceptanceGate.inputBytes(MAPPER)).sha256());
                var cases = new ArrayList<Object>();
                for (var testCase : MaintenanceInvestigationProductContract.cases()) {
                    for (int invocation = 0; invocation < testCase.repeatInvocations(); invocation++) {
                        cases.add(Map.of("caseId", testCase.caseId(), "invocation", invocation, "actual", testCase.expectedOutput()));
                    }
                }
                actual.put("cases", cases);
                ObjectNode root = MAPPER.valueToTree(actual);
                mutation.accept(root);
                return new SandboxExecutionResult(command.commandId(), command.arguments(), exitCode, new byte[0],
                        Map.of(MaintenanceAcceptanceGate.ACTUAL_FILE, MAPPER.writeValueAsBytes(root)));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override public VerificationSandboxEvidence evidence() { return new DockerCliVerificationSandbox().evidence(); }
    }
}
