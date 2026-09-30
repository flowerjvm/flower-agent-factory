package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.application.work.WorkerCallbackAuditEvent;
import io.github.flowerjvm.factory.application.work.WorkerCallbackInboxEntry;
import io.github.flowerjvm.factory.application.work.WorkerCallbackInboxStatus;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcWorkerCallbackInboxTest {
    private static final TenantId TENANT = PersistenceFixtures.TENANT;
    private static final Instant NOW = Instant.parse("2026-08-20T01:00:00Z");

    @Test
    void stagesOnlyExactTenantScopedPayloadArtifactAndDeduplicatesSignedEvent() {
        Fixture fixture = fixture("callback_artifact");
        WorkerCallbackInboxEntry received = entry(fixture, "callback-a", "event-a");
        fixture.repository().create(received);

        assertEquals(received, fixture.repository().find(TENANT, received.callbackId()).orElseThrow());
        assertEquals(received, fixture.repository()
                .findByEvent(TENANT, received.workerBindingId(), received.eventId()).orElseThrow());
        assertTrue(fixture.repository().find(new TenantId("tenant-other"), received.callbackId()).isEmpty());

        WorkerCallbackInboxEntry replayWithDifferentCallback = entry(fixture, "callback-b", "event-a");
        assertThrows(
                DuplicateLedgerRecordException.class,
                () -> fixture.repository().create(replayWithDifferentCallback));

        WorkerCallbackInboxEntry wrongHash = new WorkerCallbackInboxEntry(
                "callback-wrong-hash", TENANT, fixture.worker().workerBindingId(), "event-wrong-hash",
                fixture.worker().workOrderId(), fixture.worker().workerRunId(),
                fixture.worker().operationId(), fixture.worker().attemptTokenHash().orElseThrow(), fixture.payloadRef(),
                new ContentHash("b".repeat(64)), WorkerCallbackInboxStatus.RECEIVED, NOW,
                Optional.empty(), Optional.empty(), 1, Optional.empty(), 0, NOW, NOW);
        assertThrows(IllegalArgumentException.class, () -> fixture.repository().create(wrongHash));
        assertTrue(fixture.repository().find(TENANT, wrongHash.callbackId()).isEmpty());
    }

    @Test
    void oversizedStoredArtifactIsRejectedBeforeCallbackPayloadMaterialization() {
        Fixture fixture = fixture("callback_oversized");
        byte[] oversized = new byte[65_537];
        ContentHash oversizedHash = sha256(oversized);
        ArtifactReference oversizedRef = new ArtifactReference("artifact:callback:oversized");
        new JdbcArtifactStore(fixture.dataSource()).store(new Artifact(
                TENANT, oversizedRef, oversizedHash, "application/json", oversized));
        WorkerCallbackInboxEntry entry = WorkerCallbackInboxEntry.received(
                "callback-oversized",
                TENANT,
                fixture.worker().workerBindingId(),
                "event-oversized",
                fixture.worker().workOrderId(),
                fixture.worker().workerRunId(),
                fixture.worker().operationId(),
                fixture.worker().attemptTokenHash().orElseThrow(),
                oversizedRef,
                oversizedHash,
                NOW);

        assertThrows(IllegalArgumentException.class, () -> fixture.repository().create(entry));
        assertTrue(fixture.repository().find(TENANT, entry.callbackId()).isEmpty());
    }

    @Test
    void twoConnectionsAllowOneCallbackClaimAndOneExpiredReclaim() throws Exception {
        Fixture fixture = fixture("callback_claim_race");
        WorkerCallbackInboxEntry received = entry(fixture, "callback-race", "event-race");
        fixture.repository().create(received);

        List<Optional<WorkerCallbackInboxEntry>> claimed = race(
                () -> new JdbcWorkerCallbackInboxRepository(fixture.dataSource())
                        .claimNext(NOW, Duration.ofSeconds(10), "claim-a"),
                () -> new JdbcWorkerCallbackInboxRepository(fixture.dataSource())
                        .claimNext(NOW, Duration.ofSeconds(10), "claim-b"));
        assertEquals(1, claimed.stream().filter(Optional::isPresent).count());
        WorkerCallbackInboxEntry processing = claimed.stream()
                .flatMap(Optional::stream).findFirst().orElseThrow();
        assertEquals(WorkerCallbackInboxStatus.PROCESSING, processing.status());

        Instant expiry = NOW.plusSeconds(10);
        assertTrue(fixture.repository().claimNext(expiry, Duration.ofSeconds(10), "not-an-expired-claim")
                .isEmpty());
        List<Optional<WorkerCallbackInboxEntry>> reclaimed = race(
                () -> new JdbcWorkerCallbackInboxRepository(fixture.dataSource())
                        .claimExpired(expiry, Duration.ofSeconds(20), "reclaim-a"),
                () -> new JdbcWorkerCallbackInboxRepository(fixture.dataSource())
                        .claimExpired(expiry, Duration.ofSeconds(20), "reclaim-b"));
        assertEquals(1, reclaimed.stream().filter(Optional::isPresent).count());
        WorkerCallbackInboxEntry owner = reclaimed.stream()
                .flatMap(Optional::stream).findFirst().orElseThrow();
        WorkerCallbackInboxEntry applied = owner.applied(
                owner.claimToken().orElseThrow(), "WORKER_CALLBACK_APPLIED", expiry.plusSeconds(1));
        assertTrue(fixture.repository().compareAndSet(owner, applied));
        assertEquals(applied, fixture.repository().find(TENANT, applied.callbackId()).orElseThrow());
    }

    @Test
    void appendOnlySecurityAuditSurvivesRestartAndSupportsUnknownTenantWithoutPayloadData() throws Exception {
        Fixture fixture = fixture("callback_audit");
        var audit = new JdbcWorkerCallbackSecurityAudit(fixture.dataSource());
        audit.record(new WorkerCallbackAuditEvent(
                Optional.empty(),
                "unknown-binding",
                Optional.empty(),
                Optional.of("event-rejected"),
                Optional.of("worker-rejected"),
                "WORKER_CALLBACK_AUTHENTICATION_REJECTED",
                false,
                NOW));
        new JdbcWorkerCallbackSecurityAudit(fixture.dataSource()).record(new WorkerCallbackAuditEvent(
                Optional.of(TENANT),
                fixture.worker().workerBindingId(),
                Optional.of("worker-principal"),
                Optional.of("event-accepted"),
                Optional.of(fixture.worker().workerRunId().value()),
                "WORKER_CALLBACK_AUTHENTICATED",
                true,
                NOW.plusSeconds(1)));

        try (var connection = fixture.dataSource().getConnection();
                var statement = connection.prepareStatement("""
                        SELECT trusted_tenant_id, authenticated_principal_ref, event_id,
                               worker_run_id, code, accepted
                        FROM factory_worker_callback_audit
                        WHERE code = 'WORKER_CALLBACK_AUTHENTICATION_REJECTED'
                        """);
                var resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            assertNull(resultSet.getString("trusted_tenant_id"));
            assertNull(resultSet.getString("authenticated_principal_ref"));
            assertEquals("event-rejected", resultSet.getString("event_id"));
            assertEquals("worker-rejected", resultSet.getString("worker_run_id"));
            assertEquals("WORKER_CALLBACK_AUTHENTICATION_REJECTED", resultSet.getString("code"));
            assertTrue(!resultSet.getBoolean("accepted"));
            assertTrue(!resultSet.next());
        }
        try (var connection = fixture.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM factory_worker_callback_audit");
                var resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            assertEquals(2, resultSet.getInt(1));
        }
    }

    private static WorkerCallbackInboxEntry entry(Fixture fixture, String callbackId, String eventId) {
        return WorkerCallbackInboxEntry.received(
                callbackId,
                TENANT,
                fixture.worker().workerBindingId(),
                eventId,
                fixture.worker().workOrderId(),
                fixture.worker().workerRunId(),
                fixture.worker().operationId(),
                fixture.worker().attemptTokenHash().orElseThrow(),
                fixture.payloadRef(),
                fixture.payloadHash(),
                NOW);
    }

    private static Fixture fixture(String suffix) {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2(suffix);
        FactoryDatabaseMigrations.migrate(dataSource);
        var buildSession = PersistenceFixtures.buildSession(
                suffix,
                "request-" + suffix,
                io.github.flowerjvm.factory.application.build.BuildSessionPhase.GENERATE_CANDIDATE);
        var workOrder = PersistenceFixtures.workOrder(buildSession, suffix);
        WorkerRunRecord requested = PersistenceFixtures.workerRun(buildSession, workOrder, suffix);
        var sessions = new JdbcBuildSessionRepository(dataSource);
        var orders = new JdbcWorkOrderRepository(dataSource);
        var workers = new JdbcWorkerRunRepository(dataSource);
        sessions.create(buildSession);
        orders.create(workOrder);
        workers.create(requested);
        var owner = ActionRunFixtures.createWaitingWorkerDispatchOwner(
                dataSource, requested, "action-callback-" + suffix);
        DispatchOutbox fixtureOutbox = PersistenceFixtures.outbox(requested, suffix);
        DispatchOutbox dispatchOutbox = new DispatchOutbox(
                fixtureOutbox.outboxId(),
                fixtureOutbox.tenantId(),
                WorkerOutboxOperations.DISPATCH,
                fixtureOutbox.aggregateType(),
                fixtureOutbox.aggregateId(),
                fixtureOutbox.operationId(),
                workOrder.inputArtifactManifestRef(),
                fixtureOutbox.status(),
                fixtureOutbox.availableAt(),
                fixtureOutbox.attemptCount(),
                fixtureOutbox.lastCode(),
                fixtureOutbox.version(),
                fixtureOutbox.createdAt(),
                fixtureOutbox.updatedAt());
        Instant dispatchAt = PersistenceFixtures.NOW.plusSeconds(1);
        WorkerRunRecord dispatching = requested.startDispatch(
                dispatchOutbox.outboxId(),
                owner.runId(),
                WorkerDispatchOperationIds.hashAttemptToken(owner.attemptToken()),
                dispatchAt);
        assertTrue(new JdbcWorkerDispatchTransaction(
                        dataSource,
                        new ObjectMapper(),
                        Clock.fixed(dispatchAt, ZoneOffset.UTC))
                .prepare(workOrder, requested, dispatching, dispatchOutbox));
        byte[] payload = ("{\"event\":\"" + suffix + "\"}").getBytes(StandardCharsets.UTF_8);
        ContentHash hash = sha256(payload);
        ArtifactReference reference = new ArtifactReference("artifact:callback:" + suffix);
        new JdbcArtifactStore(dataSource).store(new Artifact(
                TENANT, reference, hash, "application/json", payload));
        return new Fixture(
                dataSource,
                new JdbcWorkerCallbackInboxRepository(dataSource),
                dispatching,
                reference,
                hash);
    }

    private static ContentHash sha256(byte[] value) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<Optional<WorkerCallbackInboxEntry>> race(
            Callable<Optional<WorkerCallbackInboxEntry>> first,
            Callable<Optional<WorkerCallbackInboxEntry>> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(3);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<WorkerCallbackInboxEntry>> a = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return first.call();
            });
            Future<Optional<WorkerCallbackInboxEntry>> b = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.call();
            });
            barrier.await(5, TimeUnit.SECONDS);
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private record Fixture(
            JdbcDataSource dataSource,
            JdbcWorkerCallbackInboxRepository repository,
            WorkerRunRecord worker,
            ArtifactReference payloadRef,
            ContentHash payloadHash) {}
}
