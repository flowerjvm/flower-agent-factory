package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.SynchronousActionExecutor;
import java.util.Map;
import java.util.Objects;

/** Bounded input reads and atomic acceptance on a host control caller; never a Flower tick. */
public final class AgentPackProductionIntakeActionExecutor implements SynchronousActionExecutor {
    private final AgentPackProductionIntakeService service;
    public AgentPackProductionIntakeActionExecutor(AgentPackProductionIntakeService service) {
        this.service = Objects.requireNonNull(service, "service");
    }
    @Override public ActionDefinition definition() { return AgentPackProductionIntakeAction.definition(); }
    @Override public ActionExecutionResult execute(ActionExecutionContext context) {
        try {
            var input = AgentPackProductionIntakeInput.from(context.input());
            var session = service.accept(new TenantId(context.executionContext().tenantId()),
                    context.executionContext().userId(), context.proposal().idempotencyKey(), input);
            AgentPackProductionIntakeAuthority.requireExact(session, new TenantId(context.executionContext().tenantId()),
                    context.executionContext().userId(), context.proposal().idempotencyKey(), input);
            return new ActionExecutionResult(ActionExecutionStatus.SUCCEEDED, "PRODUCTION_ORDER_ACCEPTED",
                    "Production order accepted; this result is not a product approval or release",
                    Map.of("buildSessionId", session.buildSessionId().value(), "projectId", session.projectId().value()),
                    RetryDisposition.NEVER);
        } catch (RuntimeException uncertain) {
            return ActionExecutionResult.manualReviewFailure("PRODUCTION_INTAKE_UNCERTAIN", "Production intake requires reconciliation");
        }
    }
}
