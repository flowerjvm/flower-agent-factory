package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.contracts.ids.TenantId;

/** ProductLine-specific authority for one durable DecisionPoint type and its current subject. */
public interface DecisionSubjectAuthority {
    /** Exact stable DecisionPoint type exclusively owned by this authority. */
    String decisionType();

    /** Original deadline unless a concrete line proves an independently governed immutable review window. */
    default java.time.Instant decisionDeadline(BuildSession session, DecisionPoint point) {
        return session.deadlineAt();
    }

    /**
     * Fails closed unless the tenant-scoped durable subject still exactly authorizes this point.
     */
    void validateCurrentSubject(
            TenantId trustedTenantId,
            BuildSession currentBuildSession,
            DecisionPoint decisionPoint);
}
