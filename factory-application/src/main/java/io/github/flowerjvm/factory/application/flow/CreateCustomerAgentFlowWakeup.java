package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import java.util.Objects;

/** Internal wake-up hint emitted only after the durable ledger transition has committed. */
public record CreateCustomerAgentFlowWakeup(BuildSessionId buildSessionId) {
    public CreateCustomerAgentFlowWakeup {
        Objects.requireNonNull(buildSessionId, "buildSessionId");
    }
}
