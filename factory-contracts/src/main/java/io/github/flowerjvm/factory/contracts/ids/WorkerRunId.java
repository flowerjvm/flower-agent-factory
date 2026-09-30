package io.github.flowerjvm.factory.contracts.ids;

/** Identity of one worker execution attempt. */
public record WorkerRunId(String value) {
    public WorkerRunId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("workerRunId must not be blank");
        }
    }
}
