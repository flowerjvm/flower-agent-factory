package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Immutable lease/CAS snapshot for one deferred Certification issuance. */
public record CertificationDispatchIntent(
        String operationId,
        TenantId tenantId,
        CertificationId certificationId,
        ContentHash inputLockManifestHash,
        long expectedCertificationVersion,
        String actionRunId,
        String attemptTokenHash,
        Instant deadlineAt,
        CertificationDispatchIntentStatus status,
        Optional<String> claimToken,
        Optional<Instant> leaseUntil,
        int attemptCount,
        Optional<String> lastCode,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public CertificationDispatchIntent {
        operationId = requireText(operationId, "operationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(certificationId, "certificationId");
        Objects.requireNonNull(inputLockManifestHash, "inputLockManifestHash");
        if (expectedCertificationVersion < 0) {
            throw new IllegalArgumentException("expectedCertificationVersion must not be negative");
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

    public static CertificationDispatchIntent pending(
            String operationId,
            TenantId tenantId,
            CertificationId certificationId,
            ContentHash inputLockManifestHash,
            long expectedCertificationVersion,
            String actionRunId,
            String attemptTokenHash,
            Instant deadlineAt,
            Instant createdAt) {
        return new CertificationDispatchIntent(
                operationId,
                tenantId,
                certificationId,
                inputLockManifestHash,
                expectedCertificationVersion,
                actionRunId,
                attemptTokenHash,
                deadlineAt,
                CertificationDispatchIntentStatus.PENDING,
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.empty(),
                0,
                createdAt,
                createdAt);
    }

    /**
     * Claims PENDING, a due UNCERTAIN park observation, or lease-expired RUNNING work.
     *
     * <p>The retry time bounds polling while the Action Runtime persists WAITING_EXTERNAL. The
     * runner uses a short observation backoff and retains the immutable createdAt-based grace
     * boundary.</p>
     */
    public CertificationDispatchIntent claim(String token, Instant claimedAt, Duration lease) {
        token = requireText(token, "claimToken");
        requireMonotonic(claimedAt, "claimedAt");
        Objects.requireNonNull(lease, "lease");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        boolean claimable = status == CertificationDispatchIntentStatus.PENDING
                || (status == CertificationDispatchIntentStatus.UNCERTAIN
                        && leaseUntil.filter(value -> !claimedAt.isBefore(value)).isPresent())
                || (status == CertificationDispatchIntentStatus.RUNNING
                        && leaseUntil.filter(value -> !claimedAt.isBefore(value)).isPresent());
        if (!claimable) {
            throw new IllegalStateException("Certification dispatch intent is not claimable");
        }
        return next(
                CertificationDispatchIntentStatus.RUNNING,
                Optional.of(token),
                Optional.of(claimedAt.plus(lease)),
                attemptCount + 1,
                lastCode,
                claimedAt);
    }

    /** Holds the normal executor-return-to-WAITING_EXTERNAL park window without issuing. */
    public CertificationDispatchIntent uncertain(
            String token, String code, Instant observedAt, Instant retryAt) {
        requireCurrentClaim(token);
        code = requireText(code, "code");
        requireMonotonic(observedAt, "observedAt");
        Objects.requireNonNull(retryAt, "retryAt");
        if (!retryAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("retryAt must be after observedAt");
        }
        return next(
                CertificationDispatchIntentStatus.UNCERTAIN,
                Optional.empty(),
                Optional.of(retryAt),
                attemptCount,
                Optional.of(code),
                observedAt);
    }

    public CertificationDispatchIntent complete(String token, String code, Instant completedAt) {
        return terminal(token, code, completedAt, CertificationDispatchIntentStatus.COMPLETED);
    }

    public CertificationDispatchIntent orphan(String token, String code, Instant orphanedAt) {
        return terminal(token, code, orphanedAt, CertificationDispatchIntentStatus.ORPHANED);
    }

    public CertificationDispatchIntent orphanBeforeWaiting(
            String token, String code, Instant orphanedAt) {
        return terminal(
                token, code, orphanedAt, CertificationDispatchIntentStatus.ORPHANED_BEFORE_WAITING);
    }

    public boolean sameImmutableIdentity(CertificationDispatchIntent other) {
        return other != null
                && operationId.equals(other.operationId)
                && tenantId.equals(other.tenantId)
                && certificationId.equals(other.certificationId)
                && inputLockManifestHash.equals(other.inputLockManifestHash)
                && expectedCertificationVersion == other.expectedCertificationVersion
                && actionRunId.equals(other.actionRunId)
                && attemptTokenHash.equals(other.attemptTokenHash)
                && deadlineAt.equals(other.deadlineAt);
    }

    private CertificationDispatchIntent terminal(
            String token,
            String code,
            Instant terminalAt,
            CertificationDispatchIntentStatus terminalStatus) {
        requireCurrentClaim(token);
        code = requireText(code, "code");
        requireMonotonic(terminalAt, "terminalAt");
        return next(
                terminalStatus,
                Optional.empty(),
                Optional.empty(),
                attemptCount,
                Optional.of(code),
                terminalAt);
    }

    private CertificationDispatchIntent next(
            CertificationDispatchIntentStatus nextStatus,
            Optional<String> nextClaim,
            Optional<Instant> nextLease,
            int nextAttemptCount,
            Optional<String> nextCode,
            Instant nextUpdatedAt) {
        return new CertificationDispatchIntent(
                operationId,
                tenantId,
                certificationId,
                inputLockManifestHash,
                expectedCertificationVersion,
                actionRunId,
                attemptTokenHash,
                deadlineAt,
                nextStatus,
                nextClaim,
                nextLease,
                nextAttemptCount,
                nextCode,
                version + 1,
                createdAt,
                nextUpdatedAt);
    }

    private void requireCurrentClaim(String token) {
        token = requireText(token, "claimToken");
        if (status != CertificationDispatchIntentStatus.RUNNING
                || claimToken.filter(token::equals).isEmpty()) {
            throw new IllegalStateException("only the current claim owner may transition the intent");
        }
    }

    private void requireMonotonic(Instant value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBefore(updatedAt)) {
            throw new IllegalArgumentException(name + " must not be before updatedAt");
        }
    }

    private static void validateLifecycle(
            CertificationDispatchIntentStatus status,
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
            case COMPLETED, ORPHANED, ORPHANED_BEFORE_WAITING -> {
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
        if (value == null
                || value.isBlank()
                || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value.trim();
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex value");
        }
        return normalized;
    }
}
