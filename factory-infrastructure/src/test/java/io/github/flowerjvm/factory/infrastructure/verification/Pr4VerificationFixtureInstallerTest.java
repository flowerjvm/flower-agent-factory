package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Pr4VerificationFixtureInstallerTest {
    @Test
    void checkedInFixtureHasPinnedCanonicalIdentityAndInstallsPerTenant() {
        var artifacts = new MemoryArtifacts();
        var installer = new Pr4VerificationFixtureInstaller(artifacts, new ObjectMapper());
        var expected = installer.descriptor();

        assertEquals("6a2ae8440eb6883de2abdd91256219344ee49b70cc1275668e8a9a0f7053ecf8",
                expected.hash().sha256());
        var tenant = new TenantId("tenant-first-run");
        assertEquals(expected, installer.install(tenant));
        assertTrue(artifacts.find(tenant, expected.reference()).isPresent());
    }

    @Test
    void validatorMutationControlRejectsWrongExpectedRule() {
        byte[] sarif = """
                {"version":"2.1.0","runs":[{"results":[{"ruleId":"FLOWER-CHECK-001"}]}]}
                """.getBytes(StandardCharsets.UTF_8);
        assertTrue(IndependentMavenVerifier.fixtureDetectsExpectedRules(
                Map.of("flower-check.sarif", sarif), Set.of("FLOWER-CHECK-001")));
        assertFalse(IndependentMavenVerifier.fixtureDetectsExpectedRules(
                Map.of("flower-check.sarif", sarif), Set.of("FLOWER-CHECK-999")));
    }

    private static final class MemoryArtifacts implements ArtifactStore {
        private final Map<String, Artifact> artifacts = new HashMap<>();
        @Override public ArtifactReference store(Artifact artifact) {
            artifacts.put(artifact.tenantId().value() + "\n" + artifact.reference().value(), artifact);
            return artifact.reference();
        }
        @Override public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(artifacts.get(tenantId.value() + "\n" + reference.value()));
        }
    }
}
