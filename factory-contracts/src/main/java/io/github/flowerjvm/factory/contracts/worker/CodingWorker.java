package io.github.flowerjvm.factory.contracts.worker;

/** Non-blocking control port for an external or local coding worker. */
public interface CodingWorker {
    WorkerCapabilities capabilities();

    WorkerSubmission submit(WorkerDispatchRequest request);

    WorkerStatusObservation status(WorkerStatusRequest request);

    WorkerCancelResult cancel(WorkerCancelRequest request);
}
