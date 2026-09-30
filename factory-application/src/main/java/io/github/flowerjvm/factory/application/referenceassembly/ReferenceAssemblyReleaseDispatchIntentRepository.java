package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Version-CAS and lease-claim port for deferred Reference Assembly release intents. */
public interface ReferenceAssemblyReleaseDispatchIntentRepository {
    void create(ReferenceAssemblyReleaseDispatchIntent intent);

    Optional<ReferenceAssemblyReleaseDispatchIntent> find(String operationId);

    Optional<ReferenceAssemblyReleaseDispatchIntent> findLatest(
            TenantId tenantId, ReferenceAssemblyId referenceAssemblyId);

    /** Claims PENDING and due UNCERTAIN candidates while respecting their retry lease. */
    Optional<ReferenceAssemblyReleaseDispatchIntent> claimNext(
            Instant now, Duration lease, String claimToken);

    /** Reclaims an expired RUNNING lease solely for idempotent release reconciliation. */
    Optional<ReferenceAssemblyReleaseDispatchIntent> claimExpiredRunning(
            Instant now, Duration lease, String claimToken);

    boolean compareAndSet(
            ReferenceAssemblyReleaseDispatchIntent expected,
            ReferenceAssemblyReleaseDispatchIntent next);
}
