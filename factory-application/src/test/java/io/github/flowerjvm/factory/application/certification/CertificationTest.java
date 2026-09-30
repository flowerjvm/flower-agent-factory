package io.github.flowerjvm.factory.application.certification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CertificationTest {
    private static final Instant REQUESTED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void issuanceTimeIsTheTrustedInitialRequestTimeSoRetriesKeepTheSameManifestInput() {
        Certification requested = Certification.requested(
                new CertificationId("certification-001"), input(), lock("input", 'b'), REQUESTED_AT);

        Instant certifiedAt = REQUESTED_AT.plusSeconds(10);
        Certification certified = requested.certify(
                lock("manifest", 'c'), lock("evidence", 'd'), "action-001",
                certifiedAt, Optional.of(certifiedAt.plusSeconds(60)));
        assertEquals(Optional.of(REQUESTED_AT), certified.issuedAt());
        assertEquals(certifiedAt, certified.updatedAt());
        assertThrows(IllegalArgumentException.class, () -> requested.certify(
                lock("manifest", 'c'), lock("evidence", 'd'), "action-001",
                REQUESTED_AT.minusSeconds(1), Optional.empty()));
    }

    @Test
    void craftedRevocationCannotPrecedeIssuance() {
        Certification requested = Certification.requested(
                new CertificationId("certification-002"), input(), lock("input", 'b'), REQUESTED_AT);
        Certification certified = requested.certify(
                lock("manifest", 'c'), lock("evidence", 'd'), "action-001",
                REQUESTED_AT.plusSeconds(1), Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> new Certification(
                certified.certificationId(), certified.inputLock(), certified.inputLockArtifact(),
                CertificationStatus.REVOKED,
                certified.certificationManifest(), certified.certificationEvidence(), certified.actionRunId(),
                Optional.of("CERTIFICATION_REVOKED"), certified.issuedAt(), certified.expiresAt(),
                Optional.of(REQUESTED_AT.minusSeconds(1)), certified.version() + 1,
                certified.createdAt(), REQUESTED_AT.minusSeconds(1)));
    }

    private static CertificationInputLock input() {
        return new CertificationInputLock(
                CertificationInputLock.SCHEMA_VERSION,
                new TenantId("tenant-a"),
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                new BuildSessionId("build-001"),
                new WorkOrderId("work-001"),
                new CandidateId("candidate-001"),
                hash('1'),
                lock("source", '1'), lock("dependency", '2'), lock("toolchain", '3'),
                lock("generation", '4'), lock("product", '5'), lock("api", '6'),
                "sha256-ordinal-v1", "internal",
                new VerificationRunId("verification-001"), "verification-action-001",
                lock("verification", '7'), hash('8'), lock("policy", '9'), lock("compatibility", 'a'),
                "internal", "0.2.0", "0.1.3", "0.3.3");
    }

    private static CertificationArtifactLock lock(String name, char hash) {
        return new CertificationArtifactLock(new ArtifactReference("artifact:" + name), hash(hash));
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }
}
