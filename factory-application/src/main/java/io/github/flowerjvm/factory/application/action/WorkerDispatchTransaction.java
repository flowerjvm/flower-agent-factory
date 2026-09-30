package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;

/** Atomic domain boundary for WorkerRun CAS plus dispatch-outbox insertion. */
@FunctionalInterface
public interface WorkerDispatchTransaction {
    boolean prepare(
            WorkOrder workOrder,
            WorkerRunRecord expectedWorkerRun,
            WorkerRunRecord dispatchingWorkerRun,
            DispatchOutbox outbox);
}
