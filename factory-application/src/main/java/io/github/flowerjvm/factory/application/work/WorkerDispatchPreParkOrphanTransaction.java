package io.github.flowerjvm.factory.application.work;

import java.time.Duration;
import java.time.Instant;

/**
 * Durable recovery boundary for a deferred Action that never completed its RUNNING park.
 *
 * <p>The implementation examines at most one bounded candidate. Before {@code grace} elapses from
 * the newest trusted Action/Worker/outbox update it performs no mutation, allowing the original
 * Action pipeline to win its normal WAITING_EXTERNAL CAS. At or after the boundary it may return
 * true only after atomically proving all of the following: exact tenant/action/input/attempt/run/
 * operation/outbox ownership; Action RUNNING at {@code execute-action} with no external operation;
 * WorkerRun DISPATCHING; an unclaimed PENDING WORKER_DISPATCH outbox; and either a non-cancelling
 * RUNNING/REPAIRING BuildSession or a CANCELLING BuildSession with durable cancellation authority,
 * at the exact WorkOrder phase/binding/candidate/deadlines.
 *
 * <p>For an ordinary session the only permitted recovery mutation is outbox MANUAL_REVIEW plus
 * BuildSession MANUAL_REVIEW with {@link WorkerDispatchPublisher#ACTION_PARK_ORPHANED}. For a
 * CANCELLING session it is outbox MANUAL_REVIEW plus {@code manualReviewCancellation} with
 * {@link WorkerDispatchPublisher#ACTION_PARK_CANCEL_ORPHANED}. WorkerRun and ActionRun remain
 * unchanged, and no Coding Worker method may be invoked. Action Runtime 0.3.3 has no governed
 * RUNNING-resume API; adapters must never patch ActionRun directly or use RunStore CAS to invent a
 * deferred transition.
 */
@FunctionalInterface
public interface WorkerDispatchPreParkOrphanTransaction {
    boolean reconcileNext(Instant observedAt, Duration grace);
}
