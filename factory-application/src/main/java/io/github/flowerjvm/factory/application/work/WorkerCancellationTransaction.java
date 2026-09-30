package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;

/** Atomically records CANCEL_REQUESTED and its durable WORKER_CANCEL outbox intent. */
@FunctionalInterface
public interface WorkerCancellationTransaction {
    boolean prepare(
            WorkOrder workOrder,
            WorkerRunRecord expected,
            WorkerRunRecord cancelRequested,
            DispatchOutbox cancelOutbox);
}
