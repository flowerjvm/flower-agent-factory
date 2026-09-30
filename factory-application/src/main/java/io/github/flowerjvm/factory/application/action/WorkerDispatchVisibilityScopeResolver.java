package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import java.util.Objects;

/** Resolves duplicate result visibility from a repository-verified trusted WorkOrder identity. */
public final class WorkerDispatchVisibilityScopeResolver implements DuplicateVisibilityScopeResolver {
    private final WorkOrderRepository workOrders;

    public WorkerDispatchVisibilityScopeResolver(WorkOrderRepository workOrders) {
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
    }

    @Override
    public String resolve(ActionProposal proposal, ExecutionContext context) {
        WorkerDispatchInput input = WorkerDispatchInput.from(proposal.input());
        Object resourceType = context.metadata().get("resource.type");
        Object resourceId = context.metadata().get("resource.id");
        if (!WorkerDispatchAction.RESOURCE_TYPE.equals(resourceType)
                || !(resourceId instanceof String id)
                || !input.workOrderId().value().equals(id)) {
            throw new IllegalArgumentException("trusted WorkOrder resource scope does not match the request");
        }
        TenantId tenantId = new TenantId(context.tenantId());
        var canonical = workOrders.find(tenantId, input.workOrderId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "WorkOrder is not visible in the trusted tenant scope"));
        return "work-order:" + canonical.workOrderId().value();
    }
}
