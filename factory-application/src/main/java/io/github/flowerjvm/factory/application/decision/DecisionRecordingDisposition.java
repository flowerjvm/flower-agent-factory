package io.github.flowerjvm.factory.application.decision;

/** Stable result of atomically inserting a Decision and terminalizing its DecisionPoint. */
public enum DecisionRecordingDisposition {
    APPLIED,
    DUPLICATE,
    CONFLICT
}
