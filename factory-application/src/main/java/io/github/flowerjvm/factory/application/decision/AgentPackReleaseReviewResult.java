package io.github.flowerjvm.factory.application.decision;

import java.util.Objects;

/** Opening a review never records a human Decision or grants release approval. */
public record AgentPackReleaseReviewResult(Disposition disposition, DecisionPoint decisionPoint) {
    public AgentPackReleaseReviewResult {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(decisionPoint, "decisionPoint");
    }

    public enum Disposition { CREATED, EXISTING_EXACT }
}
