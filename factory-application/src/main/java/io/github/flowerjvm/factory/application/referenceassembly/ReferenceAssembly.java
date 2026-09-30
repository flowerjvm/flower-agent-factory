package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable version-CAS snapshot for the concrete Reference Assembly ProductLine.
 *
 * <p>The exact upstream Agent Pack Certification remains a component input. It is deliberately not
 * projected into {@code BuildSession.currentCertificationId}, which belongs to the BuildSession's
 * own product.
 */
public record ReferenceAssembly(
        ReferenceAssemblyId referenceAssemblyId,
        TenantId tenantId,
        BuildSessionId buildSessionId,
        CertificationArtifactLock requirement,
        CertificationArtifactLock consumerContract,
        CertificationArtifactLock hostFixture,
        CertificationArtifactLock policySnapshot,
        CertificationId componentCertificationId,
        ContentHash componentCandidateHash,
        CertificationArtifactLock componentCertificationManifest,
        ReferenceAssemblyStatus status,
        Optional<CertificationArtifactLock> assemblyManifest,
        Optional<CertificationArtifactLock> inspectionReport,
        Optional<CertificationArtifactLock> releaseManifest,
        Optional<DecisionPointId> releaseDecisionPointId,
        Optional<ContentHash> releaseSubjectHash,
        Optional<String> releaseActionRunId,
        Optional<String> stableCode,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public ReferenceAssembly {
        Objects.requireNonNull(referenceAssemblyId, "referenceAssemblyId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(consumerContract, "consumerContract");
        Objects.requireNonNull(hostFixture, "hostFixture");
        Objects.requireNonNull(policySnapshot, "policySnapshot");
        Objects.requireNonNull(componentCertificationId, "componentCertificationId");
        Objects.requireNonNull(componentCandidateHash, "componentCandidateHash");
        Objects.requireNonNull(componentCertificationManifest, "componentCertificationManifest");
        Objects.requireNonNull(status, "status");
        assemblyManifest = requireOptionalLock(assemblyManifest, "assemblyManifest");
        inspectionReport = requireOptionalLock(inspectionReport, "inspectionReport");
        releaseManifest = requireOptionalLock(releaseManifest, "releaseManifest");
        releaseDecisionPointId = Objects.requireNonNull(
                releaseDecisionPointId, "releaseDecisionPointId");
        releaseSubjectHash = Objects.requireNonNull(releaseSubjectHash, "releaseSubjectHash");
        releaseActionRunId = requireOptionalText(
                releaseActionRunId, "releaseActionRunId", 64);
        stableCode = requireOptionalStableCode(stableCode);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        createdAt = requireCanonicalInstant(createdAt, "createdAt");
        updatedAt = requireCanonicalInstant(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        validateLifecycle(
                status,
                assemblyManifest,
                inspectionReport,
                releaseManifest,
                releaseDecisionPointId,
                releaseSubjectHash,
                releaseActionRunId,
                stableCode);
    }

    public static ReferenceAssembly requested(
            ReferenceAssemblyId referenceAssemblyId,
            TenantId tenantId,
            BuildSessionId buildSessionId,
            CertificationArtifactLock requirement,
            CertificationArtifactLock consumerContract,
            CertificationArtifactLock hostFixture,
            CertificationArtifactLock policySnapshot,
            CertificationId componentCertificationId,
            ContentHash componentCandidateHash,
            CertificationArtifactLock componentCertificationManifest,
            Instant createdAt) {
        return new ReferenceAssembly(
                referenceAssemblyId,
                tenantId,
                buildSessionId,
                requirement,
                consumerContract,
                hostFixture,
                policySnapshot,
                componentCertificationId,
                componentCandidateHash,
                componentCertificationManifest,
                ReferenceAssemblyStatus.REQUESTED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                createdAt,
                createdAt);
    }

    public ReferenceAssembly resolveComponent(Instant resolvedAt) {
        requireTransitionFrom(ReferenceAssemblyStatus.REQUESTED, resolvedAt, "resolve component");
        return copy(
                ReferenceAssemblyStatus.COMPONENT_RESOLVED,
                assemblyManifest,
                inspectionReport,
                releaseManifest,
                releaseDecisionPointId,
                releaseSubjectHash,
                releaseActionRunId,
                stableCode,
                resolvedAt);
    }

    public ReferenceAssembly assemble(
            CertificationArtifactLock manifest, Instant assembledAt) {
        Objects.requireNonNull(manifest, "manifest");
        requireTransitionFrom(
                ReferenceAssemblyStatus.COMPONENT_RESOLVED, assembledAt, "assemble");
        return copy(
                ReferenceAssemblyStatus.ASSEMBLED,
                Optional.of(manifest),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                assembledAt);
    }

    public ReferenceAssembly inspect(
            CertificationArtifactLock report, Instant inspectedAt) {
        Objects.requireNonNull(report, "report");
        requireTransitionFrom(ReferenceAssemblyStatus.ASSEMBLED, inspectedAt, "inspect");
        return copy(
                ReferenceAssemblyStatus.INSPECTED,
                assemblyManifest,
                Optional.of(report),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                inspectedAt);
    }

    /** Durably binds the exact release-review subject while remaining INSPECTED. */
    public ReferenceAssembly bindReleaseReview(
            DecisionPointId decisionPointId, ContentHash subjectHash, Instant boundAt) {
        Objects.requireNonNull(decisionPointId, "decisionPointId");
        Objects.requireNonNull(subjectHash, "subjectHash");
        if (status != ReferenceAssemblyStatus.INSPECTED
                || releaseDecisionPointId.isPresent()
                || releaseSubjectHash.isPresent()
                || releaseActionRunId.isPresent()) {
            throw new IllegalStateException(
                    "release review can bind only once to an unbound INSPECTED ReferenceAssembly");
        }
        requireMonotonic(boundAt, "boundAt");
        return copy(
                ReferenceAssemblyStatus.INSPECTED,
                assemblyManifest,
                inspectionReport,
                Optional.empty(),
                Optional.of(decisionPointId),
                Optional.of(subjectHash),
                Optional.empty(),
                Optional.empty(),
                boundAt);
    }

    /** Durably binds the release Action owner after the exact review subject is known. */
    public ReferenceAssembly bindReleaseAction(String owningActionRunId, Instant boundAt) {
        owningActionRunId = requireText(owningActionRunId, "owningActionRunId", 64);
        if (status != ReferenceAssemblyStatus.INSPECTED
                || releaseDecisionPointId.isEmpty()
                || releaseSubjectHash.isEmpty()
                || releaseActionRunId.isPresent()) {
            throw new IllegalStateException(
                    "release Action can bind only once after release review is bound");
        }
        requireMonotonic(boundAt, "boundAt");
        return copy(
                ReferenceAssemblyStatus.INSPECTED,
                assemblyManifest,
                inspectionReport,
                Optional.empty(),
                releaseDecisionPointId,
                releaseSubjectHash,
                Optional.of(owningActionRunId),
                Optional.empty(),
                boundAt);
    }

    public ReferenceAssembly release(
            CertificationArtifactLock manifest,
            DecisionPointId decisionPointId,
            ContentHash subjectHash,
            String owningActionRunId,
            Instant releasedAt) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(decisionPointId, "decisionPointId");
        Objects.requireNonNull(subjectHash, "subjectHash");
        String exactActionRunId = requireText(owningActionRunId, "owningActionRunId", 64);
        requireTransitionFrom(ReferenceAssemblyStatus.INSPECTED, releasedAt, "release");
        if (releaseDecisionPointId.filter(decisionPointId::equals).isEmpty()
                || releaseSubjectHash.filter(subjectHash::equals).isEmpty()
                || releaseActionRunId.filter(exactActionRunId::equals).isEmpty()) {
            throw new IllegalArgumentException(
                    "release authority must exactly match the durably bound review and Action owner");
        }
        return copy(
                ReferenceAssemblyStatus.RELEASED,
                assemblyManifest,
                inspectionReport,
                Optional.of(manifest),
                releaseDecisionPointId,
                releaseSubjectHash,
                releaseActionRunId,
                Optional.empty(),
                releasedAt);
    }

    /** Rejects at the current non-terminal stage while preserving evidence already produced. */
    public ReferenceAssembly reject(String code, Instant rejectedAt) {
        code = requireStableCode(code);
        requireRejectable(rejectedAt);
        if (releaseActionRunId.isPresent()) {
            throw new IllegalStateException(
                    "an Action-owned ReferenceAssembly requires owner reconciliation before rejection");
        }
        return copy(
                ReferenceAssemblyStatus.REJECTED,
                assemblyManifest,
                inspectionReport,
                Optional.empty(),
                releaseDecisionPointId,
                releaseSubjectHash,
                releaseActionRunId,
                Optional.of(code),
                rejectedAt);
    }

    /** Records a failed independent inspection and rejects in one immutable transition. */
    public ReferenceAssembly rejectAfterInspection(
            CertificationArtifactLock report, String code, Instant rejectedAt) {
        Objects.requireNonNull(report, "report");
        code = requireStableCode(code);
        if (status != ReferenceAssemblyStatus.ASSEMBLED) {
            throw new IllegalStateException(
                    "only an ASSEMBLED ReferenceAssembly can reject with inspection evidence");
        }
        requireMonotonic(rejectedAt, "rejectedAt");
        return copy(
                ReferenceAssemblyStatus.REJECTED,
                assemblyManifest,
                Optional.of(report),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(code),
                rejectedAt);
    }

    private ReferenceAssembly copy(
            ReferenceAssemblyStatus nextStatus,
            Optional<CertificationArtifactLock> nextAssemblyManifest,
            Optional<CertificationArtifactLock> nextInspectionReport,
            Optional<CertificationArtifactLock> nextReleaseManifest,
            Optional<DecisionPointId> nextReleaseDecisionPointId,
            Optional<ContentHash> nextReleaseSubjectHash,
            Optional<String> nextReleaseActionRunId,
            Optional<String> nextStableCode,
            Instant nextUpdatedAt) {
        return new ReferenceAssembly(
                referenceAssemblyId,
                tenantId,
                buildSessionId,
                requirement,
                consumerContract,
                hostFixture,
                policySnapshot,
                componentCertificationId,
                componentCandidateHash,
                componentCertificationManifest,
                nextStatus,
                nextAssemblyManifest,
                nextInspectionReport,
                nextReleaseManifest,
                nextReleaseDecisionPointId,
                nextReleaseSubjectHash,
                nextReleaseActionRunId,
                nextStableCode,
                version + 1,
                createdAt,
                nextUpdatedAt);
    }

    private void requireTransitionFrom(
            ReferenceAssemblyStatus expectedStatus, Instant transitionAt, String transition) {
        if (status != expectedStatus) {
            throw new IllegalStateException(
                    "only a " + expectedStatus + " ReferenceAssembly can " + transition);
        }
        requireMonotonic(transitionAt, transition + "At");
    }

    private void requireRejectable(Instant rejectedAt) {
        if (status.isTerminal()) {
            throw new IllegalStateException("terminal ReferenceAssembly cannot be rejected");
        }
        requireMonotonic(rejectedAt, "rejectedAt");
    }

    private void requireMonotonic(Instant value, String name) {
        value = requireCanonicalInstant(value, name);
        if (value.isBefore(updatedAt)) {
            throw new IllegalArgumentException(name + " must not be before the last update");
        }
    }

    private static void validateLifecycle(
            ReferenceAssemblyStatus status,
            Optional<CertificationArtifactLock> assemblyManifest,
            Optional<CertificationArtifactLock> inspectionReport,
            Optional<CertificationArtifactLock> releaseManifest,
            Optional<DecisionPointId> releaseDecisionPointId,
            Optional<ContentHash> releaseSubjectHash,
            Optional<String> releaseActionRunId,
            Optional<String> stableCode) {
        boolean reviewPairAbsent = releaseDecisionPointId.isEmpty() && releaseSubjectHash.isEmpty();
        boolean reviewPairPresent = releaseDecisionPointId.isPresent() && releaseSubjectHash.isPresent();
        boolean validStagedAuthority = (reviewPairAbsent && releaseActionRunId.isEmpty())
                || reviewPairPresent;
        switch (status) {
            case REQUESTED, COMPONENT_RESOLVED -> {
                if (assemblyManifest.isPresent()
                        || inspectionReport.isPresent()
                        || releaseManifest.isPresent()
                        || !reviewPairAbsent
                        || releaseActionRunId.isPresent()
                        || stableCode.isPresent()) {
                    throw invalidShape(status);
                }
            }
            case ASSEMBLED -> {
                if (assemblyManifest.isEmpty()
                        || inspectionReport.isPresent()
                        || releaseManifest.isPresent()
                        || !reviewPairAbsent
                        || releaseActionRunId.isPresent()
                        || stableCode.isPresent()) {
                    throw invalidShape(status);
                }
            }
            case INSPECTED -> {
                if (assemblyManifest.isEmpty()
                        || inspectionReport.isEmpty()
                        || releaseManifest.isPresent()
                        || !validStagedAuthority
                        || stableCode.isPresent()) {
                    throw invalidShape(status);
                }
            }
            case RELEASED -> {
                if (assemblyManifest.isEmpty()
                        || inspectionReport.isEmpty()
                        || releaseManifest.isEmpty()
                        || releaseDecisionPointId.isEmpty()
                        || releaseSubjectHash.isEmpty()
                        || releaseActionRunId.isEmpty()
                        || stableCode.isPresent()) {
                    throw invalidShape(status);
                }
            }
            case REJECTED -> {
                if (stableCode.isEmpty()
                        || releaseManifest.isPresent()
                        || !validStagedAuthority
                        || (inspectionReport.isPresent() && assemblyManifest.isEmpty())) {
                    throw invalidShape(status);
                }
            }
        }
    }

    private static IllegalArgumentException invalidShape(ReferenceAssemblyStatus status) {
        return new IllegalArgumentException(status + " ReferenceAssembly has an invalid lifecycle shape");
    }

    private static Optional<CertificationArtifactLock> requireOptionalLock(
            Optional<CertificationArtifactLock> value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private static Optional<String> requireOptionalText(
            Optional<String> value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        value.ifPresent(text -> requireText(text, name, maximumLength));
        return value;
    }

    private static Optional<String> requireOptionalStableCode(Optional<String> value) {
        Objects.requireNonNull(value, "stableCode");
        value.ifPresent(ReferenceAssembly::requireStableCode);
        return value;
    }

    private static String requireStableCode(String value) {
        value = requireText(value, "stableCode", 128);
        if (!value.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("stableCode must be a bounded uppercase stable code");
        }
        return value;
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
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
