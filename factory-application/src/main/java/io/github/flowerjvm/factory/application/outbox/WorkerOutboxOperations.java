package io.github.flowerjvm.factory.application.outbox;

/** Stable operation types stored in the shared dispatch outbox. */
public final class WorkerOutboxOperations {
    public static final String DISPATCH = "WORKER_DISPATCH";
    public static final String CANCEL = "WORKER_CANCEL";

    private WorkerOutboxOperations() {}
}
