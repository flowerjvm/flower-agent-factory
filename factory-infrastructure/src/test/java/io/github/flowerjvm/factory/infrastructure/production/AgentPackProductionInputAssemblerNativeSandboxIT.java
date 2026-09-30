package io.github.flowerjvm.factory.infrastructure.production;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPlan;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.MavenDependencyLock;
import io.github.flowerjvm.factory.contracts.verification.MavenToolchainLock;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.infrastructure.verification.OrdinalSourceTreeHasher;
import io.github.flowerjvm.factory.infrastructure.verification.Pr4MavenToolchainInstaller;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real pinned public Maven cache proof. The optional absolute-path system property
 * factory.production.test.flowerSkillRoot selects read-only installed plugin sources; otherwise
 * explicit synthetic Skill fixtures are used. Neither mode runs a Worker or produces a product.
 */
class AgentPackProductionInputAssemblerNativeSandboxIT {
    private static final String SKILL_ROOT_PROPERTY = "factory.production.test.flowerSkillRoot";
    @TempDir Path temporary;

    @Test
    void realPinnedPublicBytesAssembleAllExactLocksWithoutArtifactStoreAccess(TestReporter reporter) throws Exception {
        var mapper = new ObjectMapper();
        var tenant = new TenantId("native-read-only-inputs");
        Path cache = Pr4MavenToolchainInstaller.configuredCache();
        var installer = new Pr4MavenToolchainInstaller(ProductionInputTestFixtures.NO_STORE_ACCESS, mapper, cache);
        String configuredSkillRoot = System.getProperty(SKILL_ROOT_PROPERTY);
        boolean actualInstalledSkill = configuredSkillRoot != null;
        Path skillRoot;
        if (actualInstalledSkill) {
            assertFalse(configuredSkillRoot.isBlank(), "configured installed Skill root must not be blank");
            skillRoot = Path.of(configuredSkillRoot);
            assertTrue(skillRoot.isAbsolute(), "configured installed Skill root must be an absolute path");
            skillRoot = skillRoot.normalize();
        } else {
            skillRoot = ProductionInputTestFixtures.plugin(temporary);
        }
        reporter.publishEntry("skillSourceMode", actualInstalledSkill ? "actual-installed-plugin" : "synthetic-fixture");
        reporter.publishEntry("workerExecutions", "0");
        var assembler = new AgentPackProductionInputAssembler(installer, skillRoot, mapper);
        var binding = new AgentPackProductionPlan.WorkerBinding("test-binding", "1", new WorkerCapabilities(Set.of()));
        var inputs = assembler.prepare(tenant, new BuildSessionId("native-preflight-session"), "bound-workspace", binding, binding);
        var retry = assembler.prepare(tenant, new BuildSessionId("native-preflight-session"), "bound-workspace", binding, binding);
        var skillBundle = mapper.readTree(find(inputs, inputs.plan().skill().reference().value()).content());
        assertEquals("0.3.3", skillBundle.path("pluginVersion").asText());
        assertEquals(16, skillBundle.path("files").size());
        reporter.publishEntry("pluginVersion", skillBundle.path("pluginVersion").asText());
        reporter.publishEntry("skillBundleHash", inputs.plan().skill().hash().sha256());
        reporter.publishEntry("pluginManifestHash", skillBundle.path("pluginManifestHash").asText());
        assertEquals(inputs.plan(), retry.plan());
        assertEquals(27, inputs.artifacts().size());
        assertThrows(UnsupportedOperationException.class, () -> inputs.artifacts().add(inputs.artifacts().getFirst()));
        assertEquals(MaintenanceInvestigationProductContract.lock(), inputs.plan().productContract());
        assertEquals(MaintenanceInvestigationProductContract.requirementsLock(), inputs.plan().requirements());
        assertEquals(MaintenanceInvestigationProductContract.GATE_PROFILE, inputs.plan().gateProfile());
        assertEquals(Pr4MavenToolchainInstaller.EXPECTED_HASH, inputs.plan().toolchainLock().hash());
        var hashes = new OrdinalSourceTreeHasher();
        for (int index = 0; index < inputs.artifacts().size(); index++) {
            Artifact artifact = inputs.artifacts().get(index);
            assertEquals(tenant, artifact.tenantId());
            assertEquals(hashes.sha256(artifact.content()), artifact.contentHash());
            assertEquals(artifact.reference(), retry.artifacts().get(index).reference());
            assertArrayEquals(artifact.content(), retry.artifacts().get(index).content());
        }
        Artifact dependency = find(inputs, inputs.plan().dependencyLock().reference().value());
        MavenDependencyLock lock = mapper.readValue(dependency.content(), MavenDependencyLock.class);
        assertEquals(18, lock.fileCount());
        assertEquals(AgentPackProductionInputAssembler.DEPENDENCY_COORDINATES, lock.expectedDependencyCoordinates());
        assertEquals(AgentPackProductionInputAssembler.dependencyPaths(), lock.files().stream().map(file -> file.path()).toList());
        for (var entry : lock.files()) {
            Artifact artifact = find(inputs, entry.artifactRef().value());
            assertArrayEquals(Files.readAllBytes(cache.resolve(entry.path())), artifact.content());
        }
        MavenToolchainLock toolchain = mapper.readValue(find(inputs, inputs.plan().toolchainLock().reference().value()).content(), MavenToolchainLock.class);
        assertTrue(toolchain.fileCount() > 18);
        assertTrue(inputs.artifacts().stream().mapToLong(value -> value.content().length).sum() < 32L * 1024 * 1024);
        assertFalse(inputs.artifacts().stream().anyMatch(value -> value.mediaType().equals("text/x-java-source")));
        assertThrows(IllegalArgumentException.class, () -> installer.repositoryArtifacts(tenant, List.of("not/pinned/1/pinned-1.jar")));
        Artifact manifest = installer.manifestArtifact(tenant);
        byte[] alteredCallerCopy = manifest.content();
        alteredCallerCopy[0] ^= 1;
        assertEquals(Pr4MavenToolchainInstaller.EXPECTED_HASH, hashes.sha256(installer.manifestArtifact(tenant).content()));
    }

    private static Artifact find(AgentPackProductionInputAssembler.PreparedInputs inputs, String reference) {
        return inputs.artifacts().stream().filter(value -> value.reference().value().equals(reference)).findFirst().orElseThrow();
    }
}
