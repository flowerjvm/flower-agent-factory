package io.github.flowerjvm.factory.contracts.certification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CertifiedAgentComponentContractTest {
    private static final ContentHash HASH =
            new ContentHash("1111111111111111111111111111111111111111111111111111111111111111");

    @Test
    void downstreamReferenceCarriesExactAgentPackLocksButNoRoutingTenant() {
        CertifiedAgentComponentRef reference = new CertifiedAgentComponentRef(
                CertifiedAgentComponentRef.SCHEMA_VERSION,
                "embedded-agent-pack",
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                new CertificationId("cert-001"),
                lock("artifact:certification:001"),
                new CandidateId("candidate-001"),
                HASH,
                lock("artifact:source:001"),
                lock("artifact:input:001"),
                new VerificationRunId("verification-001"),
                lock("artifact:verification:001"),
                lock("artifact:compatibility:001"),
                lock("artifact:evidence:001"),
                "internal");

        assertEquals(CertifiedArtifactType.AGENT_PACK, reference.artifactType());
        assertFalse(Arrays.stream(CertifiedAgentComponentRef.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("tenantId")));
    }

    @Test
    void floatingOrLatestReferencesAreRejectedAtTheContractBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> lock("artifact:agent-pack:latest"));
        assertThrows(IllegalArgumentException.class,
                () -> new CertifiedAgentComponentRef(
                        CertifiedAgentComponentRef.SCHEMA_VERSION,
                        "latest",
                        ProductLineId.AGENT_PACK,
                        CertifiedArtifactType.AGENT_PACK,
                        new CertificationId("cert-001"),
                        lock("artifact:certification:001"),
                        new CandidateId("candidate-001"),
                        HASH,
                        lock("artifact:source:001"),
                        lock("artifact:input:001"),
                        new VerificationRunId("verification-001"),
                        lock("artifact:verification:001"),
                        lock("artifact:compatibility:001"),
                        lock("artifact:evidence:001"),
                        "internal"));
    }

    @Test
    void compatibilityDescriptorCannotClaimAnotherProductLine() {
        assertThrows(IllegalArgumentException.class, () -> new AgentPackCompatibilityDescriptor(
                AgentPackCompatibilityDescriptor.SCHEMA_VERSION,
                new io.github.flowerjvm.factory.contracts.ids.TenantId("tenant-a"),
                new ProductLineId("tos"),
                CertifiedArtifactType.AGENT_PACK,
                new CandidateId("candidate-001"),
                HASH,
                lock("artifact:contract:001"),
                lock("artifact:api:001"),
                lock("artifact:dependency:001"),
                lock("artifact:toolchain:001"),
                "internal",
                "sha256-ordinal-v1",
                "0.2.0",
                "0.1.3",
                "0.3.3"));
    }

    @Test
    void mutableSnapshotVersionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentPackCompatibilityDescriptor(
                AgentPackCompatibilityDescriptor.SCHEMA_VERSION,
                new io.github.flowerjvm.factory.contracts.ids.TenantId("tenant-a"),
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                new CandidateId("candidate-001"),
                HASH,
                lock("artifact:contract:001"),
                lock("artifact:api:001"),
                lock("artifact:dependency:001"),
                lock("artifact:toolchain:001"),
                "internal",
                "sha256-ordinal-v1",
                "0.2.0-SNAPSHOT",
                "0.1.3",
                "0.3.3"));
    }

    private static CertificationArtifactLock lock(String reference) {
        return new CertificationArtifactLock(new ArtifactReference(reference), HASH);
    }
}
