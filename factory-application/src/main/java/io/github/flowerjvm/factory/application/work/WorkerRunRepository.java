package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Version-CAS repository SPI. No unconditional update or upsert is exposed. */
public interface WorkerRunRepository {
    void create(WorkerRunRecord workerRun);

    Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId);

    /** Latest durable attempt for an immutable WorkOrder, including its terminal result. */
    default Optional<WorkerRunRecord> findLatestByWorkOrder(TenantId tenantId, WorkOrderId workOrderId) {
        throw new UnsupportedOperationException("WorkOrder query is not implemented by this repository");
    }

    /**
     * Bounded restart scan for accepted external work whose callback may have been lost.
     * Implementations include DISPATCHING, WAITING_EXTERNAL, and CANCEL_REQUESTED rows observed no
     * later than {@code updatedBefore}. DISPATCHING is required so startup cancellation recovery can
     * restage a cancel hook that failed before commit after ActionRun CANCELLED became canonical;
     * callers never redispatch any returned row by age.
     */
    default List<WorkerRunRecord> findActiveForReconciliation(Instant updatedBefore, int limit) {
        throw new UnsupportedOperationException("active reconciliation query is not implemented");
    }

    /**
     * Bounded oldest-first status-poll query containing only accepted WAITING_EXTERNAL work.
     * Keeping this separate prevents pre-acceptance/cancellation recovery rows from starving
     * external status observation at a finite scan limit.
     */
    default List<WorkerRunRecord> findWaitingExternalForStatusPoll(
            Instant updatedBefore, int limit) {
        throw new UnsupportedOperationException("Worker status-poll query is not implemented");
    }

    /**
     * Bounded, oldest-first recovery query that cannot be starved by ordinary running work.
     * Implementations return only active exact owners for which either the canonical ActionRun is
     * CANCELLED or the owning BuildSession is CANCELLING. The query must tenant-bind the WorkerRun,
     * ActionRun, WorkOrder, and BuildSession join and include DISPATCHING, WAITING_EXTERNAL,
     * CANCEL_REQUESTED, and RECONCILING states.
     */
    default List<WorkerRunRecord> findCancellationRecoveryCandidates(
            Instant updatedBefore, int limit) {
        throw new UnsupportedOperationException(
                "cancellation recovery candidate query is not implemented");
    }

    default Optional<WorkerRunRecord> findActiveByBuildSession(
            TenantId tenantId, io.github.flowerjvm.factory.contracts.ids.BuildSessionId buildSessionId) {
        throw new UnsupportedOperationException("active BuildSession query is not implemented");
    }

    boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next);
}
