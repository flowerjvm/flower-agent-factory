package io.github.flowerjvm.factory.application.referenceassembly;

/** Durable lifecycle of one exact Reference Assembly production. */
public enum ReferenceAssemblyStatus {
    REQUESTED,
    COMPONENT_RESOLVED,
    ASSEMBLED,
    INSPECTED,
    RELEASED,
    REJECTED;

    public boolean isTerminal() {
        return this == RELEASED || this == REJECTED;
    }
}
