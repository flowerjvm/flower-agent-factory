package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.SynchronousActionExecutor;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Exactly one controlled call to bounded domain preparation; no external process or model work. */
public final class AgentPackProductionActionExecutor implements SynchronousActionExecutor {
    private final AgentPackProductionPreparer preparer;
    public AgentPackProductionActionExecutor(AgentPackProductionPreparer preparer) { this.preparer = Objects.requireNonNull(preparer, "preparer"); }
    @Override public ActionDefinition definition() { return AgentPackProductionAction.definition(); }

    @Override
    public ActionExecutionResult execute(ActionExecutionContext context) {
        final AgentPackProductionInput input;
        try {
            input = AgentPackProductionInput.from(context.input());
        } catch (IllegalArgumentException rejected) {
            return ActionExecutionResult.correctableFailure("PRODUCTION_PREPARATION_REJECTED", "Production input is invalid");
        }
        try {
            ProductionPreparationResult result = Objects.requireNonNull(preparer.prepare(
                    new TenantId(context.executionContext().tenantId()), input.buildSessionId(), input.expectedSessionVersion()));
            var output = new LinkedHashMap<String, Object>();
            output.put("buildSessionId", input.buildSessionId().value());
            result.workOrderId().ifPresent(value -> output.put("workOrderId", value.value()));
            result.workerRunId().ifPresent(value -> output.put("workerRunId", value.value()));
            result.decisionPointId().ifPresent(value -> output.put("decisionPointId", value.value()));
            return new ActionExecutionResult(ActionExecutionStatus.SUCCEEDED, result.code(),
                    "Production phase preparation recorded", output, RetryDisposition.NEVER);
        } catch (RuntimeException uncertain) {
            return ActionExecutionResult.manualReviewFailure("PRODUCTION_PREPARATION_UNCERTAIN", "Production preparation requires reconciliation");
        }
    }
}
