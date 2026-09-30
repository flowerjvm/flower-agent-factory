package io.github.flowerjvm.factory.application.verification;

/** Durable PR4 verifier-dispatch lifecycle; this is not the generic PR5 Worker outbox. */
public enum VerificationDispatchIntentStatus {
    PENDING,
    UNCERTAIN,
    RUNNING,
    COMPLETED,
    ORPHANED;

    public boolean isTerminal() {
        return this == COMPLETED || this == ORPHANED;
    }
}
