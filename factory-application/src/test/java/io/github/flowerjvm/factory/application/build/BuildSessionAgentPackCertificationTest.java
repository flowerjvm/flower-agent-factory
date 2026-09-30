package io.github.flowerjvm.factory.application.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuildSessionAgentPackCertificationTest {
    private static final Instant NOW = Instant.parse("2026-09-02T01:00:00Z");
    private static final CandidateId CANDIDATE = new CandidateId("candidate-ready");
    private static final ContentHash CANDIDATE_HASH = hash('a');

    @Test
    void exactReleaseReadyAgentPackMovesToCertifyingWithoutBindingAnUnissuedCertification() {
        BuildSession ready = session(
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                Optional.of(CANDIDATE),
                Optional.of(CANDIDATE_HASH),
                Optional.empty(),
                NOW.plusSeconds(60));

        BuildSession certifying = ready.beginAgentPackCertification(NOW);

        assertEquals(BuildSessionStatus.CERTIFYING, certifying.status());
        assertEquals(BuildSessionPhase.CERTIFY, certifying.currentPhase());
        assertEquals(ready.currentCandidateId(), certifying.currentCandidateId());
        assertEquals(ready.currentCandidateHash(), certifying.currentCandidateHash());
        assertEquals(Optional.empty(), certifying.currentCertificationId());
        assertEquals(ready.version() + 1, certifying.version());
        assertEquals(NOW, certifying.updatedAt());
    }

    @Test
    void transitionRejectsAnyDifferentLineStatePhaseOrIncompleteCandidate() {
        assertRejected(session(
                new ProductLineId("tos"),
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                Optional.of(CANDIDATE), Optional.of(CANDIDATE_HASH), Optional.empty(), NOW.plusSeconds(60)));
        assertRejected(session(
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                Optional.of(CANDIDATE), Optional.of(CANDIDATE_HASH), Optional.empty(), NOW.plusSeconds(60)));
        assertRejected(session(
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.CERTIFY,
                Optional.of(CANDIDATE), Optional.of(CANDIDATE_HASH), Optional.empty(), NOW.plusSeconds(60)));
        assertRejected(session(
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                Optional.empty(), Optional.empty(), Optional.empty(), NOW.plusSeconds(60)));
    }

    @Test
    void transitionRejectsCancellationAndDeadlineBoundary() {
        assertRejected(session(
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                Optional.of(CANDIDATE), Optional.of(CANDIDATE_HASH), Optional.of(NOW.minusSeconds(1)),
                NOW.plusSeconds(60)));
        assertRejected(session(
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                Optional.of(CANDIDATE), Optional.of(CANDIDATE_HASH), Optional.empty(), NOW));
    }

    private static void assertRejected(BuildSession session) {
        assertThrows(IllegalStateException.class, () -> session.beginAgentPackCertification(NOW));
    }

    private static BuildSession session(
            ProductLineId productLine,
            BuildSessionStatus status,
            BuildSessionPhase phase,
            Optional<CandidateId> candidateId,
            Optional<ContentHash> candidateHash,
            Optional<Instant> cancellation,
            Instant deadline) {
        return new BuildSession(
                new BuildSessionId("build-certification-transition"),
                new TenantId("tenant-certification-transition"),
                new ProjectId("project-certification-transition"),
                productLine,
                "request-key",
                "test",
                status,
                phase,
                new ArtifactReference("requirements"),
                hash('f'),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                candidateId,
                candidateHash,
                Optional.empty(),
                0,
                1,
                NOW.minusSeconds(600),
                deadline,
                cancellation,
                Optional.empty(),
                Optional.empty(),
                7,
                NOW.minusSeconds(600),
                NOW.minusSeconds(1));
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }
}
