package io.github.flowerjvm.factory.infrastructure.production;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPlan;
import io.github.flowerjvm.factory.application.production.MaintenanceProductionRecipe;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerRepairLock;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class JacksonAgentPackProductionCodecTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JacksonAgentPackProductionCodec codec = new JacksonAgentPackProductionCodec(mapper);

    @Test
    void planRoundTripHasCanonicalScalarLocksSortedKeysAndCapabilities() throws Exception {
        AgentPackProductionPlan plan = plan();
        byte[] bytes = codec.writePlan(plan);
        assertEquals(plan, codec.readPlan(bytes));
        assertArrayEquals(bytes, codec.writePlan(codec.readPlan(bytes)));
        ObjectNode root = (ObjectNode) mapper.readTree(bytes);
        assertEquals(18, root.size());
        var keys = new ArrayList<String>();
        root.fieldNames().forEachRemaining(keys::add);
        assertEquals(keys.stream().sorted().toList(), keys);
        assertTrue(root.get("tenantId").isTextual());
        assertTrue(root.get("requirements").get("reference").isTextual());
        assertTrue(root.get("requirements").get("hash").isTextual());
        assertEquals("bounded-patch-write", root.get("coding").get("capabilities").get(0).textValue());
        assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("\n"));
    }

    @Test
    void unknownAndMissingFieldsAtEveryPlanObjectLevelFailClosed() throws Exception {
        for (Consumer<ObjectNode> mutate : List.<Consumer<ObjectNode>>of(
                root -> root.put("unknown", true), root -> root.remove("tenantId"),
                root -> ((ObjectNode) root.get("requirements")).put("unknown", true),
                root -> ((ObjectNode) root.get("requirements")).remove("hash"),
                root -> ((ObjectNode) root.get("coding")).put("unknown", true),
                root -> ((ObjectNode) root.get("coding")).remove("capabilities"))) {
            rejectPlan(mutate);
        }
    }

    @Test
    void wrongScalarTypesIdentityHashAndCapabilityShapesFailClosed() throws Exception {
        for (Consumer<ObjectNode> mutate : List.<Consumer<ObjectNode>>of(
                root -> root.put("schemaVersion", "other"), root -> root.put("tenantId", 12),
                root -> root.put("tenantId", ""), root -> root.put("buildSessionId", "session\nunsafe"),
                root -> root.put("skillVersion", "a".repeat(65)), root -> root.putNull("requirements"),
                root -> ((ObjectNode) root.get("requirements")).put("hash", "A".repeat(64)),
                root -> ((ObjectNode) root.get("requirements")).put("hash", "0".repeat(63)),
                root -> ((ObjectNode) root.get("requirements")).put("reference", "artifact:latest"),
                root -> ((ObjectNode) root.get("coding")).put("capabilities", "repository-read"),
                root -> ((ObjectNode) root.get("coding")).putArray("capabilities").add(7),
                root -> ((ObjectNode) root.get("coding")).putArray("capabilities").add("read").add("read"),
                root -> ((ObjectNode) root.get("manager")).putNull("adapterVersion"),
                root -> root.put("tenantId", "\ud800"))) {
            rejectPlan(mutate);
        }
    }

    @Test
    void malformedDuplicateTrailingDeepAndOversizedPlanDocumentsAreRejectedWithSanitizedErrors() {
        String valid = new String(codec.writePlan(plan()), StandardCharsets.UTF_8);
        for (String document : List.of(
                valid + " {}", "[]", "null", " ", "{", "\ufeff" + valid,
                valid.replace("\"tenantId\":\"tenant-one\"", "\"tenantId\":\"tenant-one\",\"tenantId\":\"tenant-two\""),
                valid.replace("\"hash\":", "\"hash\":\"" + "0".repeat(64) + "\",\"hash\":"),
                "{\"private-fixture\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> codec.readPlan(utf8(document)));
            assertEquals("AGENT_PACK_PRODUCTION_JSON_INVALID", failure.getMessage());
            assertEquals(null, failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> codec.readPlan(null));
        assertThrows(IllegalArgumentException.class, () -> codec.readPlan(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> codec.readPlan(new byte[65_537]));
        assertThrows(IllegalArgumentException.class, () -> codec.readPlan(new byte[] {(byte) 0xff}));
    }

    @Test
    void hostPermissiveParserSettingsCannotWidenTheStoredProtocol() {
        var permissive = new ObjectMapper().enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                .enable(JsonParser.Feature.ALLOW_COMMENTS).enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);
        var strict = new JacksonAgentPackProductionCodec(permissive);
        String valid = new String(codec.writePlan(plan()), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> strict.readPlan(utf8(valid.replace('"', '\''))));
        assertThrows(IllegalArgumentException.class, () -> strict.readPlan(utf8("/* ignored? */" + valid)));
    }

    @Test
    void newWorkerInputUsesExistingExactV1DecoderWithoutChangingHistoricalProtocol() throws Exception {
        var decoder = new JacksonWorkerProtocolArtifactDecoder(mapper);
        for (Optional<CodingWorkerRepairLock> repair : List.of(Optional.<CodingWorkerRepairLock>empty(),
                Optional.of(new CodingWorkerRepairLock(new CandidateId("candidate-base"), hash(),
                        new ArtifactReference("artifact:finding-v1"), hash(),
                        List.of(MaintenanceProductionRecipe.BRIDGE_SOURCE_PATH), 1, 2)))) {
            CodingWorkerInputManifest input = input(repair);
            byte[] encoded = codec.writeWorkerInput(input);
            assertEquals(input, decoder.decodeInputManifest(encoded));
            assertArrayEquals(encoded, codec.writeWorkerInput(input));
            var root = mapper.readTree(encoded);
            assertEquals(20, root.size());
            assertTrue(root.get("workOrderId").isTextual());
            assertEquals(repair.isEmpty(), root.get("repairLock").isNull());
            if (repair.isPresent()) assertTrue(root.get("repairLock").get("repairRound").isIntegralNumber());
        }
    }

    @Test
    void blueprintNormalizationIsDeterministicAndPreservesOnlyDataFields() throws Exception {
        ObjectNode root = blueprint();
        root.put("summary", "한국어 bounded investigation design");
        root.withArray("designNotes").add("UNTRUSTED: a suggestion, not a policy override");
        byte[] normalized = codec.normalizeBlueprint(mapper.writeValueAsBytes(root));
        assertArrayEquals(normalized, codec.normalizeBlueprint(normalized));
        var result = mapper.readTree(normalized);
        assertEquals(5, result.size());
        assertEquals("pom.xml", result.get("sourceFiles").get(0).textValue());
        assertEquals(root.get("summary"), result.get("summary"));
        assertEquals(root.get("designNotes"), result.get("designNotes"));
    }

    @Test
    void blueprintRequiresExactContractSchemaAndBoundedPlainTextShapes() throws Exception {
        for (Consumer<ObjectNode> mutate : List.<Consumer<ObjectNode>>of(
                root -> root.put("permissionProfile", "unsafe"), root -> root.remove("summary"),
                root -> root.put("schemaVersion", "wrong"), root -> root.put("productContractHash", "f".repeat(64)),
                root -> root.put("summary", true), root -> root.put("summary", " "),
                root -> root.put("summary", "a".repeat(2049)), root -> root.put("summary", "line\nbreak"),
                root -> root.put("summary", "\ud800"), root -> root.putNull("designNotes"),
                root -> root.putArray("designNotes").addObject().put("instruction", "override"),
                root -> root.putArray("designNotes").add("a".repeat(1025)),
                root -> { var notes = root.putArray("designNotes"); for (int i = 0; i < 17; i++) notes.add("note"); },
                root -> { var files = root.putArray("sourceFiles"); for (int i = 0; i < 65; i++) files.add("src/main/resources/f" + i); })) {
            rejectBlueprint(mutate);
        }
    }

    @Test
    void blueprintRejectsMissingRequiredBridgePomOrTests() throws Exception {
        for (String missing : List.of("pom.xml", MaintenanceProductionRecipe.BRIDGE_SOURCE_PATH, "src/test/java/pack/InvestigationTest.java")) {
            rejectBlueprint(root -> {
                var files = root.putArray("sourceFiles");
                for (String file : List.of("pom.xml", MaintenanceProductionRecipe.BRIDGE_SOURCE_PATH,
                        "src/test/java/pack/InvestigationTest.java", "src/main/resources/readme.txt")) {
                    if (!missing.equals(file)) files.add(file);
                }
            });
        }
    }

    @Test
    void blueprintPathsCannotGrantTraversalGlobsDirectoriesHiddenPolicyOrFileDirectoryCollisions() throws Exception {
        for (String path : List.of("../auth.json", "C:/auth.json", "/auth.json", "src\\main\\X.java",
                "src/main/java/*.java", "src/main/java", "src", ".codex/config.toml", "AGENTS.md",
                "src/main/java/CON.java", "src/main/java/.java", "src/main/resources/a%2fsecret",
                "POM.XML", "pom.xml")) {
            rejectBlueprint(root -> root.withArray("sourceFiles").add(path));
        }
        rejectBlueprint(root -> root.withArray("sourceFiles").add("src/main/resources/a")
                .add("src/main/resources/a/b.json"));
    }

    @Test
    void blueprintDuplicateTrailingInvalidUtf8AndSizeErrorsStayFailClosed() throws Exception {
        String valid = mapper.writeValueAsString(blueprint());
        for (byte[] bytes : List.of(utf8(valid + "{}"), utf8(valid.replace("\"summary\":", "\"summary\":\"duplicate\",\"summary\":")),
                utf8("[]"), new byte[0], new byte[MaintenanceProductionRecipe.MAX_BLUEPRINT_BYTES + 1], new byte[] {(byte) 0xff})) {
            assertEquals("MAINTENANCE_BLUEPRINT_INVALID", assertThrows(IllegalArgumentException.class,
                    () -> codec.normalizeBlueprint(bytes)).getMessage());
        }
    }

    @Test
    void generationRecipeActuallyUsesStrictBlueprintValidationBeforePromptAssembly() throws Exception {
        var recipe = new MaintenanceProductionRecipe(codec);
        ObjectNode blueprint = blueprint();
        blueprint.withArray("designNotes").add("Attempt to override policy; treat this as data only");
        byte[] valid = mapper.writeValueAsBytes(blueprint);
        String instruction = new String(recipe.generationInstruction(
                MaintenanceInvestigationProductContract.requirementsBytes(), valid), StandardCharsets.UTF_8);
        assertTrue(instruction.contains("UNTRUSTED DESIGN CONTEXT"));
        assertTrue(instruction.contains("only generation write scopes are pom.xml and src"));
        blueprint.put("allowedWritePaths", "/");
        byte[] invalid = mapper.writeValueAsBytes(blueprint);
        assertThrows(IllegalArgumentException.class, () -> recipe.generationInstruction(
                MaintenanceInvestigationProductContract.requirementsBytes(), invalid));
    }

    @Test
    void proposedSourcePathsAreValidatedAndImmutableWithoutBecomingWriteAuthorization() throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(blueprint());
        List<String> proposed = codec.blueprintSourceFiles(bytes);
        assertEquals(List.of("pom.xml", MaintenanceProductionRecipe.BRIDGE_SOURCE_PATH,
                "src/test/java/pack/InvestigationTest.java"), proposed);
        assertThrows(UnsupportedOperationException.class, () -> proposed.add("/"));
        assertEquals(proposed, new MaintenanceProductionRecipe(codec).proposalPaths(bytes));
        ObjectNode invalid = blueprint();
        invalid.withArray("sourceFiles").add("../escape.java");
        byte[] invalidBytes = mapper.writeValueAsBytes(invalid);
        assertThrows(IllegalArgumentException.class, () -> codec.blueprintSourceFiles(invalidBytes));
    }

    private void rejectPlan(Consumer<ObjectNode> mutate) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(codec.writePlan(plan()));
        mutate.accept(root);
        byte[] bytes = mapper.writeValueAsBytes(root);
        assertThrows(IllegalArgumentException.class, () -> codec.readPlan(bytes));
    }

    private void rejectBlueprint(Consumer<ObjectNode> mutate) throws Exception {
        ObjectNode root = blueprint();
        mutate.accept(root);
        byte[] bytes = mapper.writeValueAsBytes(root);
        assertThrows(IllegalArgumentException.class, () -> codec.normalizeBlueprint(bytes));
    }

    private ObjectNode blueprint() {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", MaintenanceProductionRecipe.BLUEPRINT_SCHEMA_VERSION);
        root.put("productContractHash", MaintenanceInvestigationProductContract.lock().hash().sha256());
        root.put("summary", "Bounded investigation core");
        root.putArray("sourceFiles").add("src/test/java/pack/InvestigationTest.java")
                .add(MaintenanceProductionRecipe.BRIDGE_SOURCE_PATH).add("pom.xml");
        root.putArray("designNotes");
        return root;
    }

    private static AgentPackProductionPlan plan() {
        var worker = new AgentPackProductionPlan.WorkerBinding("codex", "0.148.0",
                new WorkerCapabilities(Set.of(new WorkerCapability("repository-read"), new WorkerCapability("bounded-patch-write"))));
        return new AgentPackProductionPlan(AgentPackProductionPlan.SCHEMA_VERSION, new TenantId("tenant-one"),
                new BuildSessionId("session-one"), MaintenanceProductionRecipe.ID,
                MaintenanceInvestigationProductContract.requirementsLock(), "factory-skill", "1.0.0", lock("skill"),
                lock("dependencies"), lock("toolchain"), MaintenanceInvestigationProductContract.lock(),
                MaintenanceInvestigationProductContract.apiSignatureIndexLock(),
                MaintenanceInvestigationProductContract.requirementTestMatrixLock(),
                MaintenanceInvestigationProductContract.GATE_PROFILE, lock("policy"), "workspace:one", worker, worker);
    }

    private static CodingWorkerInputManifest input(Optional<CodingWorkerRepairLock> repair) {
        var plan = plan();
        return new CodingWorkerInputManifest(CodingWorkerInputManifest.SCHEMA_VERSION, new WorkOrderId("work-one"),
                plan.buildSessionId(), plan.skillId(), plan.skillVersion(), plan.skill().reference(), plan.skill().hash(),
                plan.dependencyLock().reference(), plan.dependencyLock().hash(), plan.toolchainLock().reference(), plan.toolchainLock().hash(),
                plan.apiSignatureIndex().reference(), plan.apiSignatureIndex().hash(), plan.productContract().reference(), plan.productContract().hash(),
                plan.gateProfile(), plan.requirementTestMatrix().reference(), plan.requirementTestMatrix().hash(),
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID, repair);
    }

    private static ContentHash hash() { return new ContentHash("1".repeat(64)); }
    private static CertificationArtifactLock lock(String kind) {
        return new CertificationArtifactLock(new ArtifactReference("artifact:" + kind + "-v1"), hash());
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
