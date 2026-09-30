package io.github.flowerjvm.factory.contracts.worker;

/** Stable machine guidance for a new, explicitly governed Worker attempt. */
public enum WorkerRetryDisposition {
    AFTER_BACKOFF,
    AFTER_CORRECTION,
    NEVER,
    MANUAL_REVIEW
}
