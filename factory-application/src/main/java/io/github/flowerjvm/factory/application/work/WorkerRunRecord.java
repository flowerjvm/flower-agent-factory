package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable snapshot of one WorkOrder execution attempt, transitioned only through version CAS. */
public record WorkerRunRecord(
        WorkerRunId workerRunId,
        TenantId tenantId,
        BuildSessionId buildSessionId,
        WorkOrderId workOrderId,
        int attemptNo,
        String workerBindingId,
        String workerAdapterVersion,
        WorkerCapabilities workerCapabilitySnapshot,
        WorkerRunStatus status,
        Optional<String> actionRunId,
        String operationId,
        Optional<ContentHash> attemptTokenHash,
        Optional<String> externalSessionRef,
        Optional<DispatchOutboxId> dispatchOutboxId,
        Optional<Instant> startedAt,
        Instant deadlineAt,
        Optional<Instant> heartbeatAt,
        Optional<Instant> cancelRequestedAt,
        Optional<Instant> completedAt,
        Optional<ArtifactReference> resultArtifactManifestRef,
        Optional<ContentHash> resultHash,
        Optional<String> code,
        Optional<String> message,
        Optional<WorkerRetryDisposition> retryDisposition,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public WorkerRunRecord {
        Objects.requireNonNull(workerRunId, "workerRunId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(workOrderId, "workOrderId");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be at least one");
        }
        workerBindingId = requireText(workerBindingId, "workerBindingId");
        workerAdapterVersion = requireText(workerAdapterVersion, "workerAdapterVersion");
        Objects.requireNonNull(workerCapabilitySnapshot, "workerCapabilitySnapshot");
        Objects.requireNonNull(status, "status");
        actionRunId = requireOptionalText(actionRunId, "actionRunId");
        if (status == WorkerRunStatus.REQUESTED && actionRunId.isPresent()) {
            throw new IllegalArgumentException("REQUESTED WorkerRun must not be pre-bound to an ActionRun");
        }
        operationId = requireText(operationId, "operationId");
        attemptTokenHash = Objects.requireNonNull(attemptTokenHash, "attemptTokenHash");
        externalSessionRef = requireOptionalText(externalSessionRef, "externalSessionRef");
        dispatchOutboxId = Objects.requireNonNull(dispatchOutboxId, "dispatchOutboxId");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        heartbeatAt = Objects.requireNonNull(heartbeatAt, "heartbeatAt");
        cancelRequestedAt = Objects.requireNonNull(cancelRequestedAt, "cancelRequestedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        resultArtifactManifestRef = Objects.requireNonNull(
                resultArtifactManifestRef, "resultArtifactManifestRef");
        resultHash = Objects.requireNonNull(resultHash, "resultHash");
        if (resultArtifactManifestRef.isPresent() != resultHash.isPresent()) {
            throw new IllegalArgumentException("result artifact manifest and hash must be present together");
        }
        code = requireOptionalText(code, "code");
        message = requireOptionalText(message, "message");
        retryDisposition = Objects.requireNonNull(retryDisposition, "retryDisposition");
        if (status == WorkerRunStatus.REQUESTED
                && (attemptTokenHash.isPresent() || dispatchOutboxId.isPresent() || startedAt.isPresent())) {
            throw new IllegalArgumentException("REQUESTED WorkerRun must not contain dispatch ownership fields");
        }
        boolean noDispatchOwnership = actionRunId.isEmpty()
                && attemptTokenHash.isEmpty()
                && dispatchOutboxId.isEmpty()
                && startedAt.isEmpty();
        boolean fullDispatchOwnership = actionRunId.isPresent()
                && attemptTokenHash.isPresent()
                && dispatchOutboxId.isPresent()
                && startedAt.isPresent();
        boolean cancelMayPrecedeDispatch = status == WorkerRunStatus.CANCEL_REQUESTED
                || status == WorkerRunStatus.CANCELLED;
        if (status != WorkerRunStatus.REQUESTED
                && !(cancelMayPrecedeDispatch && noDispatchOwnership)
                && !fullDispatchOwnership) {
            throw new IllegalArgumentException(
                    "post-dispatch WorkerRun must contain all dispatch ownership fields");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (deadlineAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("deadlineAt must not be before createdAt");
        }
        if (status.isTerminal() && completedAt.isEmpty()) {
            throw new IllegalArgumentException("terminal WorkerRun must have completedAt");
        }
        if (!status.isTerminal() && completedAt.isPresent()) {
            throw new IllegalArgumentException("non-terminal WorkerRun must not have completedAt");
        }
        if (status == WorkerRunStatus.SUCCEEDED && resultHash.isEmpty()) {
            throw new IllegalArgumentException("successful WorkerRun must have a result manifest and hash");
        }
        if (status.isTerminal()
                && (code.isEmpty() || message.isEmpty() || retryDisposition.isEmpty())) {
            throw new IllegalArgumentException("terminal WorkerRun must contain code, message and retry disposition");
        }
    }

    /**
     * Claims an unbound REQUESTED attempt for the ActionRun that won duplicate reservation and
     * creates the PR2 dispatch transition. Persistence still decides the domain CAS winner.
     */
    public WorkerRunRecord startDispatch(
            DispatchOutboxId outboxId,
            String ownerActionRunId,
            ContentHash ownerAttemptTokenHash,
            Instant startedAt) {
        Objects.requireNonNull(outboxId, "outboxId");
        ownerActionRunId = requireText(ownerActionRunId, "ownerActionRunId");
        Objects.requireNonNull(ownerAttemptTokenHash, "ownerAttemptTokenHash");
        Objects.requireNonNull(startedAt, "startedAt");
        if (status != WorkerRunStatus.REQUESTED) {
            throw new IllegalStateException("only a REQUESTED WorkerRun can start dispatch");
        }
        if (dispatchOutboxId.isPresent() || this.startedAt.isPresent()) {
            throw new IllegalStateException("WorkerRun dispatch was already prepared");
        }
        if (attemptTokenHash.isPresent()) {
            throw new IllegalStateException("REQUESTED WorkerRun already has an attempt token hash");
        }
        if (startedAt.isBefore(updatedAt) || !startedAt.isBefore(deadlineAt)) {
            throw new IllegalArgumentException("dispatch time must be monotonic and before the deadline");
        }
        return new WorkerRunRecord(
                workerRunId,
                tenantId,
                buildSessionId,
                workOrderId,
                attemptNo,
                workerBindingId,
                workerAdapterVersion,
                workerCapabilitySnapshot,
                WorkerRunStatus.DISPATCHING,
                Optional.of(ownerActionRunId),
                operationId,
                Optional.of(ownerAttemptTokenHash),
                externalSessionRef,
                Optional.of(outboxId),
                Optional.of(startedAt),
                deadlineAt,
                heartbeatAt,
                cancelRequestedAt,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                version + 1,
                createdAt,
                startedAt);
    }

    /** Records that the external fake/adapter accepted the already-owned durable operation. */
    public WorkerRunRecord awaitExternal(String externalSessionRef, Instant observedAt) {
        externalSessionRef = requireText(externalSessionRef, "externalSessionRef");
        Objects.requireNonNull(observedAt, "observedAt");
        if (status != WorkerRunStatus.DISPATCHING) {
            throw new IllegalStateException("only a DISPATCHING WorkerRun can await external completion");
        }
        if (observedAt.isBefore(updatedAt) || !observedAt.isBefore(deadlineAt)) {
            throw new IllegalArgumentException("external acceptance time must be monotonic and before deadline");
        }
        return copy(
                WorkerRunStatus.WAITING_EXTERNAL,
                Optional.of(externalSessionRef),
                heartbeatAt,
                cancelRequestedAt,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                observedAt);
    }

    /** Applies one authenticated terminal callback; repository CAS decides the single winner. */
    public WorkerRunRecord complete(
            WorkerRunStatus terminalStatus,
            Optional<ArtifactReference> resultArtifactManifestRef,
            Optional<ContentHash> resultHash,
            String code,
            String message,
            WorkerRetryDisposition retryDisposition,
            Instant completedAt) {
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        Objects.requireNonNull(resultArtifactManifestRef, "resultArtifactManifestRef");
        Objects.requireNonNull(resultHash, "resultHash");
        code = requireText(code, "code");
        message = requireText(message, "message");
        Objects.requireNonNull(retryDisposition, "retryDisposition");
        Objects.requireNonNull(completedAt, "completedAt");
        if (status != WorkerRunStatus.WAITING_EXTERNAL
                && status != WorkerRunStatus.CANCEL_REQUESTED
                && status != WorkerRunStatus.RECONCILING) {
            throw new IllegalStateException("WorkerRun is not accepting a terminal callback");
        }
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("completion status must be terminal");
        }
        if (terminalStatus == WorkerRunStatus.SUCCEEDED
                && (resultArtifactManifestRef.isEmpty() || resultHash.isEmpty())) {
            throw new IllegalArgumentException("successful completion requires result artifact and hash");
        }
        if (resultArtifactManifestRef.isPresent() != resultHash.isPresent()) {
            throw new IllegalArgumentException("result artifact manifest and hash must be present together");
        }
        boolean receiptMayPredateState = (status == WorkerRunStatus.WAITING_EXTERNAL
                        || status == WorkerRunStatus.CANCEL_REQUESTED
                        || status == WorkerRunStatus.RECONCILING)
                && startedAt.filter(started -> !completedAt.isBefore(started)).isPresent()
                && (status != WorkerRunStatus.CANCEL_REQUESTED
                        || cancelRequestedAt
                                .filter(cancelledAt -> !completedAt.isAfter(cancelledAt))
                                .isPresent());
        if (completedAt.isBefore(updatedAt) && !receiptMayPredateState) {
            throw new IllegalArgumentException("completion time must be monotonic");
        }
        Instant transitionAt = completedAt.isBefore(updatedAt) ? updatedAt : completedAt;
        return copy(
                terminalStatus,
                externalSessionRef,
                heartbeatAt,
                cancelRequestedAt,
                Optional.of(completedAt),
                resultArtifactManifestRef,
                resultHash,
                Optional.of(code),
                Optional.of(message),
                Optional.of(retryDisposition),
                transitionAt);
    }

    /** Persists cancellation intent before the DeferredActionExecutor cancel hook returns. */
    public WorkerRunRecord requestCancellation(Instant requestedAt) {
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (status.isTerminal() || status == WorkerRunStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("WorkerRun is not accepting a cancellation request");
        }
        if (requestedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("cancellation time must be monotonic");
        }
        return copy(
                WorkerRunStatus.CANCEL_REQUESTED,
                externalSessionRef,
                heartbeatAt,
                Optional.of(requestedAt),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                requestedAt);
    }

    /** Records confirmed remote cancellation after the ActionRun has already accepted cancellation. */
    public WorkerRunRecord confirmCancellation(String code, String message, Instant confirmedAt) {
        code = requireText(code, "code");
        message = requireText(message, "message");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (status != WorkerRunStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("only a cancellation-requested WorkerRun can be confirmed cancelled");
        }
        if (confirmedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("cancellation confirmation time must be monotonic");
        }
        return copy(
                WorkerRunStatus.CANCELLED,
                externalSessionRef,
                heartbeatAt,
                cancelRequestedAt,
                Optional.of(confirmedAt),
                Optional.empty(),
                Optional.empty(),
                Optional.of(code),
                Optional.of(message),
                Optional.of(WorkerRetryDisposition.NEVER),
                confirmedAt);
    }

    /** Conservatively terminalizes an uncertain external effect for operator review. */
    public WorkerRunRecord manualReview(String code, String message, Instant observedAt) {
        code = requireText(code, "code");
        message = requireText(message, "message");
        Objects.requireNonNull(observedAt, "observedAt");
        if (status.isTerminal()) {
            throw new IllegalStateException("a terminal WorkerRun cannot be overwritten");
        }
        if (observedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("manual-review time must be monotonic");
        }
        return copy(
                WorkerRunStatus.MANUAL_REVIEW,
                externalSessionRef,
                heartbeatAt,
                cancelRequestedAt,
                Optional.of(observedAt),
                Optional.empty(),
                Optional.empty(),
                Optional.of(code),
                Optional.of(message),
                Optional.of(WorkerRetryDisposition.MANUAL_REVIEW),
                observedAt);
    }

    /** Records a bounded status-poll observation without changing external lifecycle truth. */
    public WorkerRunRecord observeHeartbeat(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        if (status != WorkerRunStatus.WAITING_EXTERNAL
                && status != WorkerRunStatus.RECONCILING
                && status != WorkerRunStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("WorkerRun is not externally active");
        }
        if (observedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("heartbeat time must be monotonic");
        }
        return copy(
                status,
                externalSessionRef,
                Optional.of(observedAt),
                cancelRequestedAt,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                observedAt);
    }

    private WorkerRunRecord copy(
            WorkerRunStatus nextStatus,
            Optional<String> nextExternalSessionRef,
            Optional<Instant> nextHeartbeatAt,
            Optional<Instant> nextCancelRequestedAt,
            Optional<Instant> nextCompletedAt,
            Optional<ArtifactReference> nextResultArtifactManifestRef,
            Optional<ContentHash> nextResultHash,
            Optional<String> nextCode,
            Optional<String> nextMessage,
            Optional<WorkerRetryDisposition> nextRetryDisposition,
            Instant nextUpdatedAt) {
        return new WorkerRunRecord(
                workerRunId,
                tenantId,
                buildSessionId,
                workOrderId,
                attemptNo,
                workerBindingId,
                workerAdapterVersion,
                workerCapabilitySnapshot,
                nextStatus,
                actionRunId,
                operationId,
                attemptTokenHash,
                nextExternalSessionRef,
                dispatchOutboxId,
                startedAt,
                deadlineAt,
                nextHeartbeatAt,
                nextCancelRequestedAt,
                nextCompletedAt,
                nextResultArtifactManifestRef,
                nextResultHash,
                nextCode,
                nextMessage,
                nextRetryDisposition,
                version + 1,
                createdAt,
                nextUpdatedAt);
    }

    private static Optional<String> requireOptionalText(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(text -> requireText(text, name));
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
