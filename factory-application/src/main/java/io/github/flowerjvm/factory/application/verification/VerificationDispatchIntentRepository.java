package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Version-CAS SPI for the PR4-only durable verifier queue. */
public interface VerificationDispatchIntentRepository {
    void create(VerificationDispatchIntent intent);

    Optional<VerificationDispatchIntent> find(String operationId);

    Optional<VerificationDispatchIntent> findLatest(
            TenantId tenantId, VerificationRunId verificationRunId);

    Optional<VerificationDispatchIntent> claimNext(Instant now, Duration lease, String claimToken);

    /** Claims an expired active attempt solely for reconciliation/classification, never verifier execution. */
    Optional<VerificationDispatchIntent> claimExpiredRunningForReconciliation(
            Instant now, Duration lease, String claimToken);

    boolean compareAndSet(VerificationDispatchIntent expected, VerificationDispatchIntent next);
}
