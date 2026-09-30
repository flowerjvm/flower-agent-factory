package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Tokenless, tenant-bound callback staging row with owner-aware processing claims. */
public record WorkerCallbackInboxEntry(
        String callbackId,
        TenantId tenantId,
        String workerBindingId,
        String eventId,
        WorkOrderId workOrderId,
        WorkerRunId workerRunId,
        String operationId,
        ContentHash attemptTokenHash,
        ArtifactReference payloadArtifactRef,
        ContentHash payloadHash,
        WorkerCallbackInboxStatus status,
        Instant availableAt,
        Optional<String> claimToken,
        Optional<Instant> leaseUntil,
        int deliveryCount,
        Optional<String> lastCode,
        long version,
        Instant receivedAt,
        Instant updatedAt) {

    public WorkerCallbackInboxEntry {
        callbackId = requireText(callbackId, "callbackId", 128);
        Objects.requireNonNull(tenantId, "tenantId");
        workerBindingId = requireText(workerBindingId, "workerBindingId", 128);
        eventId = requireText(eventId, "eventId", 128);
        Objects.requireNonNull(workOrderId, "workOrderId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        operationId = requireText(operationId, "operationId", 256);
        Objects.requireNonNull(attemptTokenHash, "attemptTokenHash");
        Objects.requireNonNull(payloadArtifactRef, "payloadArtifactRef");
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(availableAt, "availableAt");
        claimToken = optionalText(claimToken, "claimToken", 128);
        leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (deliveryCount < 1 || version < 0) {
            throw new IllegalArgumentException("deliveryCount must be positive and version non-negative");
        }
        lastCode = optionalText(lastCode, "lastCode", 128);
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(receivedAt)) {
            throw new IllegalArgumentException("callback inbox time must be monotonic");
        }
        if (status == WorkerCallbackInboxStatus.PROCESSING) {
            if (claimToken.isEmpty() || leaseUntil.isEmpty()) {
                throw new IllegalArgumentException("PROCESSING callback requires an owner lease");
            }
        } else if (claimToken.isPresent() || leaseUntil.isPresent()) {
            throw new IllegalArgumentException("only PROCESSING callback may retain a claim");
        }
        if (status.isTerminal() && lastCode.isEmpty()) {
            throw new IllegalArgumentException("terminal callback inbox entry requires a stable code");
        }
    }

    public static WorkerCallbackInboxEntry received(
            String callbackId,
            TenantId tenantId,
            String workerBindingId,
            String eventId,
            WorkOrderId workOrderId,
            WorkerRunId workerRunId,
            String operationId,
            ContentHash attemptTokenHash,
            ArtifactReference payloadArtifactRef,
            ContentHash payloadHash,
            Instant receivedAt) {
        return new WorkerCallbackInboxEntry(
                callbackId, tenantId, workerBindingId, eventId, workOrderId, workerRunId,
                operationId, attemptTokenHash, payloadArtifactRef, payloadHash,
                WorkerCallbackInboxStatus.RECEIVED, receivedAt, Optional.empty(), Optional.empty(),
                1, Optional.empty(), 0, receivedAt, receivedAt);
    }

    public WorkerCallbackInboxEntry claim(String token, Instant now, Duration lease) {
        token = requireText(token, "claimToken", 128);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        boolean claimable = status == WorkerCallbackInboxStatus.RECEIVED && !now.isBefore(availableAt);
        boolean expired = status == WorkerCallbackInboxStatus.PROCESSING
                && leaseUntil.filter(value -> !now.isBefore(value)).isPresent();
        if (!claimable && !expired) {
            throw new IllegalStateException("callback is not claimable");
        }
        return copy(
                WorkerCallbackInboxStatus.PROCESSING,
                availableAt,
                Optional.of(token),
                Optional.of(now.plus(lease)),
                lastCode,
                now);
    }

    public WorkerCallbackInboxEntry defer(String token, String code, Instant now, Instant retryAt) {
        requireOwner(token);
        code = stableCode(code);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(retryAt, "retryAt");
        if (retryAt.isBefore(now)) {
            throw new IllegalArgumentException("retryAt must not precede now");
        }
        return copy(
                WorkerCallbackInboxStatus.RECEIVED, retryAt, Optional.empty(), Optional.empty(),
                Optional.of(code), now);
    }

    public WorkerCallbackInboxEntry applied(String token, String code, Instant at) {
        return terminal(token, WorkerCallbackInboxStatus.APPLIED, code, at);
    }

    public WorkerCallbackInboxEntry superseded(String token, String code, Instant at) {
        return terminal(token, WorkerCallbackInboxStatus.SUPERSEDED, code, at);
    }

    public WorkerCallbackInboxEntry rejected(String token, String code, Instant at) {
        return terminal(token, WorkerCallbackInboxStatus.REJECTED, code, at);
    }

    public WorkerCallbackInboxEntry manualReview(String token, String code, Instant at) {
        return terminal(token, WorkerCallbackInboxStatus.MANUAL_REVIEW, code, at);
    }

    /** Reopens only the recoverable late-acceptance boundary after exact dispatch proof lands. */
    public WorkerCallbackInboxEntry recoverAcceptedDispatch(Instant at) {
        Objects.requireNonNull(at, "at");
        if (status != WorkerCallbackInboxStatus.MANUAL_REVIEW
                || lastCode.filter(WorkerCallbackProcessor.ACCEPTANCE_ORPHANED::equals).isEmpty()) {
            throw new IllegalStateException("callback is not an acceptance-recovery orphan");
        }
        return copy(
                WorkerCallbackInboxStatus.RECEIVED,
                at,
                Optional.empty(),
                Optional.empty(),
                Optional.of(WorkerCallbackProcessor.AWAITING_ACCEPTANCE),
                at);
    }

    private WorkerCallbackInboxEntry terminal(
            String token, WorkerCallbackInboxStatus next, String code, Instant at) {
        requireOwner(token);
        return copy(
                next, availableAt, Optional.empty(), Optional.empty(), Optional.of(stableCode(code)), at);
    }

    private void requireOwner(String token) {
        token = requireText(token, "claimToken", 128);
        if (status != WorkerCallbackInboxStatus.PROCESSING || claimToken.filter(token::equals).isEmpty()) {
            throw new IllegalStateException("only the current callback claim owner may transition it");
        }
    }

    private WorkerCallbackInboxEntry copy(
            WorkerCallbackInboxStatus nextStatus,
            Instant nextAvailableAt,
            Optional<String> nextClaimToken,
            Optional<Instant> nextLeaseUntil,
            Optional<String> nextLastCode,
            Instant nextUpdatedAt) {
        if (nextUpdatedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("callback inbox time must be monotonic");
        }
        return new WorkerCallbackInboxEntry(
                callbackId, tenantId, workerBindingId, eventId, workOrderId, workerRunId,
                operationId, attemptTokenHash, payloadArtifactRef, payloadHash, nextStatus,
                nextAvailableAt, nextClaimToken, nextLeaseUntil, deliveryCount, nextLastCode,
                version + 1, receivedAt, nextUpdatedAt);
    }

    private static Optional<String> optionalText(Optional<String> value, String name, int max) {
        Objects.requireNonNull(value, name);
        value.ifPresent(text -> requireText(text, name, max));
        return value;
    }

    private static String stableCode(String value) {
        String code = requireText(value, "code", 128);
        if (!code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a stable uppercase code");
        }
        return code;
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }
}
