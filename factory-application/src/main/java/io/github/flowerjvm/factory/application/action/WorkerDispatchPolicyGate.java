package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import java.util.Collection;
import java.util.Objects;

/** Enforces the permission and canonical WorkOrder boundary omitted by the runtime default gate. */
public final class WorkerDispatchPolicyGate implements PolicyGate {
    private final WorkOrderRepository workOrders;
    private final WorkerRunRepository workerRuns;
    private final PolicyGate baseline;

    public WorkerDispatchPolicyGate(
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns) {
        this(workOrders, workerRuns, new DefaultPolicyGate());
    }

    WorkerDispatchPolicyGate(
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            PolicyGate baseline) {
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.baseline = Objects.requireNonNull(baseline, "baseline");
    }

    @Override
    public PolicyDecision evaluate(
            ActionProposal proposal,
            ActionDefinition definition,
            ExecutionContext context) {
        PolicyDecision baselineDecision = baseline.evaluate(proposal, definition, context);
        if (!baselineDecision.allowedToExecuteNow()) {
            return baselineDecision;
        }
        if (context.tenantId().isBlank() || context.userId().isBlank()) {
            return PolicyDecision.deny("trusted tenant and execution principal are required");
        }
        if (!hasPermission(context.metadata().get("actor.permissions"), WorkerDispatchAction.PERMISSION)) {
            return PolicyDecision.deny("execution principal lacks " + WorkerDispatchAction.PERMISSION);
        }
        WorkerDispatchInput input;
        try {
            input = WorkerDispatchInput.from(proposal.input());
        } catch (IllegalArgumentException exception) {
            return PolicyDecision.deny("worker dispatch input is invalid");
        }
        Object resourceType = context.metadata().get("resource.type");
        Object resourceId = context.metadata().get("resource.id");
        if (!WorkerDispatchAction.RESOURCE_TYPE.equals(resourceType)
                || !(resourceId instanceof String id)
                || !input.workOrderId().value().equals(id)) {
            return PolicyDecision.deny("trusted WorkOrder resource scope does not match the request");
        }
        TenantId tenantId = new TenantId(context.tenantId());
        var workOrder = workOrders.find(tenantId, input.workOrderId()).orElse(null);
        if (workOrder == null) {
            return PolicyDecision.deny("WorkOrder is not visible in the trusted tenant scope");
        }
        var workerRun = workerRuns.find(tenantId, input.workerRunId()).orElse(null);
        if (workerRun == null
                || !workerRun.workOrderId().equals(workOrder.workOrderId())
                || !workerRun.buildSessionId().equals(workOrder.buildSessionId())) {
            return PolicyDecision.deny("WorkerRun is not visible in the trusted WorkOrder scope");
        }
        if (workerRun.status() == WorkerRunStatus.REQUESTED
                && (workerRun.version() != input.expectedWorkerRunVersion()
                        || workerRun.actionRunId().isPresent()
                        || workerRun.attemptTokenHash().isPresent()
                        || workerRun.dispatchOutboxId().isPresent()
                        || workerRun.startedAt().isPresent())) {
            return PolicyDecision.deny("REQUESTED WorkerRun version or ownership state is stale");
        }
        if (!WorkerDispatchIdempotencyKeys.derive(workOrder, workerRun).equals(proposal.idempotencyKey())) {
            return PolicyDecision.deny("idempotency key is not bound to this logical Worker attempt");
        }
        return PolicyDecision.allow();
    }

    private static boolean hasPermission(Object value, String required) {
        if (!(value instanceof Collection<?> permissions)) {
            return false;
        }
        return permissions.stream().anyMatch(required::equals);
    }
}
