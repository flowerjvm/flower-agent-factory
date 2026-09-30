package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact inspected-ledger authority for the Reference Assembly human release review. */
public final class ReferenceAssemblyReleaseDecisionSubjectAuthority
        implements DecisionSubjectAuthority {
    private final ReferenceAssemblyRepository referenceAssemblies;

    public ReferenceAssemblyReleaseDecisionSubjectAuthority(
            ReferenceAssemblyRepository referenceAssemblies) {
        this.referenceAssemblies = Objects.requireNonNull(
                referenceAssemblies, "referenceAssemblies");
    }

    @Override
    public String decisionType() {
        return ReferenceAssemblyReleaseReviewService.DECISION_TYPE;
    }

    @Override
    public void validateCurrentSubject(
            TenantId trustedTenantId,
            BuildSession currentBuildSession,
            DecisionPoint decisionPoint) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(currentBuildSession, "currentBuildSession");
        Objects.requireNonNull(decisionPoint, "decisionPoint");
        ReferenceAssembly assembly = findAssembly(trustedTenantId, decisionPoint.subjectId());
        CertificationArtifactLock sessionRequirement = new CertificationArtifactLock(
                currentBuildSession.requirementsArtifactRef(),
                currentBuildSession.requirementsHash());
        boolean exactSubjectVersion = decisionPoint.subjectVersion() < Long.MAX_VALUE
                && assembly.version() == decisionPoint.subjectVersion() + 1;
        boolean exactAuthority = decisionPoint.tenantId().equals(trustedTenantId)
                && assembly.tenantId().equals(trustedTenantId)
                && currentBuildSession.tenantId().equals(trustedTenantId)
                && decisionPoint.buildSessionId().equals(currentBuildSession.buildSessionId())
                && assembly.buildSessionId().equals(currentBuildSession.buildSessionId())
                && ProductLineId.REFERENCE_ASSEMBLY.equals(currentBuildSession.productLineId())
                && decisionType().equals(decisionPoint.type())
                && ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE.equals(
                        decisionPoint.subjectType())
                && assembly.referenceAssemblyId().value().equals(decisionPoint.subjectId())
                && assembly.status() == ReferenceAssemblyStatus.INSPECTED
                && assembly.releaseDecisionPointId()
                        .filter(decisionPoint.decisionPointId()::equals)
                        .isPresent()
                && assembly.releaseSubjectHash()
                        .filter(decisionPoint.subjectHash()::equals)
                        .isPresent()
                && assembly.releaseActionRunId().isEmpty()
                && exactSubjectVersion
                && ReferenceAssemblyReleaseReviewService.OPTIONS_SCHEMA_ID.equals(
                        decisionPoint.optionsSchemaId())
                && decisionPoint.requiredPermissions().equals(
                        Set.of(ReferenceAssemblyReleaseReviewService.REQUIRED_PERMISSION))
                && decisionPoint.minimumApprovers() == 1
                && decisionPoint.policySnapshotRef().equals(
                        assembly.policySnapshot().reference())
                && sessionRequirement.equals(assembly.requirement())
                && currentBuildSession.status() == BuildSessionStatus.WAITING_RELEASE_REVIEW
                && currentBuildSession.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW
                && currentBuildSession.cancellationRequestedAt().isEmpty()
                && decisionPoint.dueAt().equals(currentBuildSession.deadlineAt());
        if (!exactAuthority) {
            throw subjectChanged();
        }
    }

    private ReferenceAssembly findAssembly(TenantId trustedTenantId, String subjectId) {
        try {
            ReferenceAssemblyId referenceAssemblyId = new ReferenceAssemblyId(subjectId);
            Optional<ReferenceAssembly> found =
                    referenceAssemblies.find(trustedTenantId, referenceAssemblyId);
            return found.orElseThrow(ReferenceAssemblyReleaseDecisionSubjectAuthority::subjectChanged);
        } catch (RuntimeException unavailableOrInvalid) {
            if (unavailableOrInvalid instanceof IllegalArgumentException illegal
                    && "DECISION_SUBJECT_CHANGED".equals(illegal.getMessage())) {
                throw illegal;
            }
            throw new IllegalArgumentException(
                    "DECISION_SUBJECT_CHANGED", unavailableOrInvalid);
        }
    }

    private static IllegalArgumentException subjectChanged() {
        return new IllegalArgumentException("DECISION_SUBJECT_CHANGED");
    }
}
