package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Atomically claims the next fresh Worker dispatch at its external-effect start boundary.
 *
 * <p>The implementation locks the exact outbox, WorkerRun, WorkOrder, BuildSession, and repair
 * candidate when present. It may return a claimed DISPATCHING outbox only if the Worker owner is
 * still DISPATCHING, the session is RUNNING/REPAIRING at the WorkOrder phase with the selected
 * binding and current candidate/hash, cancellation is absent, and {@code now} is strictly before
 * every persisted session/order/run deadline. A loser is terminalized without returning an
 * externally launchable claim. This claim is the serialization point against cancellation.
 *
 * <p>A deferred executor may crash after atomically committing WorkerRun DISPATCHING plus the
 * PENDING outbox but before Action Runtime persists RUNNING to WAITING_EXTERNAL. Action Runtime
 * 0.3.3 exposes no governed RUNNING-resume transition, so neither this port nor its adapter may
 * mutate ActionRun directly (including through RunStore CAS). An exact RUNNING pre-park candidate
 * is not eligible for a fresh claim. {@link WorkerDispatchPreParkOrphanTransaction} owns its
 * bounded grace and durable manual-review convergence. If the original Action pipeline parks first,
 * this transaction may observe the canonical WAITING_EXTERNAL owner and proceed normally. No
 * Coding Worker call is allowed for the RUNNING residue.
 */
@FunctionalInterface
public interface WorkerDispatchStartTransaction {
    Optional<DispatchOutbox> claimNext(Instant now, Duration lease, String claimToken);
}
