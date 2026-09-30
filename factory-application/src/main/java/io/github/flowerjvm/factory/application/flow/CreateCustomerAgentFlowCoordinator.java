package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildPhase;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;

/**
 * Re-reads durable Factory ledgers and decides one non-blocking Flow tick.
 *
 * <p>Implementations may create an idempotent dispatch proposal through the registered
 * {@code factory.worker.dispatch} Action, but must never call a worker, verifier, nested Flower
 * tick or blocking API here. Signals are wake-up hints only; every result must be derived from the
 * persisted BuildSession, WorkerRun, VerificationRun or Decision records.
 */
@FunctionalInterface
public interface CreateCustomerAgentFlowCoordinator {
    StepResult advance(BuildPhase phase, StepContext context);
}
