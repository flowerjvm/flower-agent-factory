package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;

/**
 * Performs one bounded durable-ledger observation for a Reference Assembly Flow tick.
 *
 * <p>Implementations must derive their result from persisted ledgers. A tick may perform bounded
 * local database/artifact reads and one deterministic local transition, but it must never wait,
 * poll in a loop, invoke a remote component builder, or run the deferred release worker on the
 * Flower lane.
 */
@FunctionalInterface
public interface ReferenceAssemblyFlowCoordinator {
    StepResult advance(
            ReferenceAssemblyBuildPhase phase,
            BuildSessionId buildSessionId,
            StepContext context);
}
