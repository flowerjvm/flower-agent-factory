package io.github.flowerjvm.factory.application.work;

/** Stable callback result; duplicates observe durable truth without another transition. */
public enum WorkerCompletionDisposition {
    APPLIED,
    DUPLICATE,
    STALE_OR_CONFLICT
}
