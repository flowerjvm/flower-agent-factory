package io.github.flowerjvm.factory.contracts.worker;

/** Adapter assertion that the exact owner tuple has no external operation and therefore no effect. */
public final class WorkerOperationNotFoundException extends RuntimeException {
    public WorkerOperationNotFoundException() {
        super("CODING_WORKER_OPERATION_NOT_FOUND");
    }
}
