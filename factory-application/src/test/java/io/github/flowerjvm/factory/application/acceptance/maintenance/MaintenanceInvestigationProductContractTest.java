package io.github.flowerjvm.factory.application.acceptance.maintenance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.certification.AgentPackProductContract;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaintenanceInvestigationProductContractTest {
    @Test
    void versionOneArtifactBytesAndHistoricalContractHashArePinned() {
        assertEquals(List.of(
                        "7c29a5aba74ef732360b819ba0ed6447d676674c6d88058633ee9fe30e39fc13",
                        "354bd724b4db79c6a6a97c72d3c0bcc8ebfd48f22987d2394f50d9effca8d1ed",
                        "ccfa71712d91cc591c4f34f8ea538e136db0d05319821d928b42956441a618d0",
                        "0eff4c8f973cc3b1be7117c595060204915a501ce38dd52875088ae6c3a701ea",
                        "33660a7197650d93e7abc5da3fd47f73bfe2503bdcb30a684bdd185f8067502d"),
                MaintenanceInvestigationProductContract.artifacts(new TenantId("tenant-version-lock")).stream()
                        .map(value -> value.contentHash().sha256()).toList());
        assertEquals("c646e0a6939f1709697aafdfe0ec431d6ec105a2819731ef670bd267a83d8edf",
                AgentPackProductContract.lock().hash().sha256());
    }

    @Test
    void productContractLocksAllFourIndependentInputsWithoutChangingTheLegacyProduct() throws Exception {
        var artifacts = MaintenanceInvestigationProductContract.artifacts(new TenantId("tenant-contract"));
        assertEquals(5, artifacts.size());
        assertEquals(5, artifacts.stream().map(value -> value.reference()).distinct().count());
        for (var artifact : artifacts) {
            assertEquals(new TenantId("tenant-contract"), artifact.tenantId());
            assertEquals(artifact.contentHash().sha256(), hash(artifact.content()));
            assertTrue(artifact.reference().value().endsWith(artifact.contentHash().sha256()));
        }
        String contract = utf8(MaintenanceInvestigationProductContract.canonicalBytes());
        for (var lock : List.of(
                MaintenanceInvestigationProductContract.requirementsLock(),
                MaintenanceInvestigationProductContract.apiSignatureIndexLock(),
                MaintenanceInvestigationProductContract.requirementTestMatrixLock(),
                MaintenanceInvestigationProductContract.caseSuiteLock())) {
            assertTrue(contract.contains(lock.reference().value()));
            assertTrue(contract.contains(lock.hash().sha256()));
        }
        assertEquals("flower-agent-pack", AgentPackProductContract.CONTRACT_ID);
        assertEquals("1.0.0", AgentPackProductContract.CONTRACT_VERSION);
        assertNotEquals(AgentPackProductContract.lock(), MaintenanceInvestigationProductContract.lock());
        assertFalse(contract.contains("factory-v0.1-pr4"));
        assertTrue(contract.contains(MaintenanceInvestigationProductContract.GATE_PROFILE));
    }

    @Test
    void caseSuiteAndMatrixHaveExactUniqueCasesIncludingNegativeBehaviorAndRepeatedCalls() {
        List<String> expectedIds = List.of(
                "NORMAL", "WHITESPACE_DUPLICATE", "CONFLICT", "INVALID", "CASE_INSENSITIVE",
                "ORDER_GROUPING", "EMPTY", "DETERMINISM", "UNKNOWN_FIELD", "UNKNOWN_OBSERVATION_FIELD",
                "INVALID_TIMESTAMP", "INVALID_SCALAR", "INVALID_BLANK", "OVERSIZED_IDENTIFIER",
                "TOO_MANY_OBSERVATIONS", "INVALID_PRECEDES_CONFLICT");
        var cases = MaintenanceInvestigationProductContract.cases();
        assertEquals(expectedIds, cases.stream().map(value -> value.caseId()).toList());
        assertEquals(cases.size(), new HashSet<>(expectedIds).size());
        String suite = utf8(MaintenanceInvestigationProductContract.caseSuiteBytes());
        String matrix = utf8(MaintenanceInvestigationProductContract.requirementTestMatrixBytes());
        for (var acceptanceCase : cases) {
            assertTrue(suite.contains("\"caseId\":\"" + acceptanceCase.caseId() + "\""));
            assertTrue(matrix.contains("\"" + acceptanceCase.caseId() + "\""));
            assertTrue(acceptanceCase.inputBytes().length <= MaintenanceInvestigationProductContract.MAX_INPUT_BYTES);
            assertTrue(acceptanceCase.expectedOutputBytes().length <= MaintenanceInvestigationProductContract.MAX_OUTPUT_BYTES);
            assertEquals(acceptanceCase.caseId().equals("DETERMINISM") ? 3 : 1, acceptanceCase.repeatInvocations());
        }
        assertEquals(Map.of("errorCode", "EVIDENCE_ID_CONFLICT"), named("CONFLICT").expectedOutput());
        assertEquals(Map.of("errorCode", "INVALID_INCIDENT"), named("INVALID_PRECEDES_CONFLICT").expectedOutput());
    }

    @Test
    void goldenOrderingGroupingGroundingAndReportBytesAreExplicit() {
        var output = named("ORDER_GROUPING").expectedOutput();
        List<?> evidence = (List<?>) output.get("evidence");
        assertEquals(List.of("Z", "A", "B"), evidence.stream()
                .map(value -> ((Map<?, ?>) value).get("evidenceId")).toList());
        List<?> findings = (List<?>) output.get("findings");
        assertEquals(List.of("OBSERVATION_REQUIRES_REVIEW", "SERVICE_TIMEOUT"), findings.stream()
                .map(value -> ((Map<?, ?>) value).get("code")).toList());
        assertEquals(List.of("A", "Z"), ((Map<?, ?>) findings.get(1)).get("evidenceRefs"));
        assertEquals(output, named("DETERMINISM").expectedOutput());
        for (var acceptanceCase : MaintenanceInvestigationProductContract.cases()) {
            if (acceptanceCase.expectedOutput().containsKey("errorCode")) {
                assertEquals(1, acceptanceCase.expectedOutput().size());
                continue;
            }
            assertEquals(6, acceptanceCase.expectedOutput().size());
            String report = (String) acceptanceCase.expectedOutput().get("reportMarkdown");
            assertTrue(report.endsWith("\n"));
            assertFalse(report.endsWith("\n\n"));
            assertFalse(report.contains("\r"));
            assertTrue(report.startsWith("# Incident " + acceptanceCase.expectedOutput().get("incidentId") + "\n"));
        }
        assertEquals(List.of(), named("EMPTY").expectedOutput().get("evidence"));
        assertTrue(((String) named("EMPTY").expectedOutput().get("reportMarkdown"))
                .contains("## Findings\n- None\n\n## Evidence\n- None\n"));
    }

    @Test
    void artifactsAndNestedGoldenMapsAreDefensivelyImmutable() {
        byte[] first = MaintenanceInvestigationProductContract.canonicalBytes();
        byte[] unchanged = first.clone();
        first[0] = 0;
        assertArrayEquals(unchanged, MaintenanceInvestigationProductContract.canonicalBytes());
        var artifact = MaintenanceInvestigationProductContract.artifact(new TenantId("tenant-a"));
        byte[] content = artifact.content();
        content[0] = 0;
        assertArrayEquals(unchanged, artifact.content());
        assertThrows(UnsupportedOperationException.class,
                () -> MaintenanceInvestigationProductContract.cases().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> named("NORMAL").input().put("service", "changed"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<?>) named("NORMAL").input().get("observations")).clear());
        assertThrows(UnsupportedOperationException.class, () -> {
            Map<?, ?> observation = (Map<?, ?>) ((List<?>) named("NORMAL").input().get("observations")).getFirst();
            observation.clear();
        });
    }

    @Test
    void canonicalJsonIsIndependentOfInputMapInsertionOrderAndEscapesReportLineFeeds() {
        var firstInput = new LinkedHashMap<String, Object>();
        firstInput.put("z", List.of(Map.of("quote", "\"\\\n")));
        firstInput.put("a", 42);
        var secondInput = new LinkedHashMap<String, Object>();
        secondInput.put("a", 42);
        secondInput.put("z", List.of(Map.of("quote", "\"\\\n")));
        var first = new MaintenanceInvestigationProductContract.AcceptanceCase("CASE_A", firstInput, Map.of(), 1);
        var second = new MaintenanceInvestigationProductContract.AcceptanceCase("CASE_B", secondInput, Map.of(), 1);
        assertArrayEquals(first.inputBytes(), second.inputBytes());
        assertEquals("{\"a\":42,\"z\":[{\"quote\":\"\\\"\\\\\\n\"}]}", utf8(first.inputBytes()));
        String suite = utf8(MaintenanceInvestigationProductContract.caseSuiteBytes());
        assertFalse(suite.contains("\n"));
        assertTrue(suite.contains("\\n"));
    }

    @Test
    void bridgeScopeAndBoundsAreRequirementsRatherThanAnImplicitRuntimeAbi() {
        String requirements = utf8(MaintenanceInvestigationProductContract.requirementsBytes());
        String api = utf8(MaintenanceInvestigationProductContract.apiSignatureIndexBytes());
        assertTrue(requirements.contains("not define a general Agent Runtime"));
        assertTrue(requirements.contains("Unknown or missing fields"));
        assertTrue(requirements.contains("Validate all observations before"));
        assertTrue(requirements.contains("Timeout has precedence"));
        assertTrue(requirements.contains("Do not mutate the supplied input Map"));
        assertTrue(api.contains(MaintenanceInvestigationProductContract.API_CLASS_NAME));
        assertTrue(api.contains("public static java.util.Map<String,Object> investigate"));
        assertEquals(65_536, MaintenanceInvestigationProductContract.MAX_INPUT_BYTES);
        assertEquals(131_072, MaintenanceInvestigationProductContract.MAX_OUTPUT_BYTES);
        assertEquals(64, MaintenanceInvestigationProductContract.MAX_OBSERVATIONS);
    }

    private static MaintenanceInvestigationProductContract.AcceptanceCase named(String caseId) {
        return MaintenanceInvestigationProductContract.cases().stream()
                .filter(value -> value.caseId().equals(caseId)).findFirst().orElseThrow();
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String utf8(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
}
