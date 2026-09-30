package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable snapshot of one Action-owned, durable independent-verifier dispatch intent. */
public record VerificationDispatchIntent(
        String operationId,
        TenantId tenantId,
        VerificationRunId verificationRunId,
        CandidateId candidateId,
        long expectedVerificationRunVersion,
        String actionRunId,
        String attemptTokenHash,
        Instant deadlineAt,
        VerificationDispatchIntentStatus status,
        Optional<String> claimToken,
        Optional<Instant> leaseUntil,
        int attemptCount,
        Optional<String> lastCode,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public VerificationDispatchIntent {
        operationId = requireText(operationId, "operationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(verificationRunId, "verificationRunId");
        Objects.requireNonNull(candidateId, "candidateId");
        if (expectedVerificationRunVersion < 0) {
            throw new IllegalArgumentException("expectedVerificationRunVersion must not be negative");
        }
        actionRunId = requireText(actionRunId, "actionRunId");
        attemptTokenHash = requireHash(attemptTokenHash, "attemptTokenHash");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(status, "status");
        claimToken = requireOptionalText(claimToken, "claimToken");
        leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (attemptCount < 0 || version < 0) {
            throw new IllegalArgumentException("attemptCount and version must not be negative");
        }
        lastCode = requireOptionalText(lastCode, "lastCode");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt) || deadlineAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("intent timestamps are inconsistent");
        }
        validateLifecycle(status, claimToken, leaseUntil, attemptCount, lastCode);
    }

    public static VerificationDispatchIntent pending(
            String operationId,
            TenantId tenantId,
            VerificationRunId verificationRunId,
            CandidateId candidateId,
            long expectedVerificationRunVersion,
            String actionRunId,
            String attemptTokenHash,
            Instant deadlineAt,
            Instant createdAt) {
        return new VerificationDispatchIntent(
                operationId,
                tenantId,
                verificationRunId,
                candidateId,
                expectedVerificationRunVersion,
                actionRunId,
                attemptTokenHash,
                deadlineAt,
                VerificationDispatchIntentStatus.PENDING,
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.empty(),
                0,
                createdAt,
                createdAt);
    }

    public VerificationDispatchIntent claim(String token, Instant claimedAt, Duration lease) {
        token = requireText(token, "claimToken");
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(lease, "lease");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        boolean claimable = status == VerificationDispatchIntentStatus.PENDING
                || (status == VerificationDispatchIntentStatus.UNCERTAIN
                        && leaseUntil.filter(value -> !claimedAt.isBefore(value)).isPresent())
                || (status == VerificationDispatchIntentStatus.RUNNING
                        && leaseUntil.filter(value -> !claimedAt.isBefore(value)).isPresent());
        if (!claimable) {
            throw new IllegalStateException("verification dispatch intent is not claimable");
        }
        return new VerificationDispatchIntent(
                operationId, tenantId, verificationRunId, candidateId, expectedVerificationRunVersion,
                actionRunId, attemptTokenHash, deadlineAt, VerificationDispatchIntentStatus.RUNNING,
                Optional.of(token), Optional.of(claimedAt.plus(lease)), attemptCount + 1,
                lastCode, version + 1, createdAt, claimedAt);
    }

    public VerificationDispatchIntent complete(String token, String code, Instant completedAt) {
        return terminal(token, code, completedAt, VerificationDispatchIntentStatus.COMPLETED);
    }

    /** Defers classification of the normal RUNNING-to-WAITING park window without executing a side effect. */
    public VerificationDispatchIntent uncertain(
            String token, String code, Instant observedAt, Instant retryAt) {
        token = requireText(token, "claimToken");
        code = requireText(code, "code");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(retryAt, "retryAt");
        if (status != VerificationDispatchIntentStatus.RUNNING
                || claimToken.filter(token::equals).isEmpty()
                || retryAt.isBefore(observedAt)) {
            throw new IllegalStateException("only the current claim owner may defer an uncertain intent");
        }
        return new VerificationDispatchIntent(
                operationId, tenantId, verificationRunId, candidateId, expectedVerificationRunVersion,
                actionRunId, attemptTokenHash, deadlineAt, VerificationDispatchIntentStatus.UNCERTAIN,
                Optional.empty(), Optional.of(retryAt), attemptCount, Optional.of(code),
                version + 1, createdAt, observedAt);
    }

    public VerificationDispatchIntent orphan(String token, String code, Instant orphanedAt) {
        return terminal(token, code, orphanedAt, VerificationDispatchIntentStatus.ORPHANED);
    }

    private VerificationDispatchIntent terminal(
            String token,
            String code,
            Instant terminalAt,
            VerificationDispatchIntentStatus terminalStatus) {
        token = requireText(token, "claimToken");
        code = requireText(code, "code");
        Objects.requireNonNull(terminalAt, "terminalAt");
        if (status != VerificationDispatchIntentStatus.RUNNING
                || claimToken.filter(token::equals).isEmpty()) {
            throw new IllegalStateException("only the current claim owner may terminalize the intent");
        }
        return new VerificationDispatchIntent(
                operationId, tenantId, verificationRunId, candidateId, expectedVerificationRunVersion,
                actionRunId, attemptTokenHash, deadlineAt, terminalStatus, Optional.empty(), Optional.empty(),
                attemptCount, Optional.of(code), version + 1, createdAt, terminalAt);
    }

    public boolean sameImmutableIdentity(VerificationDispatchIntent other) {
        return other != null
                && operationId.equals(other.operationId)
                && tenantId.equals(other.tenantId)
                && verificationRunId.equals(other.verificationRunId)
                && candidateId.equals(other.candidateId)
                && expectedVerificationRunVersion == other.expectedVerificationRunVersion
                && actionRunId.equals(other.actionRunId)
                && attemptTokenHash.equals(other.attemptTokenHash)
                && deadlineAt.equals(other.deadlineAt);
    }

    private static void validateLifecycle(
            VerificationDispatchIntentStatus status,
            Optional<String> claimToken,
            Optional<Instant> leaseUntil,
            int attemptCount,
            Optional<String> lastCode) {
        switch (status) {
            case PENDING -> {
                if (claimToken.isPresent() || leaseUntil.isPresent() || attemptCount != 0 || lastCode.isPresent()) {
                    throw new IllegalArgumentException("PENDING intent must not contain claim or result state");
                }
            }
            case RUNNING -> {
                if (claimToken.isEmpty() || leaseUntil.isEmpty() || attemptCount < 1) {
                    throw new IllegalArgumentException("RUNNING intent requires a lease-bearing claim");
                }
            }
            case UNCERTAIN -> {
                if (claimToken.isPresent() || leaseUntil.isEmpty() || attemptCount < 1 || lastCode.isEmpty()) {
                    throw new IllegalArgumentException("UNCERTAIN intent requires a retry time and stable code");
                }
            }
            case COMPLETED, ORPHANED -> {
                if (claimToken.isPresent() || leaseUntil.isPresent() || attemptCount < 1 || lastCode.isEmpty()) {
                    throw new IllegalArgumentException("terminal intent requires a code and no active claim");
                }
            }
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
        return value.trim();
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name).toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex value");
        }
        return normalized;
    }
}
