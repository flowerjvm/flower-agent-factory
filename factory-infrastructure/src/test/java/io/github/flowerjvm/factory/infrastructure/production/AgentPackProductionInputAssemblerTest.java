package io.github.flowerjvm.factory.infrastructure.production;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.production.MaintenanceRepairDemoScenario;
import io.github.flowerjvm.factory.application.production.MaintenanceProductionRecipe;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.infrastructure.verification.OrdinalSourceTreeHasher;
import io.github.flowerjvm.factory.infrastructure.verification.Pr4MavenToolchainInstaller;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentPackProductionInputAssemblerTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TenantId tenant = new TenantId("tenant-production-inputs");

    @Test
    void demoV2PolicyLocksTheBuildRecipeAndVersionsRemainSeparate() throws Exception {
        var assembler = assembler(temporary.resolve("absent/skills"));
        var v1 = assembler.policyArtifact(tenant, MaintenanceRepairDemoScenario.ID);
        var v2 = assembler.policyArtifact(tenant, MaintenanceRepairDemoScenario.ID_V2);
        assertNotEquals(v1.contentHash(), v2.contentHash());
        assertNotEquals(v1.reference(), v2.reference());
        assertEquals(MaintenanceRepairDemoScenario.policy(MaintenanceRepairDemoScenario.RECIPE_ID_V2).hash(), v2.contentHash());
        assertEquals(new OrdinalSourceTreeHasher().sha256(v2.content()), v2.contentHash());
        var tree = mapper.readTree(v2.content());
        assertEquals("factory.maintenance-repair-demo-policy.v2", tree.path("schemaVersion").asText());
        assertEquals(MaintenanceRepairDemoScenario.ID_V2, tree.path("scenarioId").asText());
        assertEquals(MaintenanceRepairDemoScenario.RECIPE_ID_V2, tree.path("recipeId").asText());
        assertEquals("\n" + tree.path("mavenBuildRecipe").asText(),
                MaintenanceRepairDemoScenario.buildInstruction(MaintenanceRepairDemoScenario.RECIPE_ID_V2));
        assertFalse(mapper.readTree(v1.content()).has("mavenBuildRecipe"));
        assertEquals("CASE_INSENSITIVE", tree.path("targetCaseId").asText());
        for (String flag : java.util.List.of("networkAllowed", "webSearchAllowed", "credentialAccessAllowed",
                "deploymentAllowed", "operationAllowed", "productContractChanged")) assertFalse(tree.path(flag).asBoolean(true));
        assertArrayEquals(v1.content(), assembler.policyArtifact(tenant, MaintenanceRepairDemoScenario.ID).content());
        assertArrayEquals(assembler.policyArtifact(tenant).content(), assembler.policyArtifact(tenant, "").content());
    }

    @Test
    void demoPolicyDeclaresOneFaultWithoutChangingNormalPolicyOrGrantingAuthority() throws Exception {
        var assembler = assembler(temporary.resolve("absent/skills"));
        var normal = assembler.policyArtifact(tenant);
        assertArrayEquals(normal.content(), assembler.policyArtifact(tenant, "").content());
        var demo = assembler.policyArtifact(tenant, MaintenanceRepairDemoScenario.ID);
        assertEquals(MaintenanceRepairDemoScenario.policy().reference(), demo.reference());
        assertEquals(MaintenanceRepairDemoScenario.policy().hash(), demo.contentHash());
        assertNotEquals(normal.contentHash(), demo.contentHash());
        var tree = mapper.readTree(demo.content());
        assertEquals("factory.maintenance-repair-demo-policy.v1", tree.path("schemaVersion").asText());
        assertEquals(MaintenanceRepairDemoScenario.ID, tree.path("scenarioId").asText());
        assertEquals(MaintenanceRepairDemoScenario.RECIPE_ID, tree.path("recipeId").asText());
        assertEquals("CASE_INSENSITIVE", tree.path("targetCaseId").asText());
        assertTrue(tree.path("firstDraftDefect").asText().contains("Only at repairRound 0"));
        assertTrue(tree.path("repairAuthority").asText().contains("actual canonical independent-verification failure"));
        for (String flag : java.util.List.of("networkAllowed", "webSearchAllowed", "credentialAccessAllowed",
                "deploymentAllowed", "operationAllowed", "productContractChanged")) assertFalse(tree.path(flag).asBoolean(true));
        assertEquals(MaintenanceProductionRecipe.ID, mapper.readTree(normal.content()).path("recipeId").asText());
        assertArrayEquals(normal.content(), assembler.policyArtifact(tenant).content());
        assertThrows(IllegalArgumentException.class, () -> assembler.policyArtifact(tenant, "unregistered-demo"));
    }

    @Test
    void installedSourceBundlePreservesEveryAllowedOriginalByteAndHashWithoutStoreAccess() throws Exception {
        Path root = ProductionInputTestFixtures.plugin(temporary);
        Files.writeString(root.resolve("do-not-read.txt"), "outside explicit source allowlist");
        var assembler = assembler(root);
        var artifact = assembler.skillArtifact(tenant);
        var tree = mapper.readTree(artifact.content());
        assertEquals(16, tree.path("files").size());
        assertEquals("0.3.3", tree.path("pluginVersion").asText());
        var seen = new HashSet<String>();
        var hasher = new OrdinalSourceTreeHasher();
        for (var file : tree.path("files")) {
            String relative = file.path("path").asText();
            assertTrue(seen.add(relative));
            byte[] source = Files.readAllBytes(root.resolve(relative));
            assertArrayEquals(source, file.path("text").asText().getBytes(StandardCharsets.UTF_8));
            assertEquals(source.length, file.path("sizeBytes").asInt());
            assertEquals(hasher.sha256(source).sha256(), file.path("contentHash").asText());
        }
        assertEquals(new HashSet<>(AgentPackProductionInputAssembler.SKILL_PATHS), seen);
        assertEquals(hasher.sha256(artifact.content()), artifact.contentHash());
        assertArrayEquals(artifact.content(), assembler.skillArtifact(tenant).content());
        assertFalse(new String(artifact.content(), StandardCharsets.UTF_8).contains("outside explicit source allowlist"));
        assertEquals(artifact.contentHash(), assembler.skillArtifact(new TenantId("other-tenant")).contentHash());
    }

    @Test
    void changedAllowedSourceGetsNewContentAddressRatherThanCachedOrRewrittenText() throws Exception {
        Path root = ProductionInputTestFixtures.plugin(temporary);
        var assembler = assembler(root);
        var before = assembler.skillArtifact(tenant);
        Path source = root.resolve("flower-app-guide/references/10-flow-step-authoring.md");
        Files.writeString(source, "changed original text\r\n", StandardCharsets.UTF_8);
        var after = assembler.skillArtifact(tenant);
        assertNotEquals(before.contentHash(), after.contentHash());
        assertNotEquals(before.reference(), after.reference());
        assertEquals("changed original text\r\n", Files.readString(source));
    }

    @Test
    void missingOrLegacySkillRootAndWrongPluginVersionFailClosed() throws Exception {
        Path root = ProductionInputTestFixtures.plugin(temporary);
        assertThrows(IllegalArgumentException.class, () -> assembler(root.resolve("flower-app-guide")).skillArtifact(tenant));
        Files.writeString(root.getParent().resolve(".codex-plugin/plugin.json"),
                "{\"name\":\"flower\",\"version\":\"0.2.0\",\"skills\":\"./skills/\"}");
        assertThrows(IllegalArgumentException.class, () -> assembler(root).skillArtifact(tenant));
    }

    @Test
    void missingReferenceOrUnsupportedGuideVersionFailsClosed() throws Exception {
        Path root = ProductionInputTestFixtures.plugin(temporary);
        Path version = root.resolve("flower-app-guide/references/00-guide-version.md");
        Files.writeString(version, "Guide version: `99.0.0`\nTarget Flower version: `0.1.3`");
        assertThrows(IllegalArgumentException.class, () -> assembler(root).skillArtifact(tenant));
        Files.delete(version);
        assertThrows(IllegalArgumentException.class, () -> assembler(root).skillArtifact(tenant));
    }

    @Test
    void malformedUtf8OversizedAndSpecialDirectorySourcesFailClosed() throws Exception {
        Path root = ProductionInputTestFixtures.plugin(temporary);
        Path source = root.resolve("flower-app-guide/references/10-flow-step-authoring.md");
        Files.write(source, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThrows(IllegalArgumentException.class, () -> assembler(root).skillArtifact(tenant));
        Files.write(source, new byte[AgentPackProductionInputAssembler.MAX_TEXT_FILE_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> assembler(root).skillArtifact(tenant));
        Files.delete(source);
        Files.createDirectory(source);
        assertThrows(IllegalArgumentException.class, () -> assembler(root).skillArtifact(tenant));
        Path aggregate = ProductionInputTestFixtures.plugin(temporary.resolve("aggregate"));
        for (String relative : AgentPackProductionInputAssembler.SKILL_PATHS) {
            Path file = aggregate.resolve(relative);
            Files.writeString(file, Files.readString(file) + "x".repeat(135_000));
        }
        assertThrows(IllegalArgumentException.class, () -> assembler(aggregate).skillArtifact(tenant));
    }

    @Test
    void duplicatePluginJsonFieldsAreNotAcceptedAsInstalledMetadata() throws Exception {
        Path root = ProductionInputTestFixtures.plugin(temporary);
        Files.writeString(root.getParent().resolve(".codex-plugin/plugin.json"),
                "{\"name\":\"old\",\"name\":\"flower\",\"version\":\"0.3.3\",\"skills\":\"./skills/\"}");
        assertThrows(IllegalArgumentException.class, () -> assembler(root).skillArtifact(tenant));
    }

    @Test
    void fixedPublicDependencySelectionHasNineCoordinatesAndExactlyOnePomAndJarEach() {
        assertEquals(9, AgentPackProductionInputAssembler.DEPENDENCY_COORDINATES.size());
        var paths = AgentPackProductionInputAssembler.dependencyPaths();
        assertEquals(18, paths.size());
        assertEquals(18, new HashSet<>(paths).size());
        assertEquals(9, paths.stream().filter(path -> path.endsWith(".pom")).count());
        assertEquals(9, paths.stream().filter(path -> path.endsWith(".jar")).count());
        assertTrue(paths.contains("io/github/flowerjvm/flower-core/0.1.3/flower-core-0.1.3.jar"));
        assertFalse(paths.stream().anyMatch(path -> path.contains("SNAPSHOT") || path.contains("action-runtime")));
    }

    @Test
    void codeOwnedPolicyIsCanonicalAndDoesNotGrantBuildDeploymentOrApprovalAuthority() throws Exception {
        var assembler = assembler(temporary.resolve("absent/skills"));
        var policy = assembler.policyArtifact(tenant);
        var tree = mapper.readTree(policy.content());
        assertFalse(tree.path("networkAllowed").asBoolean());
        assertFalse(tree.path("credentialAccessAllowed").asBoolean());
        assertFalse(tree.path("deploymentAllowed").asBoolean());
        assertFalse(tree.path("operationAllowed").asBoolean());
        assertTrue(tree.path("workerBuildEnvironment").asText().contains("not provisioned"));
        assertTrue(tree.path("releasePolicy").asText().contains("human release review"));
        assertArrayEquals(policy.content(), assembler.policyArtifact(tenant).content());
    }

    private AgentPackProductionInputAssembler assembler(Path root) {
        return new AgentPackProductionInputAssembler(new Pr4MavenToolchainInstaller(
                ProductionInputTestFixtures.NO_STORE_ACCESS, mapper, temporary.resolve("absent-toolchain")), root, mapper);
    }
}
