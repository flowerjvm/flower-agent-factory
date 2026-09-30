package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.util.Objects;
import java.util.Optional;

/** Acknowledges dispatch of an existing durable WorkerRun; it does not mean the work completed. */
public record WorkerSubmission(
        WorkerRunId workerRunId,
        String operationId,
        WorkerRunStatus status,
        Optional<String> externalSessionRef) {
    public WorkerSubmission {
        Objects.requireNonNull(workerRunId, "workerRunId");
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        externalSessionRef = Objects.requireNonNull(externalSessionRef, "externalSessionRef");
        externalSessionRef.ifPresent(value -> {
            if (value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("externalSessionRef must be bounded opaque text");
            }
        });
    }

    public WorkerSubmission(WorkerRunId workerRunId, String operationId, WorkerRunStatus status) {
        this(workerRunId, operationId, status, Optional.empty());
    }
}
