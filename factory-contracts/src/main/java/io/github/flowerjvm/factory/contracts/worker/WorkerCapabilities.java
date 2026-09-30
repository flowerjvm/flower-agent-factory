package io.github.flowerjvm.factory.contracts.worker;

import java.util.Objects;
import java.util.Set;

/** Immutable snapshot of capabilities for a selected worker binding. */
public record WorkerCapabilities(Set<WorkerCapability> values) {
    public WorkerCapabilities {
        values = Set.copyOf(Objects.requireNonNull(values, "values"));
    }

    public boolean supportsAll(Set<WorkerCapability> required) {
        return values.containsAll(Objects.requireNonNull(required, "required"));
    }
}
