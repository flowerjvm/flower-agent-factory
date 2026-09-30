package io.github.flowerjvm.factory.application.outbox;

/** Durable delivery state for an at-least-once-capable outbound command. */
public enum DispatchOutboxStatus {
    PENDING,
    DISPATCHING,
    DISPATCHED,
    RETRY_WAIT,
    CONFIRMED,
    SUPERSEDED,
    MANUAL_REVIEW;

    public boolean isTerminal() {
        return this == DISPATCHED
                || this == CONFIRMED
                || this == SUPERSEDED
                || this == MANUAL_REVIEW;
    }
}
