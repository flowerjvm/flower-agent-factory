package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Authenticated, operation-bound result delivered by the PR3 fake worker test driver. */
public record WorkerCompletion(
        TenantId tenantId,
        WorkerRunId workerRunId,
        String operationId,
        String attemptToken,
        WorkerRunStatus terminalStatus,
        Optional<ArtifactReference> resultArtifactManifestRef,
        Optional<ContentHash> resultHash,
        String code,
        String message,
        WorkerRetryDisposition retryDisposition,
        Instant completedAt) {

    public WorkerCompletion {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        operationId = requireText(operationId, "operationId");
        attemptToken = requireText(attemptToken, "attemptToken");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (terminalStatus != WorkerRunStatus.SUCCEEDED
                && terminalStatus != WorkerRunStatus.FAILED) {
            throw new IllegalArgumentException(
                    "PR3 worker completion supports only SUCCEEDED or FAILED; cancellation is reconciled separately");
        }
        resultArtifactManifestRef = Objects.requireNonNull(resultArtifactManifestRef, "resultArtifactManifestRef");
        resultHash = Objects.requireNonNull(resultHash, "resultHash");
        if (resultArtifactManifestRef.isPresent() != resultHash.isPresent()) {
            throw new IllegalArgumentException("result artifact manifest and hash must be present together");
        }
        code = requireText(code, "code");
        message = requireText(message, "message");
        Objects.requireNonNull(retryDisposition, "retryDisposition");
        Objects.requireNonNull(completedAt, "completedAt");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
