package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.time.Instant;

/**
 * Atomically records external dispatch acceptance in the outbox and WorkerRun ledgers.
 *
 * <p>A normal DISPATCHING run becomes WAITING_EXTERNAL. If canonical Action cancellation already
 * moved the run to CANCEL_REQUESTED, the run remains cancellation-requested while the dispatch
 * outbox becomes DISPATCHED; this preserves proof that the late external effect must still be
 * cancelled.
 */
@FunctionalInterface
public interface WorkerDispatchAcceptanceTransaction {
    boolean accept(
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String externalSessionRef,
            Instant acceptedAt,
            Instant recordedAt);
}
