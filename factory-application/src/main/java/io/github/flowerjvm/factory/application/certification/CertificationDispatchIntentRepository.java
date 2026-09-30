package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Version-CAS and lease-claim port for deferred Certification issuance intents. */
public interface CertificationDispatchIntentRepository {
    void create(CertificationDispatchIntent intent);

    Optional<CertificationDispatchIntent> find(String operationId);

    Optional<CertificationDispatchIntent> findLatest(
            TenantId tenantId, CertificationId certificationId);

    /**
     * Claims PENDING and due UNCERTAIN work. The runner's bounded observation backoff permits
     * prompt WAITING_EXTERNAL discovery without a hot-loop; the repository must respect retryAt.
     */
    Optional<CertificationDispatchIntent> claimNext(
            Instant now, Duration lease, String claimToken);

    /** Reclaims an expired lease for idempotent issuance reconciliation, never by unconditional update. */
    Optional<CertificationDispatchIntent> claimExpiredRunning(
            Instant now, Duration lease, String claimToken);

    boolean compareAndSet(
            CertificationDispatchIntent expected, CertificationDispatchIntent next);
}
