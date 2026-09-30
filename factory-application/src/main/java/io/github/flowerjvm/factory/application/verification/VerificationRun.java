package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable snapshot of one deterministic gate execution, transitioned only through version CAS. */
public record VerificationRun(
        VerificationRunId verificationRunId,
        TenantId tenantId,
        BuildSessionId buildSessionId,
        CandidateId candidateId,
        ContentHash candidateHash,
        String gateProfile,
        ContentHash toolchainLockHash,
        ContentHash fixtureSetHash,
        VerificationRunStatus status,
        Optional<ArtifactReference> resultManifestRef,
        Optional<ContentHash> resultManifestHash,
        Optional<String> terminalCode,
        Optional<VerificationDisposition> disposition,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public VerificationRun {
        Objects.requireNonNull(verificationRunId, "verificationRunId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(candidateHash, "candidateHash");
        gateProfile = requireText(gateProfile, "gateProfile");
        Objects.requireNonNull(toolchainLockHash, "toolchainLockHash");
        Objects.requireNonNull(fixtureSetHash, "fixtureSetHash");
        Objects.requireNonNull(status, "status");
        resultManifestRef = Objects.requireNonNull(resultManifestRef, "resultManifestRef");
        resultManifestHash = Objects.requireNonNull(resultManifestHash, "resultManifestHash");
        terminalCode = requireOptionalText(terminalCode, "terminalCode");
        disposition = Objects.requireNonNull(disposition, "disposition");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        validateLifecycle(
                status,
                resultManifestRef,
                resultManifestHash,
                terminalCode,
                disposition,
                startedAt,
                completedAt,
                createdAt,
                updatedAt);
    }

    /**
     * PR3 source-compatible constructor. New verification execution must use the full constructor and
     * must never invent terminal evidence fields.
     */
    public VerificationRun(
            VerificationRunId verificationRunId,
            TenantId tenantId,
            BuildSessionId buildSessionId,
            CandidateId candidateId,
            ContentHash candidateHash,
            String gateProfile,
            ContentHash toolchainLockHash,
            ContentHash fixtureSetHash,
            VerificationRunStatus status,
            Optional<ArtifactReference> resultManifestRef,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this(
                verificationRunId,
                tenantId,
                buildSessionId,
                candidateId,
                candidateHash,
                gateProfile,
                toolchainLockHash,
                fixtureSetHash,
                status,
                resultManifestRef,
                status.isTerminal() ? Optional.of(LEGACY_RESULT_MANIFEST_HASH) : Optional.empty(),
                status.isTerminal() ? Optional.of(legacyTerminalCode(status)) : Optional.empty(),
                status.isTerminal() ? Optional.of(legacyDisposition(status)) : Optional.empty(),
                startedAt,
                completedAt,
                version,
                createdAt,
                updatedAt);
    }

    /** Explicit, recognizable sentinel used only to read or construct pre-PR4 test/legacy evidence. */
    public static final ContentHash LEGACY_RESULT_MANIFEST_HASH =
            new ContentHash("0000000000000000000000000000000000000000000000000000000000000000");

    public VerificationRun start(Instant startedAt) {
        Objects.requireNonNull(startedAt, "startedAt");
        if (status != VerificationRunStatus.REQUESTED) {
            throw new IllegalStateException("only a REQUESTED VerificationRun can start");
        }
        if (startedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("startedAt must not be before the last update");
        }
        return new VerificationRun(
                verificationRunId,
                tenantId,
                buildSessionId,
                candidateId,
                candidateHash,
                gateProfile,
                toolchainLockHash,
                fixtureSetHash,
                VerificationRunStatus.RUNNING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(startedAt),
                Optional.empty(),
                version + 1,
                createdAt,
                startedAt);
    }

    public VerificationRun complete(
            VerificationRunStatus terminalStatus,
            ArtifactReference resultManifestRef,
            ContentHash resultManifestHash,
            String terminalCode,
            VerificationDisposition disposition,
            Instant completedAt) {
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        Objects.requireNonNull(resultManifestRef, "resultManifestRef");
        Objects.requireNonNull(resultManifestHash, "resultManifestHash");
        terminalCode = requireText(terminalCode, "terminalCode");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(completedAt, "completedAt");
        if (status != VerificationRunStatus.RUNNING) {
            throw new IllegalStateException("only a RUNNING VerificationRun can complete");
        }
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("completion status must be terminal");
        }
        if (completedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("completedAt must not be before the last update");
        }
        return new VerificationRun(
                verificationRunId,
                tenantId,
                buildSessionId,
                candidateId,
                candidateHash,
                gateProfile,
                toolchainLockHash,
                fixtureSetHash,
                terminalStatus,
                Optional.of(resultManifestRef),
                Optional.of(resultManifestHash),
                Optional.of(terminalCode),
                Optional.of(disposition),
                startedAt,
                Optional.of(completedAt),
                version + 1,
                createdAt,
                completedAt);
    }

    /** PR3 source-compatible completion. Production PR4 execution uses the evidence-bound overload. */
    public VerificationRun complete(
            VerificationRunStatus terminalStatus,
            ArtifactReference resultManifestRef,
            Instant completedAt) {
        return complete(
                terminalStatus,
                resultManifestRef,
                LEGACY_RESULT_MANIFEST_HASH,
                legacyTerminalCode(terminalStatus),
                legacyDisposition(terminalStatus),
                completedAt);
    }

    private static void validateLifecycle(
            VerificationRunStatus status,
            Optional<ArtifactReference> resultManifestRef,
            Optional<ContentHash> resultManifestHash,
            Optional<String> terminalCode,
            Optional<VerificationDisposition> disposition,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt,
            Instant createdAt,
            Instant updatedAt) {
        if (status == VerificationRunStatus.REQUESTED
                && (startedAt.isPresent()
                        || completedAt.isPresent()
                        || resultManifestRef.isPresent()
                        || resultManifestHash.isPresent()
                        || terminalCode.isPresent()
                        || disposition.isPresent())) {
            throw new IllegalArgumentException("REQUESTED VerificationRun must not contain execution results");
        }
        if (status == VerificationRunStatus.RUNNING
                && (startedAt.isEmpty()
                        || completedAt.isPresent()
                        || resultManifestRef.isPresent()
                        || resultManifestHash.isPresent()
                        || terminalCode.isPresent()
                        || disposition.isPresent())) {
            throw new IllegalArgumentException("RUNNING VerificationRun must contain only startedAt");
        }
        if (status.isTerminal()
                && (startedAt.isEmpty()
                        || completedAt.isEmpty()
                        || resultManifestRef.isEmpty()
                        || resultManifestHash.isEmpty()
                        || terminalCode.isEmpty()
                        || disposition.isEmpty())) {
            throw new IllegalArgumentException("terminal VerificationRun must contain start, completion and result");
        }
        if (status == VerificationRunStatus.PASSED
                && disposition.filter(value -> value != VerificationDisposition.REVIEW_ELIGIBLE).isPresent()) {
            throw new IllegalArgumentException("PASSED VerificationRun must be review eligible");
        }
        if (status == VerificationRunStatus.FAILED
                && disposition.filter(value -> value == VerificationDisposition.REVIEW_ELIGIBLE).isPresent()) {
            throw new IllegalArgumentException("FAILED VerificationRun must not be review eligible");
        }
        startedAt.ifPresent(start -> {
            if (start.isBefore(createdAt)) {
                throw new IllegalArgumentException("startedAt must not be before createdAt");
            }
        });
        if (startedAt.isPresent() && completedAt.isPresent() && completedAt.get().isBefore(startedAt.get())) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
        if (completedAt.isPresent() && !updatedAt.equals(completedAt.get())) {
            throw new IllegalArgumentException("terminal updatedAt must equal completedAt");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Optional<String> requireOptionalText(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(text -> requireText(text, name));
        return value;
    }

    private static String legacyTerminalCode(VerificationRunStatus status) {
        return switch (status) {
            case PASSED -> "LEGACY_VERIFICATION_PASSED";
            case FAILED -> "LEGACY_VERIFICATION_FAILED";
            default -> throw new IllegalArgumentException("legacy completion status must be terminal");
        };
    }

    private static VerificationDisposition legacyDisposition(VerificationRunStatus status) {
        return switch (status) {
            case PASSED -> VerificationDisposition.REVIEW_ELIGIBLE;
            case FAILED -> VerificationDisposition.REPAIR_REQUIRED;
            default -> throw new IllegalArgumentException("legacy completion status must be terminal");
        };
    }
}
