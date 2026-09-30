package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.verification.VerificationRun;

/**
 * Commits only an OPEN review after locking the exact live session and verification snapshots.
 * The caller must first validate generation-selected independent evidence and its canonical Action
 * owner. Candidate/artifact locks and successful terminal evidence are immutable; implementations
 * must reject stale snapshots, cancellation, elapsed deadlines and conflicting prior reviews.
 */
@FunctionalInterface
public interface AgentPackReleaseReviewTransaction {
    AgentPackReleaseReviewResult ensureOpen(
            BuildSession expectedSession,
            CandidateVersion expectedCandidate,
            VerificationRun expectedVerification,
            DecisionPoint requestedPoint);
}
