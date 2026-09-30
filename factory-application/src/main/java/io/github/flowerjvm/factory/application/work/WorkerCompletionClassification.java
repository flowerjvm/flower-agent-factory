package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.util.Objects;

/** Host-owned terminal projection; provider text never decides retry safety. */
public record WorkerCompletionClassification(
        WorkerRunStatus terminalStatus,
        String code,
        String message,
        WorkerRetryDisposition retryDisposition) {

    public WorkerCompletionClassification {
        if (terminalStatus != WorkerRunStatus.SUCCEEDED && terminalStatus != WorkerRunStatus.FAILED) {
            throw new IllegalArgumentException("classification supports only success or failure");
        }
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a stable uppercase code");
        }
        if (message == null || message.isBlank() || message.length() > 512
                || message.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("message must be bounded non-control text");
        }
        Objects.requireNonNull(retryDisposition, "retryDisposition");
    }
}
