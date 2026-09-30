package io.github.flowerjvm.factory.application.build;

public enum BuildSessionStatus {
    DRAFT,
    RUNNING,
    WAITING_DECISION,
    REPAIRING,
    VERIFYING,
    EVALUATING,
    CERTIFYING,
    WAITING_RELEASE_REVIEW,
    CANDIDATE_READY_FOR_RELEASE,
    CERTIFIED_BUNDLE_READY,
    SUCCEEDED,
    CANCELLING,
    CANCELLED,
    MANUAL_REVIEW,
    BLOCKED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELLED || this == FAILED;
    }
}
