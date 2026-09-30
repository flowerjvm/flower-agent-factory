package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.Objects;
import java.util.Set;

/** Current-candidate authority for the Agent Pack human release review. */
public final class AgentPackReleaseReviewDecisionSubjectAuthority
        implements DecisionSubjectAuthority {
    public static final String SUBJECT_TYPE = "CANDIDATE";

    @Override
    public String decisionType() {
        return DecisionPoint.RELEASE_REVIEW_TYPE;
    }

    @Override
    public void validateCurrentSubject(
            TenantId trustedTenantId,
            BuildSession currentBuildSession,
            DecisionPoint decisionPoint) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(currentBuildSession, "currentBuildSession");
        Objects.requireNonNull(decisionPoint, "decisionPoint");
        boolean sameAuthority = decisionPoint.tenantId().equals(trustedTenantId)
                && currentBuildSession.tenantId().equals(trustedTenantId)
                && decisionPoint.buildSessionId().equals(currentBuildSession.buildSessionId())
                && ProductLineId.AGENT_PACK.equals(currentBuildSession.productLineId())
                && decisionType().equals(decisionPoint.type())
                && SUBJECT_TYPE.equals(decisionPoint.subjectType());
        boolean sameCandidate = currentBuildSession.currentCandidateId()
                        .map(candidateId -> candidateId.value().equals(decisionPoint.subjectId()))
                        .orElse(false)
                && currentBuildSession.currentCandidateHash()
                        .map(decisionPoint.subjectHash()::equals)
                        .orElse(false);
        boolean releaseReviewState =
                currentBuildSession.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW
                        && currentBuildSession.status() == BuildSessionStatus.WAITING_RELEASE_REVIEW
                        && currentBuildSession.cancellationRequestedAt().isEmpty()
                        && currentBuildSession.currentCertificationId().isEmpty()
                        && !decisionPoint.dueAt().isAfter(currentBuildSession.deadlineAt())
                        && decisionPoint.optionsSchemaId().equals(AgentPackReleaseReviewPolicy.OPTIONS_SCHEMA_ID)
                        && decisionPoint.requiredPermissions().equals(Set.of(AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION))
                        && decisionPoint.minimumApprovers() == 1;
        if (!sameAuthority || !sameCandidate || !releaseReviewState) {
            throw new IllegalArgumentException("DECISION_SUBJECT_CHANGED");
        }
    }
}
