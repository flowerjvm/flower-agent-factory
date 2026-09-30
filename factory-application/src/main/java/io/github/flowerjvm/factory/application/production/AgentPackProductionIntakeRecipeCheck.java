package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.contracts.ids.TenantId;

/** Read-only host-selected recipe check, after trusted target authorization and before result lookup. */
@FunctionalInterface
public interface AgentPackProductionIntakeRecipeCheck {
    void check(TenantId tenant, AgentPackProductionIntakeInput input);
}
