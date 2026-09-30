package io.github.flowerjvm.factory.application.verification;

/** Durable lifecycle of one deterministic verification gate execution. */
public enum VerificationRunStatus {
    REQUESTED,
    RUNNING,
    PASSED,
    FAILED;

    public boolean isTerminal() {
        return this == PASSED || this == FAILED;
    }
}
