package io.github.flowerjvm.factory.application.referenceassembly;

/** Durable lifecycle of one Action-owned Reference Assembly release attempt. */
public enum ReferenceAssemblyReleaseDispatchIntentStatus {
    PENDING,
    RUNNING,
    UNCERTAIN,
    COMPLETED,
    ORPHANED,
    ORPHANED_BEFORE_WAITING;

    public boolean isTerminal() {
        return this == COMPLETED || this == ORPHANED || this == ORPHANED_BEFORE_WAITING;
    }
}
