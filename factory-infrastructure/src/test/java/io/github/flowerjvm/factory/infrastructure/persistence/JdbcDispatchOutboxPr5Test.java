package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.outbox.DispatchClaimPurpose;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcDispatchOutboxPr5Test {
    private static final TenantId TENANT = new TenantId("tenant-pr5-outbox");
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void operationLookupIsTenantAndCommandScoped() {
        JdbcDataSource dataSource = migrated("outbox_operation_scope");
        var repository = new JdbcDispatchOutboxRepository(dataSource);
        String operationId = "worker-operation-shared";
        DispatchOutbox dispatch = pending("dispatch", WorkerOutboxOperations.DISPATCH, operationId);
        DispatchOutbox cancel = pending("cancel", WorkerOutboxOperations.CANCEL, operationId);
        repository.create(dispatch);
        repository.create(cancel);

        assertEquals(
                dispatch,
                repository.findByOperation(TENANT, WorkerOutboxOperations.DISPATCH, operationId)
                        .orElseThrow());
        assertEquals(
                cancel,
                repository.findByOperation(TENANT, WorkerOutboxOperations.CANCEL, operationId)
                        .orElseThrow());
        assertTrue(repository.findByOperation(
                        new TenantId("tenant-other"), WorkerOutboxOperations.DISPATCH, operationId)
                .isEmpty());
    }

    @Test
    void twoConnectionsAllowExactlyOneFreshSubmissionClaim() throws Exception {
        JdbcDataSource dataSource = migrated("outbox_submit_race");
        DispatchOutbox pending = pending("submit-race", WorkerOutboxOperations.DISPATCH, "op-submit-race");
        new JdbcDispatchOutboxRepository(dataSource).create(pending);

        List<Optional<DispatchOutbox>> outcomes = race(
                () -> new JdbcDispatchOutboxRepository(dataSource).claimNextForSubmission(
                        WorkerOutboxOperations.DISPATCH, NOW, Duration.ofSeconds(30), "submit-a"),
                () -> new JdbcDispatchOutboxRepository(dataSource).claimNextForSubmission(
                        WorkerOutboxOperations.DISPATCH, NOW, Duration.ofSeconds(30), "submit-b"));

        assertEquals(1, outcomes.stream().filter(Optional::isPresent).count());
        DispatchOutbox winner = outcomes.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        assertEquals(DispatchOutboxStatus.DISPATCHING, winner.status());
        assertEquals(DispatchClaimPurpose.SUBMIT, winner.claimPurpose().orElseThrow());
        assertEquals(1, winner.attemptCount());
        assertEquals(winner, new JdbcDispatchOutboxRepository(dataSource)
                .find(TENANT, pending.outboxId()).orElseThrow());
    }

    @Test
    void expiredSubmissionCanOnlyBeClaimedForReconciliationThenProvenAbsentRetry() throws Exception {
        JdbcDataSource dataSource = migrated("outbox_reconcile_race");
        var repository = new JdbcDispatchOutboxRepository(dataSource);
        DispatchOutbox pending = pending("reconcile-race", WorkerOutboxOperations.DISPATCH, "op-reconcile");
        repository.create(pending);
        DispatchOutbox submitted = repository.claimNextForSubmission(
                WorkerOutboxOperations.DISPATCH, NOW, Duration.ofSeconds(10), "submit-owner")
                .orElseThrow();
        Instant expiry = NOW.plusSeconds(10);

        assertTrue(repository.claimNextForSubmission(
                        WorkerOutboxOperations.DISPATCH, expiry, Duration.ofSeconds(10), "illegal-resubmit")
                .isEmpty());
        List<Optional<DispatchOutbox>> outcomes = race(
                () -> new JdbcDispatchOutboxRepository(dataSource).claimExpiredForReconciliation(
                        WorkerOutboxOperations.DISPATCH, expiry, Duration.ofSeconds(20), "reconcile-a"),
                () -> new JdbcDispatchOutboxRepository(dataSource).claimExpiredForReconciliation(
                        WorkerOutboxOperations.DISPATCH, expiry, Duration.ofSeconds(20), "reconcile-b"));

        assertEquals(1, outcomes.stream().filter(Optional::isPresent).count());
        DispatchOutbox reconciling = outcomes.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        assertEquals(DispatchClaimPurpose.RECONCILE, reconciling.claimPurpose().orElseThrow());
        assertEquals(submitted.attemptCount(), reconciling.attemptCount());

        Instant observedAt = expiry.plusSeconds(1);
        DispatchOutbox provenAbsent = reconciling.retryAfterProvenNoEffect(
                reconciling.claimToken().orElseThrow(),
                "WORKER_OPERATION_NOT_FOUND",
                observedAt,
                observedAt.plusSeconds(5));
        assertTrue(repository.compareAndSet(reconciling, provenAbsent));
        DispatchOutbox retry = repository.claimNextForSubmission(
                        WorkerOutboxOperations.DISPATCH,
                        observedAt.plusSeconds(5),
                        Duration.ofSeconds(10),
                        "second-submit")
                .orElseThrow();
        assertEquals(DispatchClaimPurpose.SUBMIT, retry.claimPurpose().orElseThrow());
        assertEquals(2, retry.attemptCount());
    }

    @Test
    void repositoryRejectsAgeTakeoverDisguisedAsAnotherSubmissionClaim() {
        JdbcDataSource dataSource = migrated("outbox_illegal_takeover");
        var repository = new JdbcDispatchOutboxRepository(dataSource);
        DispatchOutbox pending = pending("illegal-takeover", WorkerOutboxOperations.DISPATCH, "op-illegal");
        repository.create(pending);
        DispatchOutbox submitted = repository.claimNextForSubmission(
                        WorkerOutboxOperations.DISPATCH, NOW, Duration.ofSeconds(1), "submit-owner")
                .orElseThrow();
        Instant later = NOW.plusSeconds(1);
        DispatchOutbox forgedSubmission = new DispatchOutbox(
                submitted.outboxId(), submitted.tenantId(), submitted.operationType(),
                submitted.aggregateType(), submitted.aggregateId(), submitted.operationId(),
                submitted.payloadArtifactRef(), DispatchOutboxStatus.DISPATCHING,
                Optional.of("forged-submit"), Optional.of(DispatchClaimPurpose.SUBMIT),
                Optional.of(later), Optional.of(later.plusSeconds(10)), submitted.availableAt(),
                submitted.attemptCount() + 1, submitted.lastCode(), submitted.version() + 1,
                submitted.createdAt(), later);

        assertThrows(IllegalArgumentException.class, () -> repository.compareAndSet(submitted, forgedSubmission));
        assertEquals(submitted, repository.find(TENANT, submitted.outboxId()).orElseThrow());
    }

    @Test
    void currentClaimOwnerCanPersistReconciliationEvidenceWithoutRenewingItsLease() {
        JdbcDataSource dataSource = migrated("outbox_claim_observation");
        var repository = new JdbcDispatchOutboxRepository(dataSource);
        DispatchOutbox pending = pending(
                "claim-observation", WorkerOutboxOperations.CANCEL, "op-claim-observation");
        repository.create(pending);
        DispatchOutbox claimed = repository.claimNextForSubmission(
                        WorkerOutboxOperations.CANCEL,
                        NOW,
                        Duration.ofSeconds(30),
                        "cancel-owner")
                .orElseThrow();

        DispatchOutbox observed = claimed.recordClaimObservation(
                "cancel-owner", "WORKER_CANCEL_ABSENCE_UNPROVEN", NOW.plusSeconds(1));

        assertTrue(repository.compareAndSet(claimed, observed));
        assertEquals(claimed.claimToken(), observed.claimToken());
        assertEquals(claimed.leaseUntil(), observed.leaseUntil());
        assertEquals(Optional.of("WORKER_CANCEL_ABSENCE_UNPROVEN"), repository.find(
                TENANT, pending.outboxId()).orElseThrow().lastCode());
    }

    private static DispatchOutbox pending(String suffix, String operationType, String operationId) {
        return new DispatchOutbox(
                new DispatchOutboxId("outbox-" + suffix),
                TENANT,
                operationType,
                "WORKER_RUN",
                "worker-" + suffix,
                operationId,
                new ArtifactReference("artifact:payload:" + suffix),
                DispatchOutboxStatus.PENDING,
                NOW,
                0,
                Optional.empty(),
                0,
                NOW,
                NOW);
    }

    private static JdbcDataSource migrated(String suffix) {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2(suffix);
        FactoryDatabaseMigrations.migrate(dataSource);
        return dataSource;
    }

    private static List<Optional<DispatchOutbox>> race(
            Callable<Optional<DispatchOutbox>> first,
            Callable<Optional<DispatchOutbox>> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(3);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<DispatchOutbox>> a = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return first.call();
            });
            Future<Optional<DispatchOutbox>> b = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.call();
            });
            barrier.await(5, TimeUnit.SECONDS);
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }
}
