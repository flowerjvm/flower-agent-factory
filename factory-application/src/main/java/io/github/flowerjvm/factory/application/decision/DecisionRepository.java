package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.Optional;

/** Insert-only repository SPI for immutable human decisions. */
public interface DecisionRepository {
    void create(Decision decision);

    Optional<Decision> find(TenantId tenantId, DecisionId decisionId);

    Optional<Decision> findByRequestIdempotencyKey(
            TenantId tenantId,
            DecisionPointId decisionPointId,
            String requestIdempotencyKey);
}
