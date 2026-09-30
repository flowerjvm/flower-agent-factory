package io.github.flowerjvm.factory.application.build;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable specialized-product production ledger snapshot transitioned only through version CAS. */
public record BuildSession(
        BuildSessionId buildSessionId,
        TenantId tenantId,
        ProjectId projectId,
        ProductLineId productLineId,
        String requestIdempotencyKey,
        String createdBy,
        BuildSessionStatus status,
        BuildSessionPhase currentPhase,
        ArtifactReference requirementsArtifactRef,
        ContentHash requirementsHash,
        Optional<String> selectedManagerWorkerBinding,
        Optional<String> selectedCodingWorkerBinding,
        Optional<ArtifactReference> currentBlueprintRef,
        Optional<CandidateId> currentCandidateId,
        Optional<ContentHash> currentCandidateHash,
        Optional<CertificationId> currentCertificationId,
        int repairRound,
        int maxRepairRounds,
        Instant startedAt,
        Instant deadlineAt,
        Optional<Instant> cancellationRequestedAt,
        Optional<String> terminalCode,
        Optional<String> terminalMessage,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public BuildSession {
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(productLineId, "productLineId");
        requestIdempotencyKey = requireText(requestIdempotencyKey, "requestIdempotencyKey");
        createdBy = requireText(createdBy, "createdBy");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currentPhase, "currentPhase");
        Objects.requireNonNull(requirementsArtifactRef, "requirementsArtifactRef");
        Objects.requireNonNull(requirementsHash, "requirementsHash");
        selectedManagerWorkerBinding = requireOptionalText(
                selectedManagerWorkerBinding, "selectedManagerWorkerBinding");
        selectedCodingWorkerBinding = requireOptionalText(
                selectedCodingWorkerBinding, "selectedCodingWorkerBinding");
        currentBlueprintRef = Objects.requireNonNull(currentBlueprintRef, "currentBlueprintRef");
        currentCandidateId = Objects.requireNonNull(currentCandidateId, "currentCandidateId");
        currentCandidateHash = Objects.requireNonNull(currentCandidateHash, "currentCandidateHash");
        if (currentCandidateId.isPresent() != currentCandidateHash.isPresent()) {
            throw new IllegalArgumentException("current candidate id and hash must be present together");
        }
        currentCertificationId = Objects.requireNonNull(currentCertificationId, "currentCertificationId");
        if (repairRound < 0) {
            throw new IllegalArgumentException("repairRound must not be negative");
        }
        if (maxRepairRounds < 0) {
            throw new IllegalArgumentException("maxRepairRounds must not be negative");
        }
        if (repairRound > maxRepairRounds) {
            throw new IllegalArgumentException("repairRound must not exceed maxRepairRounds");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (deadlineAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("deadlineAt must not be before startedAt");
        }
        cancellationRequestedAt = Objects.requireNonNull(cancellationRequestedAt, "cancellationRequestedAt");
        terminalCode = requireOptionalText(terminalCode, "terminalCode");
        terminalMessage = requireOptionalText(terminalMessage, "terminalMessage");
        if (status.isTerminal() && terminalCode.isEmpty()) {
            throw new IllegalArgumentException("terminal BuildSession must have a terminalCode");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    /** Records the trusted cancellation request before the Flower control signal is sent. */
    public BuildSession requestCancellation(Instant requestedAt) {
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (status.isTerminal()) {
            throw new IllegalStateException("terminal BuildSession cannot be cancelled");
        }
        if (cancellationRequestedAt.isPresent()) {
            return this;
        }
        if (requestedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("cancellation time must be monotonic");
        }
        return copy(
                BuildSessionStatus.CANCELLING,
                currentPhase,
                Optional.of(requestedAt),
                terminalCode,
                terminalMessage,
                requestedAt);
    }

    /** Closes a CANCELLING session only after the external Worker stop was confirmed. */
    public BuildSession confirmCancellation(String code, String message, Instant confirmedAt) {
        code = requireText(code, "code");
        message = requireText(message, "message");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (status != BuildSessionStatus.CANCELLING || cancellationRequestedAt.isEmpty()) {
            throw new IllegalStateException("only a cancellation-requested BuildSession can be cancelled");
        }
        if (confirmedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("cancellation confirmation time must be monotonic");
        }
        return copy(
                BuildSessionStatus.CANCELLED,
                currentPhase,
                cancellationRequestedAt,
                Optional.of(code),
                Optional.of(message),
                confirmedAt);
    }

    /** Preserves uncertainty when the remote Worker stop cannot be proven. */
    public BuildSession manualReviewCancellation(String code, String message, Instant observedAt) {
        code = requireText(code, "code");
        message = requireText(message, "message");
        Objects.requireNonNull(observedAt, "observedAt");
        if (status != BuildSessionStatus.CANCELLING || cancellationRequestedAt.isEmpty()) {
            throw new IllegalStateException("only a cancellation-requested BuildSession can enter manual review");
        }
        if (observedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("manual-review time must be monotonic");
        }
        return copy(
                BuildSessionStatus.MANUAL_REVIEW,
                currentPhase,
                cancellationRequestedAt,
                Optional.of(code),
                Optional.of(message),
                observedAt);
    }

    /**
     * Stops ordinary orchestration when a pre-external-effect invariant cannot be reconciled.
     *
     * <p>This is distinct from cancellation review: no cancellation authority is inferred and no
     * external stop is claimed. The persisted code must identify an operator-visible recovery
     * boundary.
     */
    public BuildSession manualReview(String code, String message, Instant observedAt) {
        code = requireStableCode(code);
        message = requireText(message, "message");
        Objects.requireNonNull(observedAt, "observedAt");
        if ((status != BuildSessionStatus.RUNNING && status != BuildSessionStatus.REPAIRING)
                || cancellationRequestedAt.isPresent()) {
            throw new IllegalStateException(
                    "only a non-cancelling RUNNING/REPAIRING BuildSession can enter manual review");
        }
        if (observedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("manual-review time must be monotonic");
        }
        return copy(
                BuildSessionStatus.MANUAL_REVIEW,
                currentPhase,
                cancellationRequestedAt,
                Optional.of(code),
                Optional.of(message),
                observedAt);
    }

    /** Advances one local, durable stage of the concrete Reference Assembly production line. */
    public BuildSession advanceReferenceAssemblyPhase(
            BuildSessionPhase expectedPhase,
            BuildSessionPhase nextPhase,
            Instant advancedAt) {
        Objects.requireNonNull(expectedPhase, "expectedPhase");
        Objects.requireNonNull(nextPhase, "nextPhase");
        requireLiveReferenceAssembly(expectedPhase, advancedAt);
        boolean admitted = (expectedPhase == BuildSessionPhase.UNDERSTAND_CUSTOMER
                        && nextPhase == BuildSessionPhase.RESOLVE_REUSE_STRATEGY)
                || (expectedPhase == BuildSessionPhase.RESOLVE_REUSE_STRATEGY
                        && nextPhase == BuildSessionPhase.ASSEMBLE_CANDIDATE)
                || (expectedPhase == BuildSessionPhase.ASSEMBLE_CANDIDATE
                        && nextPhase == BuildSessionPhase.TEST);
        if (!admitted) {
            throw new IllegalArgumentException(
                    "unsupported Reference Assembly phase transition");
        }
        return copy(
                BuildSessionStatus.RUNNING,
                nextPhase,
                cancellationRequestedAt,
                Optional.empty(),
                Optional.empty(),
                advancedAt);
    }

    /** Parks the inspected Reference Assembly at its hash-bound human release review. */
    public BuildSession awaitReferenceAssemblyReleaseReview(Instant observedAt) {
        requireLiveReferenceAssembly(BuildSessionPhase.TEST, observedAt);
        return copy(
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                cancellationRequestedAt,
                Optional.empty(),
                Optional.empty(),
                observedAt);
    }

    /** Resumes packaging only after the exact Reference Assembly review became APPROVED. */
    public BuildSession resumeReferenceAssemblyRelease(Instant approvedAt) {
        Objects.requireNonNull(approvedAt, "approvedAt");
        if (!ProductLineId.REFERENCE_ASSEMBLY.equals(productLineId)
                || status != BuildSessionStatus.WAITING_RELEASE_REVIEW
                || currentPhase != BuildSessionPhase.HUMAN_RELEASE_REVIEW
                || cancellationRequestedAt.isPresent()) {
            throw new IllegalStateException(
                    "BuildSession is not waiting for a Reference Assembly release review");
        }
        requireReferenceAssemblyTime(approvedAt, true);
        return copy(
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.PACKAGE_RELEASE,
                cancellationRequestedAt,
                Optional.empty(),
                Optional.empty(),
                approvedAt);
    }

    /** Projects a canonically released Reference Assembly into the terminal BuildSession ledger. */
    public BuildSession completeReferenceAssemblyRelease(Instant completedAt) {
        Objects.requireNonNull(completedAt, "completedAt");
        if (!ProductLineId.REFERENCE_ASSEMBLY.equals(productLineId)
                || status != BuildSessionStatus.RUNNING
                || currentPhase != BuildSessionPhase.PACKAGE_RELEASE
                || cancellationRequestedAt.isPresent()) {
            throw new IllegalStateException(
                    "BuildSession is not a live Reference Assembly release projection");
        }
        // The SQL release transaction enforced the persisted deadline. This projection remains
        // recoverable after that deadline if a crash occurred after the product ledger commit.
        requireReferenceAssemblyTime(completedAt, false);
        return copy(
                BuildSessionStatus.SUCCEEDED,
                BuildSessionPhase.COMPLETE,
                cancellationRequestedAt,
                Optional.of("REFERENCE_ASSEMBLY_RELEASED"),
                Optional.of("Reference Assembly release manifest is ready"),
                completedAt);
    }

    /** Persists a deterministic product-line rejection without claiming a released product. */
    public BuildSession failReferenceAssembly(
            String code, String message, Instant failedAt) {
        code = requireStableCode(code);
        message = requireText(message, "message");
        Objects.requireNonNull(failedAt, "failedAt");
        if (!ProductLineId.REFERENCE_ASSEMBLY.equals(productLineId)
                || status.isTerminal()
                || cancellationRequestedAt.isPresent()) {
            throw new IllegalStateException(
                    "BuildSession is not a fail-able Reference Assembly production run");
        }
        requireReferenceAssemblyTime(failedAt, false);
        return copy(
                BuildSessionStatus.FAILED,
                currentPhase,
                cancellationRequestedAt,
                Optional.of(code),
                Optional.of(message),
                failedAt);
    }

    /** Preserves an ambiguous Reference Assembly owner or repository boundary for operators. */
    public BuildSession manualReviewReferenceAssembly(
            String code, String message, Instant observedAt) {
        code = requireStableCode(code);
        message = requireText(message, "message");
        Objects.requireNonNull(observedAt, "observedAt");
        if (!ProductLineId.REFERENCE_ASSEMBLY.equals(productLineId)
                || (status != BuildSessionStatus.RUNNING
                        && status != BuildSessionStatus.WAITING_RELEASE_REVIEW)
                || cancellationRequestedAt.isPresent()) {
            throw new IllegalStateException(
                    "BuildSession is not an operator-reviewable Reference Assembly run");
        }
        requireReferenceAssemblyTime(observedAt, false);
        return copy(
                BuildSessionStatus.MANUAL_REVIEW,
                currentPhase,
                cancellationRequestedAt,
                Optional.of(code),
                Optional.of(message),
                observedAt);
    }

    /**
     * Starts the concrete Agent Pack certification continuation from the historical release-ready
     * boundary.
     *
     * <p>The Certification request row and this returned snapshot must be persisted atomically by
     * the certification request transaction. Binding a successfully issued Certification is a
     * later release-flow transition and is intentionally not performed here.
     */
    public BuildSession beginAgentPackCertification(Instant requestedAt) {
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (!ProductLineId.AGENT_PACK.equals(productLineId)
                || status != BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE
                || currentPhase != BuildSessionPhase.HUMAN_RELEASE_REVIEW) {
            throw new IllegalStateException(
                    "only a release-ready Agent Pack BuildSession can begin certification");
        }
        if (currentCandidateId.isEmpty() || currentCandidateHash.isEmpty()) {
            throw new IllegalStateException("Agent Pack certification requires an exact current candidate");
        }
        if (currentCertificationId.isPresent()) {
            throw new IllegalStateException("release-ready BuildSession already has a Certification");
        }
        if (cancellationRequestedAt.isPresent()) {
            throw new IllegalStateException("a cancellation-requested BuildSession cannot begin certification");
        }
        if (requestedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("certification request time must be monotonic");
        }
        if (!requestedAt.isBefore(deadlineAt)) {
            throw new IllegalStateException("a BuildSession cannot begin certification at or after its deadline");
        }
        return copy(
                BuildSessionStatus.CERTIFYING,
                BuildSessionPhase.CERTIFY,
                cancellationRequestedAt,
                terminalCode,
                terminalMessage,
                requestedAt);
    }

    /**
     * Binds the canonically Action-owned Certification and hands control to human release review.
     */
    public BuildSession awaitAgentPackReleaseReview(
            CertificationId certificationId, Instant observedAt) {
        Objects.requireNonNull(certificationId, "certificationId");
        requireLiveAgentPackCertification(observedAt);
        if (currentCertificationId.isPresent()) {
            throw new IllegalStateException("CERTIFYING BuildSession already has a Certification binding");
        }
        return copyCertificationOutcome(
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                Optional.of(certificationId),
                Optional.empty(),
                Optional.empty(),
                observedAt);
    }

    /** Persists a fail-closed Agent Pack certification outcome without claiming a valid certificate. */
    public BuildSession blockAgentPackCertification(
            String code, String message, Instant observedAt) {
        code = requireStableCode(code);
        message = requireText(message, "message");
        requireAgentPackCertificationBoundary(observedAt);
        return copyCertificationOutcome(
                BuildSessionStatus.BLOCKED,
                currentPhase,
                Optional.empty(),
                Optional.of(code),
                Optional.of(message),
                observedAt);
    }

    /** Preserves an ambiguous or invalid issuance owner for explicit operator reconciliation. */
    public BuildSession manualReviewAgentPackCertification(
            String code, String message, Instant observedAt) {
        code = requireStableCode(code);
        message = requireText(message, "message");
        requireAgentPackCertificationBoundary(observedAt);
        return copyCertificationOutcome(
                BuildSessionStatus.MANUAL_REVIEW,
                currentPhase,
                Optional.empty(),
                Optional.of(code),
                Optional.of(message),
                observedAt);
    }

    private void requireLiveAgentPackCertification(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        if (!ProductLineId.AGENT_PACK.equals(productLineId)
                || status != BuildSessionStatus.CERTIFYING
                || currentPhase != BuildSessionPhase.CERTIFY
                || currentCandidateId.isEmpty()
                || currentCandidateHash.isEmpty()
                || cancellationRequestedAt.isPresent()) {
            throw new IllegalStateException("BuildSession is not a live Agent Pack certification authority");
        }
        if (observedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("certification observation time must be monotonic");
        }
    }

    private void requireLiveReferenceAssembly(
            BuildSessionPhase expectedPhase, Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        if (!ProductLineId.REFERENCE_ASSEMBLY.equals(productLineId)
                || status != BuildSessionStatus.RUNNING
                || currentPhase != expectedPhase
                || cancellationRequestedAt.isPresent()) {
            throw new IllegalStateException(
                    "BuildSession is not a live Reference Assembly phase authority");
        }
        requireReferenceAssemblyTime(observedAt, true);
    }

    private void requireReferenceAssemblyTime(Instant value, boolean requireBeforeDeadline) {
        if (value.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "Reference Assembly transition time must be monotonic");
        }
        if (requireBeforeDeadline && !value.isBefore(deadlineAt)) {
            throw new IllegalStateException(
                    "Reference Assembly transition must occur before its deadline");
        }
    }

    private void requireAgentPackCertificationBoundary(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        boolean releaseReady = status == BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE
                && currentPhase == BuildSessionPhase.HUMAN_RELEASE_REVIEW;
        boolean certifying = status == BuildSessionStatus.CERTIFYING
                && currentPhase == BuildSessionPhase.CERTIFY;
        if (!ProductLineId.AGENT_PACK.equals(productLineId)
                || (!releaseReady && !certifying)
                || currentCandidateId.isEmpty()
                || currentCandidateHash.isEmpty()
                || currentCertificationId.isPresent()
                || cancellationRequestedAt.isPresent()) {
            throw new IllegalStateException("BuildSession is not at an Agent Pack certification boundary");
        }
        if (observedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("certification observation time must be monotonic");
        }
    }

    private BuildSession copyCertificationOutcome(
            BuildSessionStatus nextStatus,
            BuildSessionPhase nextPhase,
            Optional<CertificationId> nextCertificationId,
            Optional<String> nextTerminalCode,
            Optional<String> nextTerminalMessage,
            Instant nextUpdatedAt) {
        return new BuildSession(
                buildSessionId,
                tenantId,
                projectId,
                productLineId,
                requestIdempotencyKey,
                createdBy,
                nextStatus,
                nextPhase,
                requirementsArtifactRef,
                requirementsHash,
                selectedManagerWorkerBinding,
                selectedCodingWorkerBinding,
                currentBlueprintRef,
                currentCandidateId,
                currentCandidateHash,
                nextCertificationId,
                repairRound,
                maxRepairRounds,
                startedAt,
                deadlineAt,
                cancellationRequestedAt,
                nextTerminalCode,
                nextTerminalMessage,
                version + 1,
                createdAt,
                nextUpdatedAt);
    }

    private BuildSession copy(
            BuildSessionStatus nextStatus,
            BuildSessionPhase nextPhase,
            Optional<Instant> nextCancellationRequestedAt,
            Optional<String> nextTerminalCode,
            Optional<String> nextTerminalMessage,
            Instant nextUpdatedAt) {
        return new BuildSession(
                buildSessionId,
                tenantId,
                projectId,
                productLineId,
                requestIdempotencyKey,
                createdBy,
                nextStatus,
                nextPhase,
                requirementsArtifactRef,
                requirementsHash,
                selectedManagerWorkerBinding,
                selectedCodingWorkerBinding,
                currentBlueprintRef,
                currentCandidateId,
                currentCandidateHash,
                currentCertificationId,
                repairRound,
                maxRepairRounds,
                startedAt,
                deadlineAt,
                nextCancellationRequestedAt,
                nextTerminalCode,
                nextTerminalMessage,
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

    private static String requireStableCode(String value) {
        value = requireText(value, "code");
        if (!value.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a bounded uppercase stable code");
        }
        return value;
    }
}
