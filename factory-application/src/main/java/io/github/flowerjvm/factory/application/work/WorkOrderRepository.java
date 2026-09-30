package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import java.util.Optional;

/** Insert-only repository SPI for immutable WorkOrders. */
public interface WorkOrderRepository {
    void create(WorkOrder workOrder);

    Optional<WorkOrder> find(TenantId tenantId, WorkOrderId workOrderId);

    /** Latest immutable order for one durable phase; revision/id is only a deterministic tie-breaker. */
    default Optional<WorkOrder> findLatestByBuildSessionAndPhase(
            TenantId tenantId, BuildSessionId buildSessionId, String phase) {
        throw new UnsupportedOperationException("phase query is not implemented by this repository");
    }
}
