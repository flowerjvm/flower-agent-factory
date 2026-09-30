package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.util.Objects;

/** Result of a cooperative cancellation request. */
public record WorkerCancelResult(WorkerRunId workerRunId, WorkerRunStatus status, String stableCode) {
    public WorkerCancelResult {
        Objects.requireNonNull(workerRunId, "workerRunId");
        Objects.requireNonNull(status, "status");
        if (stableCode == null || stableCode.isBlank()) {
            throw new IllegalArgumentException("stableCode must not be blank");
        }
    }
}
