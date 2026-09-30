package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.util.Objects;
import java.util.Set;

/** Default exact allowlist. Unknown/provider/internal codes always require manual review. */
public final class ConservativeWorkerCompletionClassifier implements WorkerCompletionClassifier {
    private static final Set<String> CORRECTABLE = Set.of(
            "WORKER_CANDIDATE_DEFECT",
            "WORKER_PATCH_CONFLICT",
            "WORKER_OUTPUT_SCHEMA_INVALID");

    @Override
    public WorkerCompletionClassification classify(CodingWorkerCompletionPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.terminalStatus() == WorkerRunStatus.SUCCEEDED) {
            return new WorkerCompletionClassification(
                    WorkerRunStatus.SUCCEEDED,
                    "WORKER_COMPLETED",
                    "Coding Worker produced a validated result",
                    WorkerRetryDisposition.NEVER);
        }
        if (CORRECTABLE.contains(payload.workerCode())) {
            return new WorkerCompletionClassification(
                    WorkerRunStatus.FAILED,
                    "WORKER_RESULT_REQUIRES_CORRECTION",
                    "Coding Worker reported a known correctable candidate failure",
                    WorkerRetryDisposition.AFTER_CORRECTION);
        }
        return new WorkerCompletionClassification(
                WorkerRunStatus.FAILED,
                "WORKER_FAILURE_REQUIRES_REVIEW",
                "Coding Worker failure was not proven safe for automatic retry",
                WorkerRetryDisposition.MANUAL_REVIEW);
    }
}
