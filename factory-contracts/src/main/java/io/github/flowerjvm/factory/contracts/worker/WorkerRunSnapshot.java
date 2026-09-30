package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistable observation returned by a coding-worker adapter.
 *
 * <p>{@code effectAcceptedAt} and {@code effectTerminalAt} are present only when the trusted host
 * adapter has promoted the original effect-acceptance and terminal-commit times from an
 * authenticated, durable operation journal. Neither value is copied from a provider completion
 * timestamp or an unauthenticated callback body. {@code effectTerminalAt} records when the trusted
 * journal durably committed the terminal envelope, not when a provider claims to have produced it.
 */
public record WorkerRunSnapshot(
        TenantId tenantId,
        WorkOrderId workOrderId,
        WorkerRunId workerRunId,
        String operationId,
        WorkerRunStatus status,
        Optional<Instant> effectAcceptedAt,
        Optional<Instant> effectTerminalAt,
        Optional<String> externalSessionRef,
        Optional<ArtifactReference> resultArtifact,
        Optional<String> stableCode) {

    public WorkerRunSnapshot {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workOrderId, "workOrderId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        effectAcceptedAt = Objects.requireNonNull(effectAcceptedAt, "effectAcceptedAt");
        effectTerminalAt = Objects.requireNonNull(effectTerminalAt, "effectTerminalAt");
        externalSessionRef = Objects.requireNonNull(externalSessionRef, "externalSessionRef");
        resultArtifact = Objects.requireNonNull(resultArtifact, "resultArtifact");
        stableCode = Objects.requireNonNull(stableCode, "stableCode");
        if (effectTerminalAt.isPresent() && !status.isTerminal()) {
            throw new IllegalArgumentException("only a terminal snapshot may prove effectTerminalAt");
        }
        if (effectAcceptedAt.isPresent() && effectTerminalAt.isPresent()
                && effectTerminalAt.orElseThrow().isBefore(effectAcceptedAt.orElseThrow())) {
            throw new IllegalArgumentException("effectTerminalAt must not precede effectAcceptedAt");
        }
    }

    /** Compatibility constructor for adapters that cannot yet prove a terminal journal time. */
    public WorkerRunSnapshot(
            TenantId tenantId,
            WorkOrderId workOrderId,
            WorkerRunId workerRunId,
            String operationId,
            WorkerRunStatus status,
            Optional<Instant> effectAcceptedAt,
            Optional<String> externalSessionRef,
            Optional<ArtifactReference> resultArtifact,
            Optional<String> stableCode) {
        this(
                tenantId, workOrderId, workerRunId, operationId, status, effectAcceptedAt,
                Optional.empty(), externalSessionRef, resultArtifact, stableCode);
    }

    /** Compatibility constructor for adapters that cannot prove an original acceptance time. */
    public WorkerRunSnapshot(
            TenantId tenantId,
            WorkOrderId workOrderId,
            WorkerRunId workerRunId,
            String operationId,
            WorkerRunStatus status,
            Optional<String> externalSessionRef,
            Optional<ArtifactReference> resultArtifact,
            Optional<String> stableCode) {
        this(
                tenantId, workOrderId, workerRunId, operationId, status, Optional.empty(),
                Optional.empty(), externalSessionRef, resultArtifact, stableCode);
    }

    public WorkerRunSnapshot(
            TenantId tenantId,
            WorkOrderId workOrderId,
            WorkerRunId workerRunId,
            String operationId,
            WorkerRunStatus status,
            Optional<ArtifactReference> resultArtifact,
            Optional<String> stableCode) {
        this(
                tenantId, workOrderId, workerRunId, operationId, status, Optional.empty(),
                Optional.empty(), Optional.empty(), resultArtifact, stableCode);
    }
}
