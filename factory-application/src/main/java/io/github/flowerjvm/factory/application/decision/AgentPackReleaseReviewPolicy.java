package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/** The bounded Agent Pack review policy shared by application admission and atomic JDBC commit. */
public record AgentPackReleaseReviewPolicy(Duration reviewWindow) {
    public static final String OPTIONS_SCHEMA_ID = "factory.release-review-options.v1";
    public static final String REQUIRED_PERMISSION = "factory.agent.release.approve";
    public static final String CONFLICT = "AGENT_PACK_RELEASE_REVIEW_CONFLICT";
    public static final String INELIGIBLE = "AGENT_PACK_RELEASE_REVIEW_INELIGIBLE";
    private static final String QUESTION_PREFIX = "artifact:agent-pack-release-review-question:";

    public AgentPackReleaseReviewPolicy {
        if (reviewWindow == null || reviewWindow.isNegative() || reviewWindow.isZero()
                || !reviewWindow.equals(reviewWindow.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("reviewWindow must be positive and microsecond precise");
        }
    }

    public byte[] bytes() {
        return fields("factory.agent-pack-release-review-policy.v1", reviewWindow.toString(),
                OPTIONS_SCHEMA_ID, REQUIRED_PERMISSION, "1");
    }

    public ArtifactReference reference() {
        return new ArtifactReference("artifact:agent-pack-release-review-policy:" + hash(bytes()).sha256());
    }

    public Instant dueAt(BuildSession session, Instant openedAt) {
        Instant policyDeadline = openedAt.plus(reviewWindow);
        return session.deadlineAt().isBefore(policyDeadline) ? session.deadlineAt() : policyDeadline;
    }

    public static void requireEligible(
            BuildSession session, CandidateVersion candidate, VerificationRun verification, Instant now) {
        if (!ProductLineId.AGENT_PACK.equals(session.productLineId())
                || session.status() != BuildSessionStatus.WAITING_RELEASE_REVIEW
                || session.currentPhase() != BuildSessionPhase.HUMAN_RELEASE_REVIEW
                || session.cancellationRequestedAt().isPresent()
                || session.currentCertificationId().isPresent()
                || !now.isBefore(session.deadlineAt()) || now.isBefore(session.updatedAt())
                || !session.tenantId().equals(candidate.tenantId())
                || !session.buildSessionId().equals(candidate.buildSessionId())
                || session.currentCandidateId().filter(candidate.candidateId()::equals).isEmpty()
                || session.currentCandidateHash().filter(candidate.sourceHash()::equals).isEmpty()
                || !session.tenantId().equals(verification.tenantId())
                || !session.buildSessionId().equals(verification.buildSessionId())
                || !candidate.candidateId().equals(verification.candidateId())
                || !candidate.sourceHash().equals(verification.candidateHash())
                || !candidate.toolchainLockHash().equals(verification.toolchainLockHash())
                || verification.status() != VerificationRunStatus.PASSED
                || verification.disposition().filter(VerificationDisposition.REVIEW_ELIGIBLE::equals).isEmpty()
                || verification.resultManifestHash().filter(VerificationRun.LEGACY_RESULT_MANIFEST_HASH::equals)
                        .isPresent()
                || now.isBefore(candidate.createdAt()) || now.isBefore(verification.updatedAt())) {
            throw new IllegalArgumentException(INELIGIBLE);
        }
    }

    public static DecisionPointId decisionPointId(BuildSession session, CandidateVersion candidate) {
        // One immutable question per exact candidate. A different verification cannot silently
        // create another approvable review for the same candidate after restart.
        return new DecisionPointId("agent-pack-release-review-" + hash(fields(
                session.tenantId().value(), session.buildSessionId().value(),
                candidate.candidateId().value(), candidate.sourceHash().sha256())).sha256());
    }

    public byte[] questionBytes(
            BuildSession session, CandidateVersion candidate, VerificationRun verification,
            ContentHash sourceManifestContentHash) {
        return fields("factory.agent-pack-release-review-question.v1",
                "Approve, request changes, reject, or cancel this exact independently verified Agent Pack.",
                session.tenantId().value(), session.buildSessionId().value(),
                session.requirementsArtifactRef().value(), session.requirementsHash().sha256(),
                candidate.candidateId().value(), candidate.sourceHash().sha256(),
                candidate.sourceManifestRef().value(), sourceManifestContentHash.sha256(),
                candidate.dependencyLockRef().value(), candidate.dependencyLockHash().sha256(),
                candidate.toolchainLockRef().value(), candidate.toolchainLockHash().sha256(),
                candidate.createdByWorkOrderId().value(), verification.verificationRunId().value(),
                Long.toString(verification.version()), verification.gateProfile(),
                verification.fixtureSetHash().sha256(), verification.resultManifestRef().orElseThrow().value(),
                verification.resultManifestHash().orElseThrow().sha256(),
                verification.terminalCode().orElseThrow(), reference().value());
    }

    public ArtifactReference questionReference(byte[] question) {
        return new ArtifactReference(QUESTION_PREFIX + hash(question).sha256());
    }

    public DecisionPoint requestedPoint(
            BuildSession session, CandidateVersion candidate, ArtifactReference question, Instant now) {
        return new DecisionPoint(decisionPointId(session, candidate), session.tenantId(),
                session.buildSessionId(), DecisionPoint.RELEASE_REVIEW_TYPE, DecisionPointStatus.OPEN,
                AgentPackReleaseReviewDecisionSubjectAuthority.SUBJECT_TYPE, candidate.candidateId().value(),
                0, candidate.sourceHash(), question, OPTIONS_SCHEMA_ID, Set.of(REQUIRED_PERMISSION), 1,
                reference(), now, dueAt(session, now), Optional.empty(), Optional.empty(), 0);
    }

    public void requireRequested(
            BuildSession session, CandidateVersion candidate, VerificationRun verification,
            DecisionPoint point, Instant now) {
        requireEligible(session, candidate, verification, now);
        if (!point.equals(requestedPoint(session, candidate, point.questionArtifactRef(), point.openedAt()))
                || !point.questionArtifactRef().value().matches(QUESTION_PREFIX + "[0-9a-f]{64}")
                || point.openedAt().isAfter(now) || point.openedAt().isBefore(session.updatedAt())
                || !point.openedAt().equals(point.openedAt().truncatedTo(ChronoUnit.MICROS))
                || !now.isBefore(point.dueAt())) {
            throw new IllegalArgumentException(CONFLICT);
        }
    }

    public void requireExisting(
            BuildSession session, CandidateVersion candidate, DecisionPoint requested, DecisionPoint stored,
            Instant now) {
        DecisionPoint original = requestedPoint(session, candidate, requested.questionArtifactRef(), stored.openedAt());
        // Terminal decisions are observed, never changed or replaced. Existing human decisions
        // remain the authority; an expired OPEN is not reopened with a later deadline.
        DecisionPoint storedAsOpen = new DecisionPoint(stored.decisionPointId(), stored.tenantId(),
                stored.buildSessionId(), stored.type(), DecisionPointStatus.OPEN, stored.subjectType(),
                stored.subjectId(), stored.subjectVersion(), stored.subjectHash(), stored.questionArtifactRef(),
                stored.optionsSchemaId(), stored.requiredPermissions(), stored.minimumApprovers(),
                stored.policySnapshotRef(), stored.openedAt(), stored.dueAt(), Optional.empty(), Optional.empty(), 0);
        if (!storedAsOpen.equals(original) || stored.openedAt().isAfter(now)
                || stored.openedAt().isBefore(candidate.createdAt())
                || (stored.status() == DecisionPointStatus.OPEN && (stored.version() != 0 || !now.isBefore(stored.dueAt())))
                || (stored.status() != DecisionPointStatus.OPEN && stored.version() < 1)) {
            throw new IllegalArgumentException(CONFLICT);
        }
    }

    public static ContentHash hash(byte[] bytes) {
        try {
            return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] fields(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            result.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value).append('\n');
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }
}
