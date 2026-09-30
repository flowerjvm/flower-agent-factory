package io.github.flowerjvm.factory.contracts.worker;

/** Stable lifecycle values visible through the coding-worker port. */
public enum WorkerRunStatus {
    REQUESTED,
    DISPATCHING,
    WAITING_EXTERNAL,
    SUCCEEDED,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED,
    MANUAL_REVIEW,
    TIMED_OUT,
    RECONCILING;

    public boolean isTerminal() {
        return this == SUCCEEDED
                || this == FAILED
                || this == CANCELLED
                || this == MANUAL_REVIEW
                || this == TIMED_OUT;
    }
}
