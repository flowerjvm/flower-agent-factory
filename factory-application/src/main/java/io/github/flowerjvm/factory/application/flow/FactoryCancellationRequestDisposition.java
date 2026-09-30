package io.github.flowerjvm.factory.application.flow;

/** Durable effect-level outcome of one ProductLine-specific cancellation request. */
public enum FactoryCancellationRequestDisposition {
    /** A governed effect owner accepted cancellation but durable reconciliation is still pending. */
    EFFECT_CANCELLATION_PENDING,

    /** No active ProductLine-owned effect remains, so the BuildSession may close as cancelled. */
    NO_ACTIVE_EFFECT,

    /** Current durable state cannot safely authorize or prove cancellation. */
    CONFLICT
}
