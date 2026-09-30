package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Immutable lease/CAS snapshot for one deferred Reference Assembly release. */
public record ReferenceAssemblyReleaseDispatchIntent(
        String operationId,
        TenantId tenantId,
        ReferenceAssemblyId referenceAssemblyId,
        ContentHash assemblyManifestHash,
        ContentHash inspectionReportHash,
        DecisionPointId releaseDecisionPointId,
        ContentHash releaseSubjectHash,
        long expectedReferenceAssemblyVersion,
        String actionRunId,
        String attemptTokenHash,
        Instant deadlineAt,
        ReferenceAssemblyReleaseDispatchIntentStatus status,
        Optional<String> claimToken,
        Optional<Instant> leaseUntil,
        int attemptCount,
        Optional<String> lastCode,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    private static final Pattern FLOATING_SEGMENT =
            Pattern.compile("(?i)(?:^|[:/@._-])(latest|head)(?:$|[:/@._-])");

    public ReferenceAssemblyReleaseDispatchIntent {
        operationId = requireOperationId(operationId);
        Objects.requireNonNull(tenantId, "tenantId");
        requireExactIdentity(tenantId.value(), "tenantId", 128);
        Objects.requireNonNull(referenceAssemblyId, "referenceAssemblyId");
        requireExactIdentity(referenceAssemblyId.value(), "referenceAssemblyId", 128);
        Objects.requireNonNull(assemblyManifestHash, "assemblyManifestHash");
        Objects.requireNonNull(inspectionReportHash, "inspectionReportHash");
        Objects.requireNonNull(releaseDecisionPointId, "releaseDecisionPointId");
        requireExactIdentity(releaseDecisionPointId.value(), "releaseDecisionPointId", 128);
        Objects.requireNonNull(releaseSubjectHash, "releaseSubjectHash");
        if (expectedReferenceAssemblyVersion < 0) {
            throw new IllegalArgumentException(
                    "expectedReferenceAssemblyVersion must not be negative");
        }
        ReferenceAssemblyReleaseInput exactInput = new ReferenceAssemblyReleaseInput(
                referenceAssemblyId,
                assemblyManifestHash,
                inspectionReportHash,
                releaseDecisionPointId,
                releaseSubjectHash,
                expectedReferenceAssemblyVersion);
        if (!operationId.equals(
                ReferenceAssemblyReleaseDispatchOperationIds.derive(tenantId, exactInput))) {
            throw new IllegalArgumentException(
                    "operationId must bind the exact tenant and release input");
        }
        actionRunId = requireExactIdentity(actionRunId, "actionRunId", 64);
        attemptTokenHash = requireHash(attemptTokenHash, "attemptTokenHash");
        deadlineAt = requireCanonicalInstant(deadlineAt, "deadlineAt");
        Objects.requireNonNull(status, "status");
        claimToken = requireOptionalExactIdentity(claimToken, "claimToken", 128);
        leaseUntil = requireOptionalCanonicalInstant(leaseUntil, "leaseUntil");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
        lastCode = requireOptionalStableCode(lastCode);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        createdAt = requireCanonicalInstant(createdAt, "createdAt");
        updatedAt = requireCanonicalInstant(updatedAt, "updatedAt");
        if (!deadlineAt.isAfter(createdAt) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("release intent timestamps are inconsistent");
        }
        validateLifecycle(
                status,
                claimToken,
                leaseUntil,
                attemptCount,
                lastCode,
                version,
                createdAt,
                updatedAt);
    }

    public static ReferenceAssemblyReleaseDispatchIntent pending(
            String operationId,
            TenantId tenantId,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            String attemptTokenHash,
            Instant deadlineAt,
            Instant createdAt) {
        Objects.requireNonNull(input, "input");
        return new ReferenceAssemblyReleaseDispatchIntent(
                operationId,
                tenantId,
                input.referenceAssemblyId(),
                input.assemblyManifestHash(),
                input.inspectionReportHash(),
                input.releaseDecisionPointId(),
                input.releaseSubjectHash(),
                input.expectedReferenceAssemblyVersion(),
                actionRunId,
                attemptTokenHash,
                deadlineAt,
                ReferenceAssemblyReleaseDispatchIntentStatus.PENDING,
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.empty(),
                0,
                createdAt,
                createdAt);
    }

    /** Claims new, due uncertain, or lease-expired work through an immutable CAS snapshot. */
    public ReferenceAssemblyReleaseDispatchIntent claim(
            String token, Instant claimedAt, Duration lease) {
        String exactToken = requireExactIdentity(token, "claimToken", 128);
        Instant exactClaimedAt = requireMonotonic(claimedAt, "claimedAt");
        Objects.requireNonNull(lease, "lease");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        boolean claimable = status == ReferenceAssemblyReleaseDispatchIntentStatus.PENDING
                || (status == ReferenceAssemblyReleaseDispatchIntentStatus.UNCERTAIN
                        && leaseUntil.filter(value -> !exactClaimedAt.isBefore(value)).isPresent())
                || (status == ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING
                        && leaseUntil.filter(value -> !exactClaimedAt.isBefore(value)).isPresent());
        if (!claimable) {
            throw new IllegalStateException("Reference Assembly release intent is not claimable");
        }
        Instant nextLease = requireCanonicalInstant(exactClaimedAt.plus(lease), "leaseUntil");
        if (!nextLease.isAfter(exactClaimedAt)) {
            throw new IllegalArgumentException("lease must advance the canonical claim time");
        }
        return next(
                ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING,
                Optional.of(exactToken),
                Optional.of(nextLease),
                Math.addExact(attemptCount, 1),
                lastCode,
                exactClaimedAt);
    }

    /** Defers the normal Action RUNNING-to-WAITING_EXTERNAL park observation. */
    public ReferenceAssemblyReleaseDispatchIntent uncertain(
            String token, String code, Instant observedAt, Instant retryAt) {
        requireCurrentClaim(token);
        code = requireStableCode(code);
        observedAt = requireMonotonic(observedAt, "observedAt");
        retryAt = requireCanonicalInstant(retryAt, "retryAt");
        if (!retryAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("retryAt must be after observedAt");
        }
        return next(
                ReferenceAssemblyReleaseDispatchIntentStatus.UNCERTAIN,
                Optional.empty(),
                Optional.of(retryAt),
                attemptCount,
                Optional.of(code),
                observedAt);
    }

    public ReferenceAssemblyReleaseDispatchIntent complete(
            String token, String code, Instant completedAt) {
        return terminal(
                token, code, completedAt, ReferenceAssemblyReleaseDispatchIntentStatus.COMPLETED);
    }

    public ReferenceAssemblyReleaseDispatchIntent orphan(
            String token, String code, Instant orphanedAt) {
        return terminal(
                token, code, orphanedAt, ReferenceAssemblyReleaseDispatchIntentStatus.ORPHANED);
    }

    public ReferenceAssemblyReleaseDispatchIntent orphanBeforeWaiting(
            String token, String code, Instant orphanedAt) {
        return terminal(
                token,
                code,
                orphanedAt,
                ReferenceAssemblyReleaseDispatchIntentStatus.ORPHANED_BEFORE_WAITING);
    }

    public boolean sameImmutableIdentity(ReferenceAssemblyReleaseDispatchIntent other) {
        return other != null
                && operationId.equals(other.operationId)
                && tenantId.equals(other.tenantId)
                && referenceAssemblyId.equals(other.referenceAssemblyId)
                && assemblyManifestHash.equals(other.assemblyManifestHash)
                && inspectionReportHash.equals(other.inspectionReportHash)
                && releaseDecisionPointId.equals(other.releaseDecisionPointId)
                && releaseSubjectHash.equals(other.releaseSubjectHash)
                && expectedReferenceAssemblyVersion == other.expectedReferenceAssemblyVersion
                && actionRunId.equals(other.actionRunId)
                && attemptTokenHash.equals(other.attemptTokenHash)
                && deadlineAt.equals(other.deadlineAt);
    }

    private ReferenceAssemblyReleaseDispatchIntent terminal(
            String token,
            String code,
            Instant terminalAt,
            ReferenceAssemblyReleaseDispatchIntentStatus terminalStatus) {
        requireCurrentClaim(token);
        code = requireStableCode(code);
        terminalAt = requireMonotonic(terminalAt, "terminalAt");
        return next(
                terminalStatus,
                Optional.empty(),
                Optional.empty(),
                attemptCount,
                Optional.of(code),
                terminalAt);
    }

    private ReferenceAssemblyReleaseDispatchIntent next(
            ReferenceAssemblyReleaseDispatchIntentStatus nextStatus,
            Optional<String> nextClaimToken,
            Optional<Instant> nextLeaseUntil,
            int nextAttemptCount,
            Optional<String> nextLastCode,
            Instant nextUpdatedAt) {
        return new ReferenceAssemblyReleaseDispatchIntent(
                operationId,
                tenantId,
                referenceAssemblyId,
                assemblyManifestHash,
                inspectionReportHash,
                releaseDecisionPointId,
                releaseSubjectHash,
                expectedReferenceAssemblyVersion,
                actionRunId,
                attemptTokenHash,
                deadlineAt,
                nextStatus,
                nextClaimToken,
                nextLeaseUntil,
                nextAttemptCount,
                nextLastCode,
                Math.addExact(version, 1),
                createdAt,
                nextUpdatedAt);
    }

    private void requireCurrentClaim(String token) {
        token = requireExactIdentity(token, "claimToken", 128);
        if (status != ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING
                || claimToken.filter(token::equals).isEmpty()) {
            throw new IllegalStateException("only the current claim owner may transition the intent");
        }
    }

    private Instant requireMonotonic(Instant value, String name) {
        value = requireCanonicalInstant(value, name);
        if (value.isBefore(updatedAt)) {
            throw new IllegalArgumentException(name + " must not be before updatedAt");
        }
        return value;
    }

    private static void validateLifecycle(
            ReferenceAssemblyReleaseDispatchIntentStatus status,
            Optional<String> claimToken,
            Optional<Instant> leaseUntil,
            int attemptCount,
            Optional<String> lastCode,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        switch (status) {
            case PENDING -> {
                if (claimToken.isPresent()
                        || leaseUntil.isPresent()
                        || attemptCount != 0
                        || lastCode.isPresent()
                        || version != 0
                        || !updatedAt.equals(createdAt)) {
                    throw new IllegalArgumentException(
                            "PENDING release intent must be pristine version zero");
                }
            }
            case RUNNING -> {
                if (claimToken.isEmpty()
                        || leaseUntil.filter(value -> value.isAfter(updatedAt)).isEmpty()
                        || attemptCount < 1
                        || version < attemptCount
                        || (lastCode.isEmpty() && version != attemptCount)
                        || (lastCode.isPresent() && version <= attemptCount)) {
                    throw new IllegalArgumentException(
                            "RUNNING release intent requires an active future lease");
                }
            }
            case UNCERTAIN -> {
                if (claimToken.isPresent()
                        || leaseUntil.filter(value -> value.isAfter(updatedAt)).isEmpty()
                        || attemptCount < 1
                        || lastCode.isEmpty()
                        || version <= attemptCount) {
                    throw new IllegalArgumentException(
                            "UNCERTAIN release intent requires a future retry and stable code");
                }
            }
            case COMPLETED, ORPHANED, ORPHANED_BEFORE_WAITING -> {
                if (claimToken.isPresent()
                        || leaseUntil.isPresent()
                        || attemptCount < 1
                        || lastCode.isEmpty()
                        || version <= attemptCount) {
                    throw new IllegalArgumentException(
                            "terminal release intent requires code and no active lease");
                }
            }
        }
    }

    private static String requireOperationId(String value) {
        if (value == null
                || !value.matches("reference-assembly-release:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "operationId must be an exact Reference Assembly release SHA-256 identity");
        }
        return value;
    }

    private static Optional<String> requireOptionalExactIdentity(
            Optional<String> value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        value.ifPresent(text -> requireExactIdentity(text, name, maximumLength));
        return value;
    }

    private static Optional<Instant> requireOptionalCanonicalInstant(
            Optional<Instant> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(instant -> requireCanonicalInstant(instant, name));
        return value;
    }

    private static Optional<String> requireOptionalStableCode(Optional<String> value) {
        Objects.requireNonNull(value, "lastCode");
        value.ifPresent(ReferenceAssemblyReleaseDispatchIntent::requireStableCode);
        return value;
    }

    private static String requireExactIdentity(String value, String name, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)
                || FLOATING_SEGMENT.matcher(value).find()
                || value.matches(".*[?*\\[\\]{}].*")) {
            throw new IllegalArgumentException(name + " must be a bounded exact identity");
        }
        return value;
    }

    private static String requireHash(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex value");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex value");
        }
        return normalized;
    }

    private static String requireStableCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("lastCode must be a bounded uppercase stable code");
        }
        return value;
    }

    private static Instant requireCanonicalInstant(Instant value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.equals(value.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException(name + " must use microsecond precision");
        }
        return value;
    }
}
