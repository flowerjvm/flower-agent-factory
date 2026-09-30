package io.github.flowerjvm.factory.application.outbox;

import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Version-CAS repository SPI. No unconditional update or upsert is exposed. */
public interface DispatchOutboxRepository {
    void create(DispatchOutbox outbox);

    Optional<DispatchOutbox> find(TenantId tenantId, DispatchOutboxId outboxId);

    default Optional<DispatchOutbox> findByOperation(
            TenantId tenantId, String operationType, String operationId) {
        throw new UnsupportedOperationException("operation lookup is not implemented by this repository");
    }

    default Optional<DispatchOutbox> claimNextForSubmission(
            String operationType, Instant now, Duration lease, String claimToken) {
        throw new UnsupportedOperationException("submission claiming is not implemented by this repository");
    }

    /** Claims expired DISPATCHING work for status reconciliation only, never for another submit. */
    default Optional<DispatchOutbox> claimExpiredForReconciliation(
            String operationType, Instant now, Duration lease, String claimToken) {
        throw new UnsupportedOperationException("reconciliation claiming is not implemented by this repository");
    }

    boolean compareAndSet(DispatchOutbox expected, DispatchOutbox next);
}
