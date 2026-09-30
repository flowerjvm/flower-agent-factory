package io.github.flowerjvm.factory.application.work;

/** Durable processing state for a verified, tokenless Worker completion event. */
public enum WorkerCallbackInboxStatus {
    RECEIVED,
    PROCESSING,
    APPLIED,
    SUPERSEDED,
    REJECTED,
    MANUAL_REVIEW;

    public boolean isTerminal() {
        return this == APPLIED || this == SUPERSEDED || this == REJECTED || this == MANUAL_REVIEW;
    }
}
