package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;

/** Host-owned exact allowlist classifier for tokenless Worker completion payloads. */
@FunctionalInterface
public interface WorkerCompletionClassifier {
    WorkerCompletionClassification classify(CodingWorkerCompletionPayload payload);
}
