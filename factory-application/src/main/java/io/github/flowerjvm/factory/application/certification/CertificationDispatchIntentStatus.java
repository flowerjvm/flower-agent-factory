package io.github.flowerjvm.factory.application.certification;

/** Durable lifecycle of one Action-owned Certification issuance attempt. */
public enum CertificationDispatchIntentStatus {
    PENDING,
    UNCERTAIN,
    RUNNING,
    COMPLETED,
    ORPHANED,
    ORPHANED_BEFORE_WAITING;

    public boolean isTerminal() {
        return this == COMPLETED || this == ORPHANED || this == ORPHANED_BEFORE_WAITING;
    }
}
