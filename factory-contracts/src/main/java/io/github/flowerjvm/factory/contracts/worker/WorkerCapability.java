package io.github.flowerjvm.factory.contracts.worker;

/** Stable capability identifier advertised by a coding worker binding. */
public record WorkerCapability(String value) {
    public WorkerCapability {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("worker capability must not be blank");
        }
    }
}
