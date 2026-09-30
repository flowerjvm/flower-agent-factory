package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.time.Instant;

/**
 * Atomically projects a remote cancel outcome to the cancel outbox, WorkerRun, and exact owning
 * BuildSession. Implementations must revalidate that the WorkerRun is still the session's active
 * cancellation owner.
 */
public interface WorkerCancellationOutcomeTransaction {
    boolean confirm(
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant confirmedAt);

    /**
     * Confirms authoritative Worker NOT_FOUND only while atomically proving the matching dispatch
     * intent never crossed its external-submit start boundary. Implementations must lock and
     * revalidate both outboxes: the dispatch row is either unclaimed PENDING or was terminalized
     * with {@link WorkerDispatchPublisher#CANCELLED_BEFORE_SUBMIT}, and the exact cancel row remains
     * owned by {@code claimToken}. A previously claimed/expired/RETRY_WAIT dispatch is uncertain and
     * must return false.
     */
    default boolean confirmAbsentAfterProvenNoDispatch(
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId cancelOutboxId,
            DispatchOutboxId dispatchOutboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant confirmedAt) {
        return false;
    }

    boolean manualReview(
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant observedAt);

    /**
     * Atomically closes a cancellation that lost to an already-projected canonical completion.
     * Implementations must re-read an exact terminal WorkerRun owned by the CANCELLING session,
     * leave that immutable terminal result unchanged, transition only the claimed WORKER_CANCEL
     * outbox to SUPERSEDED, and confirm the session cancellation because no remote effect remains.
     */
    boolean supersedeAfterTerminalCompletion(
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant observedAt);
}
