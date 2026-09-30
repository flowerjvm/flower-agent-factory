package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import java.time.Instant;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactVerificationEvidenceValidatorTest {
    @Test
    void scalarTerminalRowCannotAuthorizeReviewWithoutStoredManifest() {
        var validator = new ArtifactVerificationEvidenceValidator(
                new MemoryArtifactStore(), new EmptyCandidates(), new ObjectMapper(),
                new ArtifactReference("fixture"), hash("3"),
                new ArtifactReference("toolchain"), hash("2"));
        Instant created = Instant.parse("2026-08-13T00:00:00Z");
        var run = new VerificationRun(
                new VerificationRunId("verification-row-only"), new TenantId("tenant-a"),
                new BuildSessionId("session-a"), new CandidateId("candidate-a"), hash("1"),
                "factory-v0.1-pr4", hash("2"), hash("3"), VerificationRunStatus.PASSED,
                Optional.of(new ArtifactReference("missing-result")), Optional.of(hash("4")),
                Optional.of("VERIFIED"), Optional.of(VerificationDisposition.REVIEW_ELIGIBLE),
                Optional.of(created.plusSeconds(1)), Optional.of(created.plusSeconds(2)), 2,
                created, created.plusSeconds(2));

        assertFalse(validator.isReviewEligible(run));
    }

    private static ContentHash hash(String digit) {
        return new ContentHash(digit.repeat(64));
    }

    private static final class MemoryArtifactStore implements ArtifactStore {
        private final Map<String, Artifact> values = new HashMap<>();

        @Override
        public ArtifactReference store(Artifact artifact) {
            values.put(artifact.tenantId().value() + "\n" + artifact.reference().value(), artifact);
            return artifact.reference();
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(values.get(tenantId.value() + "\n" + reference.value()));
        }
    }

    private static final class EmptyCandidates implements CandidateVersionRepository {
        @Override public void create(CandidateVersion candidateVersion) { throw new UnsupportedOperationException(); }
        @Override public Optional<CandidateVersion> find(TenantId tenantId, CandidateId candidateId) {
            return Optional.empty();
        }
        @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                TenantId tenantId, BuildSessionId buildSessionId, WorkOrderId workOrderId) {
            return Optional.empty();
        }
    }
}
