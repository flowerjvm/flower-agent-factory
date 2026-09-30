package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.production.AgentPackWorkPreparationTransaction.PreparationDisposition;
import io.github.flowerjvm.factory.application.production.AgentPackWorkPreparationTransaction.PreparationOutcome;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Preparation-only fixtures: no model invocation, successful Worker, verifier or approval seed. */
class JdbcAgentPackWorkPreparationTransactionTest {
    static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z");
    static final TenantId TENANT = new TenantId("production-tenant");
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptanceAtomicallyStoresInputsAndReusesCanonicalTimestamps() {
        Fixture f = Fixture.create("accept");
        BuildSession pristine = f.pristine();
        BuildSession retry = copy(pristine, pristine.status(), pristine.currentPhase(), 0,
                pristine.createdAt().plusMillis(100), pristine.createdAt().plusMillis(100));
        assertEquals(f.session(), f.transaction().accept(retry, List.of(f.requirements())));
        assertEquals(1, f.count("factory_build_session"));
        assertEquals(2, f.count("factory_artifact")); // requirements + immutable acceptance receipt
    }

    @Test
    void sameAcceptanceKeyCannotChangeSessionProjectOrStagedPlan() {
        Fixture f = Fixture.create("accept-conflict");
        BuildSession a = f.pristine();
        BuildSession another = new BuildSession(new BuildSessionId("other-session"), a.tenantId(),
                new ProjectId("other-project"), a.productLineId(), a.requestIdempotencyKey(), a.createdBy(),
                a.status(), a.currentPhase(), a.requirementsArtifactRef(), a.requirementsHash(),
                a.selectedManagerWorkerBinding(), a.selectedCodingWorkerBinding(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 0, a.maxRepairRounds(), a.startedAt(), a.deadlineAt(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0, a.createdAt(), a.updatedAt());
        assertThrows(RuntimeException.class, () -> f.transaction().accept(another, List.of(f.requirements())));
        assertThrows(RuntimeException.class, () -> f.transaction().accept(a,
                List.of(f.requirements(), artifact("different-plan", "changed plan"))));
        assertEquals(1, f.count("factory_build_session"));
        assertEquals(2, f.count("factory_artifact"));
    }

    @Test
    void preparationCreatesOneImmutableOrderAndPristineRequestedAttemptAndReusesExactRetry() {
        Fixture f = Fixture.create("prepare");
        assertEquals(PreparationDisposition.CREATED, f.prepare().disposition());
        PreparationOutcome retry = f.prepare();
        assertEquals(PreparationDisposition.EXISTING_EXACT, retry.disposition());
        assertEquals(f.order(), retry.workOrder());
        assertEquals(f.run(), retry.workerRun());
        assertEquals(WorkerRunStatus.REQUESTED, retry.workerRun().status());
        assertEquals(f.session(), f.storedSession()); // a lock fence does not manufacture a phase change
        assertEquals(1, f.count("factory_work_order"));
        assertEquals(1, f.count("factory_worker_run"));
        assertEquals(0, f.count("factory_dispatch_outbox"));
    }

    @Test
    void distinctIdentityCannotCreateAnotherOrderForTheSamePhaseAndRepairRound() {
        Fixture f = Fixture.create("different-order");
        f.prepare();
        WorkOrder other = order(f.session(), "replacement", f.order().instructionArtifactRef(),
                f.order().instructionHash(), f.order().inputArtifactManifestRef(), f.order().inputManifestHash(),
                f.order().policySnapshotRef(), 1);
        assertThrows(IllegalArgumentException.class, () -> f.transaction().prepare(
                f.session(), other, run(f.session(), other, "replacement"), f.staged()));
        assertEquals(1, f.count("factory_work_order"));
        assertEquals(1, f.count("factory_worker_run"));
    }

    @Test
    void stalePhaseCancellationAndDeadlineCannotPrepareOrReplayAnOrder() {
        Fixture stale = Fixture.create("stale");
        BuildSession changed = copy(stale.session(), BuildSessionStatus.RUNNING, BuildSessionPhase.GENERATE_CANDIDATE,
                2, stale.session().createdAt(), NOW.plusSeconds(2));
        assertTrue(new JdbcBuildSessionRepository(stale.dataSource()).compareAndSet(stale.session(), changed));
        assertThrows(IllegalArgumentException.class, stale::prepare);
        assertEquals(0, stale.count("factory_worker_run"));

        Fixture cancelled = Fixture.create("cancelled");
        cancelled.prepare();
        assertTrue(new JdbcBuildSessionRepository(cancelled.dataSource()).compareAndSet(
                cancelled.session(), cancelled.session().requestCancellation(NOW.plusSeconds(2))));
        assertThrows(IllegalArgumentException.class, cancelled::prepare);
        assertEquals(1, cancelled.count("factory_worker_run"));

        Fixture expired = Fixture.create("expired");
        assertThrows(IllegalArgumentException.class, () -> new JdbcAgentPackWorkPreparationTransaction(
                expired.dataSource(), MAPPER, Clock.fixed(expired.order().deadlineAt(), ZoneOffset.UTC))
                .prepare(expired.session(), expired.order(), expired.run(), expired.staged()));
        assertEquals(0, expired.count("factory_work_order"));
    }

    @Test
    void missingManifestArtifactRollsBackAllStagedArtifactsAndRows() {
        Fixture f = Fixture.create("missing-artifact");
        var incomplete = f.staged().stream().filter(value -> !value.reference().value().contains("toolchain-lock")).toList();
        assertThrows(IllegalArgumentException.class, () -> f.transaction().prepare(f.session(), f.order(), f.run(), incomplete));
        assertEquals(2, f.count("factory_artifact"));
        assertEquals(0, f.count("factory_work_order"));
        assertEquals(0, f.count("factory_worker_run"));
    }

    @Test
    void badTenantHashAndNonPristineAttemptAreRejectedBeforeAnyWrite() {
        Fixture f = Fixture.create("invalid");
        Artifact input = f.staged().getFirst();
        Artifact foreign = new Artifact(new TenantId("foreign"), input.reference(), input.contentHash(), input.mediaType(), input.content());
        assertThrows(IllegalArgumentException.class, () -> f.transaction().prepare(f.session(), f.order(), f.run(), List.of(foreign)));
        Artifact corrupt = new Artifact(TENANT, input.reference(), input.contentHash(), input.mediaType(), new byte[] {1});
        assertThrows(IllegalArgumentException.class, () -> f.transaction().prepare(f.session(), f.order(), f.run(), List.of(corrupt)));
        WorkerRunRecord r = f.run();
        WorkerRunRecord wrongAttempt = new WorkerRunRecord(r.workerRunId(), r.tenantId(), r.buildSessionId(), r.workOrderId(),
                2, r.workerBindingId(), r.workerAdapterVersion(), r.workerCapabilitySnapshot(), r.status(), r.actionRunId(),
                r.operationId(), r.attemptTokenHash(), r.externalSessionRef(), r.dispatchOutboxId(), r.startedAt(),
                r.deadlineAt(), r.heartbeatAt(), r.cancelRequestedAt(), r.completedAt(), r.resultArtifactManifestRef(),
                r.resultHash(), r.code(), r.message(), r.retryDisposition(), r.version(), r.createdAt(), r.updatedAt());
        assertThrows(IllegalArgumentException.class, () -> f.transaction().prepare(f.session(), f.order(), wrongAttempt, f.staged()));
        assertEquals(0, f.count("factory_work_order"));
        assertEquals(2, f.count("factory_artifact"));
    }

    @Test
    void orderRevisionCannotSkipThePersistedRepairRound() {
        Fixture f = Fixture.create("round-fence");
        WorkOrder skipped = order(f.session(), "skip", f.order().instructionArtifactRef(), f.order().instructionHash(),
                f.order().inputArtifactManifestRef(), f.order().inputManifestHash(), f.order().policySnapshotRef(), 2);
        assertThrows(IllegalArgumentException.class, () -> f.transaction().prepare(
                f.session(), skipped, run(f.session(), skipped, "skip"), f.staged()));
        assertEquals(0, f.count("factory_work_order"));
        assertEquals(0, f.count("factory_worker_run"));
    }

    @Test
    void designWorkOrderUsesCodingWorkerAndCannotBorrowTheIndependentManagerBinding() {
        Fixture f = Fixture.create("manager-binding");
        assertEquals("coding", f.run().workerBindingId());
        WorkerRunRecord r = f.run();
        WorkerRunRecord wrongBinding = new WorkerRunRecord(r.workerRunId(), r.tenantId(), r.buildSessionId(), r.workOrderId(),
                r.attemptNo(), "manager", r.workerAdapterVersion(), r.workerCapabilitySnapshot(), r.status(), r.actionRunId(),
                r.operationId(), r.attemptTokenHash(), r.externalSessionRef(), r.dispatchOutboxId(), r.startedAt(),
                r.deadlineAt(), r.heartbeatAt(), r.cancelRequestedAt(), r.completedAt(), r.resultArtifactManifestRef(),
                r.resultHash(), r.code(), r.message(), r.retryDisposition(), r.version(), r.createdAt(), r.updatedAt());
        assertThrows(IllegalArgumentException.class, () -> f.transaction().prepare(f.session(), f.order(), wrongBinding, f.staged()));
        assertEquals(0, f.count("factory_worker_run"));
        assertEquals(PreparationDisposition.CREATED, f.prepare().disposition());
    }

    @Test
    void acceptanceFailureRollsBackTheImmutableReceiptAndNewSessionTogether() {
        DataSource source = FactoryDatabaseMigrationsTest.h2("production_accept_rollback");
        FactoryDatabaseMigrations.migrate(source);
        Fixture reference = Fixture.create("accept-rollback-template");
        var transaction = new JdbcAgentPackWorkPreparationTransaction(source, MAPPER,
                Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () -> transaction.accept(reference.pristine(), List.of()));
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM factory_artifact")) {
            r.next(); assertEquals(0, r.getInt(1));
        } catch (SQLException failure) { throw new AssertionError(failure); }
        assertTrue(new JdbcBuildSessionRepository(source).find(TENANT, reference.pristine().buildSessionId()).isEmpty());
    }

    @Test
    void failureAtWorkerInsertRollsBackOrderAndArtifactsOnTheSameConnection() {
        Fixture f = Fixture.create("rollback");
        DataSource failing = new InterceptingDataSource(f.dataSource(), (sql, method) -> {
            if (sql.contains("INSERT INTO factory_worker_run") && method.equals("executeUpdate")) {
                throw new SQLException("injected Worker insert failure", "45000");
            }
        });
        assertThrows(FactoryPersistenceException.class, () -> f.transaction(failing).prepare(
                f.session(), f.order(), f.run(), f.staged()));
        assertEquals(0, f.count("factory_work_order"));
        assertEquals(0, f.count("factory_worker_run"));
        assertEquals(2, f.count("factory_artifact"));
        assertEquals(f.session(), f.storedSession());
    }

    @Test
    void concurrentSeparateConnectionsHaveOnePreparationAndOneExactObservation() throws Exception {
        assertConcurrentPreparation(Fixture.create("race"));
    }

    @Test
    void cancellationWinningBeforeTheRowLockPreventsEveryPreparationWrite() throws Exception {
        assertCancellationBeforeLock(Fixture.create("cancel-race"));
    }

    static void assertConcurrentPreparation(Fixture f) throws Exception {
        var barrier = new CyclicBarrier(2);
        DataSource gated = new InterceptingDataSource(f.dataSource(), (sql, method) -> {
            if (sql.contains("factory_build_session") && sql.contains("FOR UPDATE") && method.equals("executeQuery")) {
                barrier.await(10, TimeUnit.SECONDS);
            }
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> f.transaction(gated).prepare(f.session(), f.order(), f.run(), f.staged()));
            var second = executor.submit(() -> f.transaction(gated).prepare(f.session(), f.order(), f.run(), f.staged()));
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertEquals(Set.of(PreparationDisposition.CREATED, PreparationDisposition.EXISTING_EXACT),
                    Set.of(a.disposition(), b.disposition()));
            assertEquals(a.workOrder(), b.workOrder());
            assertEquals(a.workerRun(), b.workerRun());
            assertEquals(1, f.count("factory_work_order"));
            assertEquals(1, f.count("factory_worker_run"));
            assertEquals(WorkerRunStatus.REQUESTED, a.workerRun().status());
        }
    }

    static void assertCancellationBeforeLock(Fixture f) throws Exception {
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        DataSource gated = new InterceptingDataSource(f.dataSource(), (sql, method) -> {
            if (sql.contains("factory_build_session") && sql.contains("FOR UPDATE") && method.equals("executeQuery")) {
                reached.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("test gate timed out");
            }
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> assertThrows(IllegalArgumentException.class, () -> f.transaction(gated)
                    .prepare(f.session(), f.order(), f.run(), f.staged())));
            assertTrue(reached.await(10, TimeUnit.SECONDS));
            BuildSession cancelled = f.session().requestCancellation(NOW.plusSeconds(2));
            assertTrue(new JdbcBuildSessionRepository(f.dataSource()).compareAndSet(f.session(), cancelled));
            release.countDown();
            future.get(15, TimeUnit.SECONDS);
            assertEquals(cancelled, f.storedSession());
            assertEquals(0, f.count("factory_work_order"));
            assertEquals(0, f.count("factory_worker_run"));
            assertEquals(2, f.count("factory_artifact"));
        } finally { release.countDown(); }
    }

    record Fixture(DataSource dataSource, BuildSession pristine, BuildSession session, Artifact requirements,
                   WorkOrder order, WorkerRunRecord run, List<Artifact> staged) {
        static Fixture create(String suffix) { return create(FactoryDatabaseMigrationsTest.h2("production_" + suffix), suffix); }

        static Fixture create(DataSource dataSource, String suffix) {
            FactoryDatabaseMigrations.migrate(dataSource);
            Artifact requirements = artifact("requirements-" + suffix, "bounded production requirements");
            BuildSession pristine = new BuildSession(new BuildSessionId("session-" + suffix), TENANT,
                    new ProjectId("project-" + suffix), ProductLineId.AGENT_PACK, "request-" + suffix, "operator",
                    BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER,
                    requirements.reference(), requirements.contentHash(), Optional.of("manager"), Optional.of("coding"),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 2, NOW,
                    NOW.plusSeconds(600), Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW);
            var transaction = new JdbcAgentPackWorkPreparationTransaction(dataSource, MAPPER,
                    Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC));
            transaction.accept(pristine, List.of(requirements));
            BuildSession session = copy(pristine, BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT,
                    1, NOW, NOW.plusSeconds(1));
            assertTrue(new JdbcBuildSessionRepository(dataSource).compareAndSet(pristine, session));
            var staged = new ArrayList<Artifact>();
            Artifact instruction = artifact("instruction-" + suffix, "Generate a bounded design");
            Artifact policy = artifact("policy-" + suffix, "bounded policy");
            staged.add(instruction); staged.add(policy);
            var input = MAPPER.createObjectNode();
            input.put("schemaVersion", CodingWorkerInputManifest.SCHEMA_VERSION);
            input.put("workOrderId", "work-" + suffix); input.put("buildSessionId", session.buildSessionId().value());
            input.put("skillId", "fixture-skill"); input.put("skillVersion", "1.0.0");
            for (String key : List.of("skill", "dependencyLock", "toolchainLock", "apiSignatureIndex", "productContractBundle", "requirementTestMatrix")) {
                Artifact lock = artifact(key.equals("toolchainLock") ? "toolchain-lock-" + suffix : key + "-" + suffix, "locked " + key);
                staged.add(lock);
                input.put(key.equals("skill") ? "skillArtifactRef" : key + "Ref", lock.reference().value());
                input.put(key + "Hash", lock.contentHash().sha256());
            }
            input.put("gateProfile", "factory-fixture-profile"); input.put("sourceLockAlgorithmId", "factory.source-lock.v1");
            input.putNull("repairLock");
            Artifact manifest = artifact("manifest-" + suffix, input.toString());
            staged.add(manifest);
            WorkOrder order = JdbcAgentPackWorkPreparationTransactionTest.order(session, suffix,
                    instruction.reference(), instruction.contentHash(), manifest.reference(),
                    manifest.contentHash(), policy.reference(), 1);
            return new Fixture(dataSource, pristine, session, requirements, order,
                    JdbcAgentPackWorkPreparationTransactionTest.run(session, order, suffix), List.copyOf(staged));
        }

        JdbcAgentPackWorkPreparationTransaction transaction() { return transaction(dataSource); }
        JdbcAgentPackWorkPreparationTransaction transaction(DataSource source) {
            return new JdbcAgentPackWorkPreparationTransaction(source, MAPPER, Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC));
        }
        PreparationOutcome prepare() { return transaction().prepare(session, order, run, staged); }
        BuildSession storedSession() { return new JdbcBuildSessionRepository(dataSource).find(TENANT, session.buildSessionId()).orElseThrow(); }
        int count(String table) {
            if (!Set.of("factory_artifact", "factory_work_order", "factory_worker_run", "factory_build_session", "factory_dispatch_outbox").contains(table)) {
                throw new IllegalArgumentException("unexpected test table");
            }
            try (var c = dataSource.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                r.next(); return r.getInt(1);
            } catch (SQLException failure) { throw new AssertionError(failure); }
        }
    }

    private static BuildSession copy(BuildSession a, BuildSessionStatus status, BuildSessionPhase phase, long version,
                                     Instant created, Instant updated) {
        return new BuildSession(a.buildSessionId(), a.tenantId(), a.projectId(), a.productLineId(), a.requestIdempotencyKey(),
                a.createdBy(), status, phase, a.requirementsArtifactRef(), a.requirementsHash(), a.selectedManagerWorkerBinding(),
                a.selectedCodingWorkerBinding(), a.currentBlueprintRef(), a.currentCandidateId(), a.currentCandidateHash(),
                a.currentCertificationId(), a.repairRound(), a.maxRepairRounds(), created, a.deadlineAt(),
                a.cancellationRequestedAt(), a.terminalCode(), a.terminalMessage(), version, created, updated);
    }

    private static WorkOrder order(BuildSession s, String suffix, ArtifactReference instruction, ContentHash instructionHash,
                                   ArtifactReference manifest, ContentHash manifestHash, ArtifactReference policy, int revision) {
        return new WorkOrder(new WorkOrderId("work-" + suffix), s.tenantId(), s.buildSessionId(), s.currentPhase().id(),
                "bounded phase", revision, Optional.empty(), Optional.empty(), Optional.empty(), instruction, instructionHash,
                manifest, manifestHash, "workspace", List.of("src"), List.of("src"), Set.of(new WorkerCapability("java")),
                "pack-candidate", "1", policy, NOW.plusSeconds(300), 1, "logical-" + suffix, WorkOrderCreatorType.SYSTEM,
                "factory", NOW.plusSeconds(2));
    }

    private static WorkerRunRecord run(BuildSession s, WorkOrder order, String suffix) {
        return new WorkerRunRecord(new WorkerRunId("run-" + suffix), s.tenantId(), s.buildSessionId(), order.workOrderId(),
                1, "coding", "1", new WorkerCapabilities(order.requiredCapabilities()), WorkerRunStatus.REQUESTED,
                Optional.empty(), "operation-" + suffix, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                order.deadlineAt(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0, order.createdAt(), order.createdAt());
    }

    private static Artifact artifact(String id, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try { return new Artifact(TENANT, new ArtifactReference("artifact:" + id), new ContentHash(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes))), "application/json", bytes); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    @FunctionalInterface interface SqlGate { void before(String sql, String method) throws Exception; }

    static final class InterceptingDataSource implements DataSource {
        private final DataSource delegate;
        private final SqlGate gate;
        InterceptingDataSource(DataSource delegate, SqlGate gate) { this.delegate = delegate; this.gate = gate; }
        public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
        public Connection getConnection(String user, String password) throws SQLException { return wrap(delegate.getConnection(user, password)); }
        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(connection, args);
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                                (statement, call, values) -> {
                                    gate.before(sql, call.getName());
                                    try { return call.invoke(result, values); }
                                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                                });
                    }
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
        public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        public Logger getParentLogger() { return Logger.getAnonymousLogger(); }
        public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }
}
