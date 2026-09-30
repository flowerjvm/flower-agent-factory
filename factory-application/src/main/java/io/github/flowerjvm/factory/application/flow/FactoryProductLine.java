package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.flower.core.flow.Flow;
import io.github.flowerjvm.flower.core.recovery.FlowFactory;

/**
 * One specialized Factory production line and its durable Flower recovery boundary.
 *
 * <p>Product-specific verification, assembly, and business rules stay in the implementation. This
 * contract only supplies stable routing and Flow construction.
 */
public interface FactoryProductLine extends FlowFactory {
    ProductLineId productLineId();

    String flowType();

    /**
     * Whether this line's durable state requires a separately installed continuation to own
     * control. The registry must not fall back to the primary Flow when that owner is missing.
     * Lines whose primary Flow owns the full lifecycle keep the default.
     */
    default boolean requiresContinuation(BuildSession session) {
        return false;
    }

    /**
     * Whether this line owns governed effects outside the generic Worker protocol and therefore
     * must install its own cancellation requester. Missing ownership wiring then fails closed
     * before the BuildSession is mutated.
     */
    default boolean requiresProductLineCancellationRequester() {
        return false;
    }

    Flow create(BuildSession session, String flowRunId, String traceId);
}
