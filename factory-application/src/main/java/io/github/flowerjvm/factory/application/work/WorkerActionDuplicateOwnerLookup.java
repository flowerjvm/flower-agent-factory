package io.github.flowerjvm.factory.application.work;

import java.util.Objects;
import java.util.Optional;

/** Read-only bridge to the Action Runtime duplicate reservation's canonical owner. */
@FunctionalInterface
public interface WorkerActionDuplicateOwnerLookup {
    Optional<String> findOwnerRunId(String tenantId, String actionId, String idempotencyKey);

    static WorkerActionDuplicateOwnerLookup none() {
        return (tenantId, actionId, idempotencyKey) -> {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            return Optional.empty();
        };
    }
}
