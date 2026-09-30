package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Version-CAS repository for tokenless callback staging and restart processing. */
public interface WorkerCallbackInboxRepository {
    void create(WorkerCallbackInboxEntry entry);

    Optional<WorkerCallbackInboxEntry> find(TenantId tenantId, String callbackId);

    Optional<WorkerCallbackInboxEntry> findByEvent(
            TenantId tenantId, String workerBindingId, String eventId);

    Optional<WorkerCallbackInboxEntry> claimNext(Instant now, Duration lease, String claimToken);

    Optional<WorkerCallbackInboxEntry> claimExpired(Instant now, Duration lease, String claimToken);

    boolean compareAndSet(WorkerCallbackInboxEntry expected, WorkerCallbackInboxEntry next);
}
