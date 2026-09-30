package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.flower.core.flow.Flow;
import io.github.flowerjvm.flower.core.recovery.FlowFactory;

/**
 * A durable continuation owned by one production line without becoming another ProductLine.
 *
 * <p>Examples are certification or final packaging after a line's primary candidate-building Flow
 * has completed. The durable BuildSession decides which continuation currently owns control.
 */
public interface FactoryContinuationFlow extends FlowFactory {
    String flowType();

    /** True only when a new continuation may be started from this durable state. */
    boolean owns(BuildSession session);

    /**
     * Durable control-plane ownership used to route cancellation and crash recovery.
     *
     * <p>A continuation may control a CANCELLING or terminal crash-window state without allowing
     * callers to start new work from that state.
     */
    default boolean controls(BuildSession session) {
        return owns(session);
    }

    Flow create(BuildSession session, String flowRunId, String traceId);
}
