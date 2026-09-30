package io.github.flowerjvm.factory.application.build;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Version-CAS repository SPI. No unconditional update or upsert is exposed. */
public interface BuildSessionRepository {
    void create(BuildSession session);

    Optional<BuildSession> find(TenantId tenantId, BuildSessionId buildSessionId);

    /** Bounded, oldest-first restart scan driven by the durable cancellation authority. */
    default List<BuildSession> findCancelling(Instant updatedBefore, int limit) {
        throw new UnsupportedOperationException("cancelling BuildSession query is not implemented");
    }

    /**
     * Bounded, oldest-first restart scan for Agent Pack certification continuations that may not
     * yet have produced their first durable Flower checkpoint.
     */
    default List<BuildSession> findCertificationContinuationCandidates(
            Instant updatedBefore, int limit) {
        throw new UnsupportedOperationException(
                "certification continuation BuildSession query is not implemented");
    }

    /**
     * Bounded, oldest-first restart scan for active Reference Assembly primary flows, including
     * the domain-before-first-checkpoint crash window.
     */
    default List<BuildSession> findReferenceAssemblyFlowCandidates(
            Instant updatedBefore, int limit) {
        throw new UnsupportedOperationException(
                "Reference Assembly Flow candidate query is not implemented");
    }

    boolean compareAndSet(BuildSession expected, BuildSession next);
}
