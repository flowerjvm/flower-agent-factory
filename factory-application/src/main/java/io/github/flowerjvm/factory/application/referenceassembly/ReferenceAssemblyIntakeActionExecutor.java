package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.SynchronousActionExecutor;
import java.util.Map;
import java.util.Objects;

/** Full evidence reads and atomic acceptance are host-caller work, never Flower-tick work. */
public final class ReferenceAssemblyIntakeActionExecutor implements SynchronousActionExecutor {
    private final ReferenceAssemblyIntakeService service;
    private final ReferenceAssemblyIntakeAdmission admission;
    public ReferenceAssemblyIntakeActionExecutor(ReferenceAssemblyIntakeService service, ReferenceAssemblyIntakeAdmission admission) {
        this.service = Objects.requireNonNull(service); this.admission = Objects.requireNonNull(admission);
    }
    @Override public ActionDefinition definition() { return ReferenceAssemblyIntakeAction.definition(); }
    @Override public ActionExecutionResult execute(ActionExecutionContext context) {
        try {
            var input = admission.current(context.proposal(), context.executionContext());
            var session = service.accept(new TenantId(context.executionContext().tenantId()), context.executionContext().userId(),
                    context.proposal().idempotencyKey(), input);
            return new ActionExecutionResult(ActionExecutionStatus.SUCCEEDED, "REFERENCE_ASSEMBLY_ORDER_ACCEPTED",
                    "Reference Assembly order accepted; this is not product approval or release",
                    Map.of("buildSessionId", session.buildSessionId().value(), "projectId", session.projectId().value()), RetryDisposition.NEVER);
        } catch (RuntimeException uncertain) {
            return ActionExecutionResult.manualReviewFailure("REFERENCE_ASSEMBLY_INTAKE_UNCERTAIN", "Reference Assembly intake requires reconciliation");
        }
    }
}
