package io.github.flowerjvm.factory.application.decision;

/** Immutable human decision submitted for a DecisionPoint. */
public enum DecisionOutcome {
    APPROVE,
    REQUEST_CHANGES,
    REJECT,
    CANCEL
}
