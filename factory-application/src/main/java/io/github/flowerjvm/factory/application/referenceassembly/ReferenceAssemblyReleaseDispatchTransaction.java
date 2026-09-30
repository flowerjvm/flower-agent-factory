package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Optional;

/**
 * Atomic exact-state validation and durable intent creation for the governed Reference Assembly
 * release Action.
 */
public interface ReferenceAssemblyReleaseDispatchTransaction {
    ReferenceAssemblyReleaseDispatchIntent prepare(
            TenantId tenantId,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            String attemptTokenHash,
            Instant preparedAt);

    Optional<ReferenceAssemblyReleaseDispatchIntent> findExact(
            TenantId tenantId,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            String attemptTokenHash);
}
