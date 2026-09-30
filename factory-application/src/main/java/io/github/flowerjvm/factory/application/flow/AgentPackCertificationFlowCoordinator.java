package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;

/** Decides one bounded, ledger-derived tick of the Agent Pack certification continuation. */
@FunctionalInterface
public interface AgentPackCertificationFlowCoordinator {
    StepResult advance(BuildSessionId buildSessionId, StepContext context);
}
