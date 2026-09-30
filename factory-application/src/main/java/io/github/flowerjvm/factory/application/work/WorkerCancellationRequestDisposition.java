package io.github.flowerjvm.factory.application.work;

/** Durable outcome of asking the owning dispatch Action to begin cooperative cancellation. */
public enum WorkerCancellationRequestDisposition {
    CANCEL_OUTBOX_STAGED,
    NO_ACTIVE_EXTERNAL_EFFECT,
    CONFLICT
}
