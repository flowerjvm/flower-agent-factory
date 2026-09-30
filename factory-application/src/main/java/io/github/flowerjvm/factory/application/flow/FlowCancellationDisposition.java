package io.github.flowerjvm.factory.application.flow;

/** Outcome of one durable BuildSession-to-Flower cancellation command. */
public enum FlowCancellationDisposition {
    CANCELLATION_REQUESTED,
    ALREADY_REQUESTED,
    CONFLICT
}
