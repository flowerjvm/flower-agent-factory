package io.github.flowerjvm.factory.application.decision;

/** Stable lifecycle of a durable human-decision request. */
public enum DecisionPointStatus {
    OPEN,
    APPROVED,
    CHANGES_REQUESTED,
    REJECTED,
    EXPIRED,
    STALE,
    CANCELLED;

    public boolean isTerminal() {
        return this != OPEN;
    }

    public boolean requiresDecisionRecord() {
        return this == APPROVED || this == CHANGES_REQUESTED || this == REJECTED;
    }
}
