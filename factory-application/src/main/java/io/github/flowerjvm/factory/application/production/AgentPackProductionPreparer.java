package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;

/** Bounded domain preparation, invoked only by the registered production Action executor. */
@FunctionalInterface
public interface AgentPackProductionPreparer {
    ProductionPreparationResult prepare(TenantId tenantId, BuildSessionId buildSessionId, long expectedSessionVersion);
}
