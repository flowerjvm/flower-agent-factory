package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;

/** ProductLine-owned cancellation boundary for effects that are not represented by WorkerRun. */
public interface FactoryProductLineCancellationRequester {
    ProductLineId productLineId();

    /**
     * Performs a read-only preflight before the BuildSession cancellation CAS.
     *
     * <p>A {@code false} result fails closed without mutating the BuildSession. Implementations
     * must revalidate the same authority in {@link #request(BuildSession)} because the preflight
     * is not a lock.
     */
    boolean canRequest(BuildSession session);

    /** Requests cancellation after the supplied canonical session durably entered CANCELLING. */
    FactoryCancellationRequestDisposition request(BuildSession cancellingSession);
}
