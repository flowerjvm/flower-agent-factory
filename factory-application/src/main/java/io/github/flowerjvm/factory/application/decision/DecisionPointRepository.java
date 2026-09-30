package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import java.util.Optional;

/** Version-CAS repository SPI. No unconditional update or upsert is exposed. */
public interface DecisionPointRepository {
    void create(DecisionPoint decisionPoint);

    Optional<DecisionPoint> find(TenantId tenantId, DecisionPointId decisionPointId);

    /** Latest durable decision point of a current Factory type for this BuildSession. */
    default Optional<DecisionPoint> findLatestByBuildSessionAndType(
            TenantId tenantId, BuildSessionId buildSessionId, String type) {
        throw new UnsupportedOperationException("BuildSession/type query is not implemented by this repository");
    }

    /** Latest hash-bound decision of the exact type for the exact current subject. */
    default Optional<DecisionPoint> findLatestByBuildSessionAndSubject(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            String type,
            String subjectType,
            String subjectId,
            ContentHash subjectHash) {
        throw new UnsupportedOperationException("BuildSession/subject query is not implemented by this repository");
    }

    boolean compareAndSet(DecisionPoint expected, DecisionPoint next);
}
