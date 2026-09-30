package io.github.flowerjvm.factory.application.outbox;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable outbound intent identified by a deterministic external operation id. */
public record DispatchOutbox(
        DispatchOutboxId outboxId,
        TenantId tenantId,
        String operationType,
        String aggregateType,
        String aggregateId,
        String operationId,
        ArtifactReference payloadArtifactRef,
        DispatchOutboxStatus status,
        Optional<String> claimToken,
        Optional<DispatchClaimPurpose> claimPurpose,
        Optional<Instant> claimedAt,
        Optional<Instant> leaseUntil,
        Instant availableAt,
        int attemptCount,
        Optional<String> lastCode,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public DispatchOutbox {
        Objects.requireNonNull(outboxId, "outboxId");
        Objects.requireNonNull(tenantId, "tenantId");
        operationType = requireText(operationType, "operationType");
        aggregateType = requireText(aggregateType, "aggregateType");
        aggregateId = requireText(aggregateId, "aggregateId");
        operationId = requireText(operationId, "operationId");
        Objects.requireNonNull(payloadArtifactRef, "payloadArtifactRef");
        Objects.requireNonNull(status, "status");
        claimToken = requireOptionalText(claimToken, "claimToken");
        claimPurpose = Objects.requireNonNull(claimPurpose, "claimPurpose");
        claimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
        leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(availableAt, "availableAt");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
        lastCode = requireOptionalText(lastCode, "lastCode");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        validateLifecycle(
                operationType, status, claimToken, claimPurpose, claimedAt, leaseUntil, attemptCount, lastCode);
    }

    /** Compatibility constructor for an unclaimed row created by the Action dispatch transaction. */
    public DispatchOutbox(
            DispatchOutboxId outboxId,
            TenantId tenantId,
            String operationType,
            String aggregateType,
            String aggregateId,
            String operationId,
            ArtifactReference payloadArtifactRef,
            DispatchOutboxStatus status,
            Instant availableAt,
            int attemptCount,
            Optional<String> lastCode,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this(
                outboxId, tenantId, operationType, aggregateType, aggregateId, operationId,
                payloadArtifactRef, status, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), availableAt, attemptCount, lastCode, version, createdAt, updatedAt);
    }

    public DispatchOutbox claimForSubmission(String token, Instant now, Duration lease) {
        requireClaimArguments(token, now, lease);
        if ((status != DispatchOutboxStatus.PENDING && status != DispatchOutboxStatus.RETRY_WAIT)
                || now.isBefore(availableAt)) {
            throw new IllegalStateException("outbox is not available for a fresh submission claim");
        }
        return claimed(token, DispatchClaimPurpose.SUBMIT, now, lease, attemptCount + 1);
    }

    /** Expiry grants status reconciliation only; it never grants another external submit. */
    public DispatchOutbox claimForReconciliation(String token, Instant now, Duration lease) {
        requireClaimArguments(token, now, lease);
        if (status != DispatchOutboxStatus.DISPATCHING
                || leaseUntil.filter(value -> !now.isBefore(value)).isEmpty()) {
            throw new IllegalStateException("only an expired uncertain dispatch can be reconciled");
        }
        return claimed(token, DispatchClaimPurpose.RECONCILE, now, lease, attemptCount);
    }

    public DispatchOutbox dispatched(String token, String code, Instant at) {
        requireCurrentClaim(token);
        if (!WorkerOutboxOperations.DISPATCH.equals(operationType)) {
            throw new IllegalStateException("DISPATCHED is valid only for a Worker dispatch operation");
        }
        return terminal(DispatchOutboxStatus.DISPATCHED, code, at);
    }

    public DispatchOutbox confirmed(String token, String code, Instant at) {
        requireCurrentClaim(token);
        if (!WorkerOutboxOperations.CANCEL.equals(operationType)) {
            throw new IllegalStateException("CONFIRMED is valid only for a Worker cancel operation");
        }
        return terminal(DispatchOutboxStatus.CONFIRMED, code, at);
    }

    public DispatchOutbox superseded(String token, String code, Instant at) {
        requireCurrentClaim(token);
        if (!WorkerOutboxOperations.CANCEL.equals(operationType)) {
            throw new IllegalStateException("SUPERSEDED is valid only for a Worker cancel operation");
        }
        return terminal(DispatchOutboxStatus.SUPERSEDED, code, at);
    }

    /** A retry is legal only after the adapter authoritatively proves that no effect exists. */
    public DispatchOutbox retryAfterProvenNoEffect(
            String token, String code, Instant observedAt, Instant retryAt) {
        requireCurrentClaim(token);
        code = requireText(code, "code");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(retryAt, "retryAt");
        if (retryAt.isBefore(observedAt)) {
            throw new IllegalArgumentException("retryAt must not precede observedAt");
        }
        return copy(
                DispatchOutboxStatus.RETRY_WAIT,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), retryAt,
                Optional.of(code), observedAt);
    }

    public DispatchOutbox manualReview(String token, String code, Instant at) {
        requireCurrentClaim(token);
        return terminal(DispatchOutboxStatus.MANUAL_REVIEW, code, at);
    }

    /**
     * Closes a fresh dispatch whose external-effect start was proven never to have become eligible.
     */
    public DispatchOutbox manualReviewBeforeSubmit(String code, Instant at) {
        if (!WorkerOutboxOperations.DISPATCH.equals(operationType)
                || status != DispatchOutboxStatus.PENDING
                || claimToken.isPresent()) {
            throw new IllegalStateException(
                    "only an unclaimed fresh Worker dispatch can be reviewed before submit");
        }
        return terminal(DispatchOutboxStatus.MANUAL_REVIEW, code, at);
    }

    /** Records a durable reconciliation fact while retaining the current owner lease. */
    public DispatchOutbox recordClaimObservation(String token, String code, Instant at) {
        requireCurrentClaim(token);
        code = requireText(code, "code");
        Objects.requireNonNull(at, "at");
        return copy(
                DispatchOutboxStatus.DISPATCHING,
                claimToken,
                claimPurpose,
                claimedAt,
                leaseUntil,
                availableAt,
                Optional.of(code),
                at);
    }

    private DispatchOutbox claimed(
            String token,
            DispatchClaimPurpose purpose,
            Instant now,
            Duration lease,
            int nextAttemptCount) {
        return copy(
                DispatchOutboxStatus.DISPATCHING,
                Optional.of(token), Optional.of(purpose), Optional.of(now), Optional.of(now.plus(lease)),
                availableAt, lastCode, now, nextAttemptCount);
    }

    private DispatchOutbox terminal(DispatchOutboxStatus nextStatus, String code, Instant at) {
        code = requireText(code, "code");
        Objects.requireNonNull(at, "at");
        return copy(
                nextStatus,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), availableAt,
                Optional.of(code), at);
    }

    private DispatchOutbox copy(
            DispatchOutboxStatus nextStatus,
            Optional<String> nextClaimToken,
            Optional<DispatchClaimPurpose> nextClaimPurpose,
            Optional<Instant> nextClaimedAt,
            Optional<Instant> nextLeaseUntil,
            Instant nextAvailableAt,
            Optional<String> nextLastCode,
            Instant nextUpdatedAt) {
        return copy(
                nextStatus, nextClaimToken, nextClaimPurpose, nextClaimedAt, nextLeaseUntil,
                nextAvailableAt, nextLastCode, nextUpdatedAt, attemptCount);
    }

    private DispatchOutbox copy(
            DispatchOutboxStatus nextStatus,
            Optional<String> nextClaimToken,
            Optional<DispatchClaimPurpose> nextClaimPurpose,
            Optional<Instant> nextClaimedAt,
            Optional<Instant> nextLeaseUntil,
            Instant nextAvailableAt,
            Optional<String> nextLastCode,
            Instant nextUpdatedAt,
            int nextAttemptCount) {
        if (nextUpdatedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("outbox time must be monotonic");
        }
        return new DispatchOutbox(
                outboxId, tenantId, operationType, aggregateType, aggregateId, operationId,
                payloadArtifactRef, nextStatus, nextClaimToken, nextClaimPurpose, nextClaimedAt,
                nextLeaseUntil, nextAvailableAt, nextAttemptCount, nextLastCode, version + 1,
                createdAt, nextUpdatedAt);
    }

    private void requireCurrentClaim(String token) {
        token = requireText(token, "claimToken");
        if (status != DispatchOutboxStatus.DISPATCHING || claimToken.filter(token::equals).isEmpty()) {
            throw new IllegalStateException("only the current claim owner may transition an outbox row");
        }
    }

    private static void requireClaimArguments(String token, Instant now, Duration lease) {
        requireText(token, "claimToken");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
    }

    private static void validateLifecycle(
            String operationType,
            DispatchOutboxStatus status,
            Optional<String> claimToken,
            Optional<DispatchClaimPurpose> claimPurpose,
            Optional<Instant> claimedAt,
            Optional<Instant> leaseUntil,
            int attemptCount,
            Optional<String> lastCode) {
        boolean fullClaim = claimToken.isPresent()
                && claimPurpose.isPresent()
                && claimedAt.isPresent()
                && leaseUntil.isPresent();
        boolean noClaim = claimToken.isEmpty()
                && claimPurpose.isEmpty()
                && claimedAt.isEmpty()
                && leaseUntil.isEmpty();
        if (status == DispatchOutboxStatus.DISPATCHING) {
            if (!fullClaim || attemptCount < 1 || leaseUntil.orElseThrow().isBefore(claimedAt.orElseThrow())) {
                throw new IllegalArgumentException("DISPATCHING outbox requires one complete owner claim");
            }
        } else if (!noClaim) {
            throw new IllegalArgumentException("only DISPATCHING outbox may retain claim metadata");
        }
        if (status == DispatchOutboxStatus.PENDING && (attemptCount != 0 || lastCode.isPresent())) {
            throw new IllegalArgumentException("PENDING outbox must not contain delivery outcome state");
        }
        if ((status == DispatchOutboxStatus.RETRY_WAIT || status.isTerminal()) && lastCode.isEmpty()) {
            throw new IllegalArgumentException("retry/terminal outbox requires a stable code");
        }
        if (status == DispatchOutboxStatus.DISPATCHED
                && !WorkerOutboxOperations.DISPATCH.equals(operationType)) {
            throw new IllegalArgumentException("DISPATCHED is reserved for WORKER_DISPATCH");
        }
        if ((status == DispatchOutboxStatus.CONFIRMED || status == DispatchOutboxStatus.SUPERSEDED)
                && !WorkerOutboxOperations.CANCEL.equals(operationType)) {
            throw new IllegalArgumentException("cancel terminal state requires WORKER_CANCEL");
        }
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
