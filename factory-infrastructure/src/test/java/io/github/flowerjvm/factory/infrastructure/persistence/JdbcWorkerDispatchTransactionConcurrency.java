package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Shared H2/PostgreSQL acceptance checks for the Worker dispatch transaction lock boundary. */
final class JdbcWorkerDispatchTransactionConcurrency {
    private static final Instant PREPARED_AT = PersistenceFixtures.NOW.plusSeconds(1);

    private JdbcWorkerDispatchTransactionConcurrency() {}

    static void assertPhaseChangeBeforeDispatchPreventsOutbox(DataSource dataSource, String prefix)
            throws Exception {
        Fixture fixture = seed(dataSource, prefix + "-phase-first", 1, fullCapabilities());
        QueryGateDataSource gated = new QueryGateDataSource(dataSource, GatePosition.BEFORE_QUERY);
        MutableClock clock = new MutableClock(PREPARED_AT);
        var transaction = transaction(gated, clock);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> dispatch = executor.submit(() -> transaction.prepare(
                    fixture.workOrder(),
                    fixture.workerRun(),
                    fixture.dispatching(),
                    fixture.outbox()));
            gated.awaitReached();
            BuildSession changed = transition(
                    fixture.buildSession(),
                    BuildSessionStatus.RUNNING,
                    BuildSessionPhase.TEST,
                    Optional.empty());
            assertTrue(new JdbcBuildSessionRepository(dataSource)
                    .compareAndSet(fixture.buildSession(), changed));
            gated.release();

            assertFalse(dispatch.get(10, TimeUnit.SECONDS));
            assertNoDispatch(dataSource, fixture);
            assertEquals(
                    changed,
                    new JdbcBuildSessionRepository(dataSource)
                            .find(changed.tenantId(), changed.buildSessionId())
                            .orElseThrow());
        } finally {
            gated.release();
        }
    }

    static void assertCancellationBeforeDispatchPreventsOutbox(DataSource dataSource, String prefix) {
        Fixture fixture = seed(dataSource, prefix + "-cancel-first", 1, fullCapabilities());
        BuildSession cancelled = transition(
                fixture.buildSession(),
                BuildSessionStatus.CANCELLING,
                fixture.buildSession().currentPhase(),
                Optional.of(PREPARED_AT));
        assertTrue(new JdbcBuildSessionRepository(dataSource)
                .compareAndSet(fixture.buildSession(), cancelled));

        assertFalse(transaction(dataSource, new MutableClock(PREPARED_AT))
                .prepare(
                        fixture.workOrder(),
                        fixture.workerRun(),
                        fixture.dispatching(),
                        fixture.outbox()));

        assertNoDispatch(dataSource, fixture);
        assertEquals(
                cancelled,
                new JdbcBuildSessionRepository(dataSource)
                        .find(cancelled.tenantId(), cancelled.buildSessionId())
                        .orElseThrow());
    }

    static void assertBindingChangeBeforeDispatchPreventsOutbox(DataSource dataSource, String prefix) {
        Fixture fixture = seed(dataSource, prefix + "-binding-first", 1, fullCapabilities());
        BuildSession rebound = transition(
                fixture.buildSession(),
                fixture.buildSession().status(),
                fixture.buildSession().currentPhase(),
                fixture.buildSession().cancellationRequestedAt(),
                Optional.of("replacement-coding-binding"));
        assertTrue(new JdbcBuildSessionRepository(dataSource)
                .compareAndSet(fixture.buildSession(), rebound));

        assertFalse(transaction(dataSource, new MutableClock(PREPARED_AT))
                .prepare(
                        fixture.workOrder(),
                        fixture.workerRun(),
                        fixture.dispatching(),
                        fixture.outbox()));

        assertNoDispatch(dataSource, fixture);
        assertEquals(
                rebound,
                new JdbcBuildSessionRepository(dataSource)
                        .find(rebound.tenantId(), rebound.buildSessionId())
                        .orElseThrow());
    }

    static void assertDispatchBeforeCancellationSerializes(DataSource dataSource, String prefix)
            throws Exception {
        Fixture fixture = seed(dataSource, prefix + "-dispatch-first", 1, fullCapabilities());
        QueryGateDataSource gated = new QueryGateDataSource(dataSource, GatePosition.AFTER_QUERY);
        MutableClock clock = new MutableClock(PREPARED_AT);
        var transaction = transaction(gated, clock);
        BuildSession cancelling = transition(
                fixture.buildSession(),
                BuildSessionStatus.CANCELLING,
                fixture.buildSession().currentPhase(),
                Optional.of(PREPARED_AT));
        UpdateProbeDataSource cancellationProbe = new UpdateProbeDataSource(dataSource);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> dispatch = executor.submit(() -> transaction.prepare(
                    fixture.workOrder(),
                    fixture.workerRun(),
                    fixture.dispatching(),
                    fixture.outbox()));
            gated.awaitReached();
            Future<Boolean> cancellation = executor.submit(() ->
                    new JdbcBuildSessionRepository(cancellationProbe)
                            .compareAndSet(fixture.buildSession(), cancelling));
            cancellationProbe.awaitUpdateAttempted();
            assertThrows(TimeoutException.class, () -> cancellation.get(200, TimeUnit.MILLISECONDS));
            gated.release();

            assertTrue(dispatch.get(10, TimeUnit.SECONDS));
            assertTrue(cancellation.get(10, TimeUnit.SECONDS));
            assertEquals(
                    fixture.dispatching(),
                    new JdbcWorkerRunRepository(dataSource)
                            .find(fixture.workerRun().tenantId(), fixture.workerRun().workerRunId())
                            .orElseThrow());
            assertEquals(
                    fixture.outbox(),
                    new JdbcDispatchOutboxRepository(dataSource)
                            .find(fixture.outbox().tenantId(), fixture.outbox().outboxId())
                            .orElseThrow());
            assertEquals(
                    cancelling,
                    new JdbcBuildSessionRepository(dataSource)
                            .find(cancelling.tenantId(), cancelling.buildSessionId())
                            .orElseThrow());
        } finally {
            gated.release();
        }
    }

    static void assertDeadlineElapsedWhileWaitingPreventsOutbox(DataSource dataSource, String prefix)
            throws Exception {
        Fixture fixture = seed(dataSource, prefix + "-deadline", 1, fullCapabilities());
        QueryGateDataSource gated = new QueryGateDataSource(dataSource, GatePosition.AFTER_QUERY);
        MutableClock clock = new MutableClock(PREPARED_AT);
        var transaction = transaction(gated, clock);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> dispatch = executor.submit(() -> transaction.prepare(
                    fixture.workOrder(),
                    fixture.workerRun(),
                    fixture.dispatching(),
                    fixture.outbox()));
            gated.awaitReached();
            clock.set(fixture.workOrder().deadlineAt());
            gated.release();

            assertFalse(dispatch.get(10, TimeUnit.SECONDS));
            assertNoDispatch(dataSource, fixture);
        } finally {
            gated.release();
        }
    }

    static void assertOutboxFailureRollsBackWorkerRun(DataSource dataSource, String prefix) {
        Fixture fixture = seed(dataSource, prefix + "-rollback", 1, fullCapabilities());
        DispatchOutbox conflict = new DispatchOutbox(
                new DispatchOutboxId("conflict-" + prefix),
                fixture.outbox().tenantId(),
                fixture.outbox().operationType(),
                fixture.outbox().aggregateType(),
                fixture.outbox().aggregateId(),
                fixture.outbox().operationId(),
                fixture.outbox().payloadArtifactRef(),
                fixture.outbox().status(),
                fixture.outbox().availableAt(),
                fixture.outbox().attemptCount(),
                fixture.outbox().lastCode(),
                fixture.outbox().version(),
                fixture.outbox().createdAt(),
                fixture.outbox().updatedAt());
        new JdbcDispatchOutboxRepository(dataSource).create(conflict);

        assertThrows(DuplicateLedgerRecordException.class, () -> transaction(dataSource, new MutableClock(PREPARED_AT))
                .prepare(
                        fixture.workOrder(),
                        fixture.workerRun(),
                        fixture.dispatching(),
                        fixture.outbox()));

        assertEquals(
                fixture.workerRun(),
                new JdbcWorkerRunRepository(dataSource)
                        .find(fixture.workerRun().tenantId(), fixture.workerRun().workerRunId())
                        .orElseThrow());
        assertTrue(new JdbcDispatchOutboxRepository(dataSource)
                .find(fixture.outbox().tenantId(), fixture.outbox().outboxId())
                .isEmpty());
        assertEquals(
                conflict,
                new JdbcDispatchOutboxRepository(dataSource)
                        .find(conflict.tenantId(), conflict.outboxId())
                        .orElseThrow());
    }

    static void assertAttemptAndCapabilityChecksPreventOutbox(DataSource dataSource, String prefix) {
        Fixture tooManyAttempts = seed(dataSource, prefix + "-attempt", 3, fullCapabilities());
        assertFalse(transaction(dataSource, new MutableClock(PREPARED_AT))
                .prepare(
                        tooManyAttempts.workOrder(),
                        tooManyAttempts.workerRun(),
                        tooManyAttempts.dispatching(),
                        tooManyAttempts.outbox()));
        assertNoDispatch(dataSource, tooManyAttempts);

        Fixture missingCapability = seed(
                dataSource,
                prefix + "-capability",
                1,
                new WorkerCapabilities(Set.of(new WorkerCapability("java"))));
        assertFalse(transaction(dataSource, new MutableClock(PREPARED_AT))
                .prepare(
                        missingCapability.workOrder(),
                        missingCapability.workerRun(),
                        missingCapability.dispatching(),
                        missingCapability.outbox()));
        assertNoDispatch(dataSource, missingCapability);
    }

    private static JdbcWorkerDispatchTransaction transaction(DataSource dataSource, Clock clock) {
        return new JdbcWorkerDispatchTransaction(dataSource, new ObjectMapper(), clock);
    }

    private static Fixture seed(
            DataSource dataSource,
            String suffix,
            int attemptNo,
            WorkerCapabilities capabilities) {
        BuildSession buildSession = PersistenceFixtures.buildSession(
                suffix,
                "request-" + suffix,
                BuildSessionPhase.GENERATE_CANDIDATE);
        WorkOrder workOrder = PersistenceFixtures.workOrder(buildSession, suffix);
        WorkerRunRecord base = PersistenceFixtures.workerRun(
                buildSession,
                workOrder,
                suffix,
                attemptNo,
                "operation-" + suffix);
        WorkerRunRecord workerRun = withCapabilities(base, capabilities);
        DispatchOutbox outbox = outbox(workOrder, workerRun, suffix);
        WorkerRunRecord dispatching = workerRun.startDispatch(
                outbox.outboxId(),
                "action-run-" + suffix,
                io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds
                        .hashAttemptToken("attempt-token-" + suffix),
                PREPARED_AT);
        new JdbcBuildSessionRepository(dataSource).create(buildSession);
        new JdbcWorkOrderRepository(dataSource).create(workOrder);
        new JdbcWorkerRunRepository(dataSource).create(workerRun);
        ActionRunFixtures.createWorkerDispatchOwner(
                dataSource,
                workerRun,
                dispatching.actionRunId().orElseThrow());
        return new Fixture(buildSession, workOrder, workerRun, dispatching, outbox);
    }

    private static WorkerRunRecord withCapabilities(
            WorkerRunRecord workerRun,
            WorkerCapabilities capabilities) {
        return new WorkerRunRecord(
                workerRun.workerRunId(),
                workerRun.tenantId(),
                workerRun.buildSessionId(),
                workerRun.workOrderId(),
                workerRun.attemptNo(),
                workerRun.workerBindingId(),
                workerRun.workerAdapterVersion(),
                capabilities,
                workerRun.status(),
                workerRun.actionRunId(),
                workerRun.operationId(),
                workerRun.attemptTokenHash(),
                workerRun.externalSessionRef(),
                workerRun.dispatchOutboxId(),
                workerRun.startedAt(),
                workerRun.deadlineAt(),
                workerRun.heartbeatAt(),
                workerRun.cancelRequestedAt(),
                workerRun.completedAt(),
                workerRun.resultArtifactManifestRef(),
                workerRun.resultHash(),
                workerRun.code(),
                workerRun.message(),
                workerRun.retryDisposition(),
                workerRun.version(),
                workerRun.createdAt(),
                workerRun.updatedAt());
    }

    private static DispatchOutbox outbox(
            WorkOrder workOrder,
            WorkerRunRecord workerRun,
            String suffix) {
        return new DispatchOutbox(
                new DispatchOutboxId("outbox-" + suffix),
                workerRun.tenantId(),
                "WORKER_DISPATCH",
                "WORKER_RUN",
                workerRun.workerRunId().value(),
                workerRun.operationId(),
                workOrder.inputArtifactManifestRef(),
                DispatchOutboxStatus.PENDING,
                PREPARED_AT,
                0,
                Optional.empty(),
                0,
                PREPARED_AT,
                PREPARED_AT);
    }

    private static WorkerCapabilities fullCapabilities() {
        return new WorkerCapabilities(Set.of(
                new WorkerCapability("java"),
                new WorkerCapability("maven")));
    }

    private static BuildSession transition(
            BuildSession expected,
            BuildSessionStatus status,
            BuildSessionPhase phase,
            Optional<Instant> cancellationRequestedAt) {
        return transition(
                expected,
                status,
                phase,
                cancellationRequestedAt,
                expected.selectedCodingWorkerBinding());
    }

    private static BuildSession transition(
            BuildSession expected,
            BuildSessionStatus status,
            BuildSessionPhase phase,
            Optional<Instant> cancellationRequestedAt,
            Optional<String> selectedCodingWorkerBinding) {
        return new BuildSession(
                expected.buildSessionId(),
                expected.tenantId(),
                expected.projectId(),
                expected.productLineId(),
                expected.requestIdempotencyKey(),
                expected.createdBy(),
                status,
                phase,
                expected.requirementsArtifactRef(),
                expected.requirementsHash(),
                expected.selectedManagerWorkerBinding(),
                selectedCodingWorkerBinding,
                expected.currentBlueprintRef(),
                expected.currentCandidateId(),
                expected.currentCandidateHash(),
                expected.currentCertificationId(),
                expected.repairRound(),
                expected.maxRepairRounds(),
                expected.startedAt(),
                expected.deadlineAt(),
                cancellationRequestedAt,
                expected.terminalCode(),
                expected.terminalMessage(),
                expected.version() + 1,
                expected.createdAt(),
                PREPARED_AT);
    }

    private static void assertNoDispatch(DataSource dataSource, Fixture fixture) {
        assertEquals(
                fixture.workerRun(),
                new JdbcWorkerRunRepository(dataSource)
                        .find(fixture.workerRun().tenantId(), fixture.workerRun().workerRunId())
                        .orElseThrow());
        assertTrue(new JdbcDispatchOutboxRepository(dataSource)
                .find(fixture.outbox().tenantId(), fixture.outbox().outboxId())
                .isEmpty());
    }

    private record Fixture(
            BuildSession buildSession,
            WorkOrder workOrder,
            WorkerRunRecord workerRun,
            WorkerRunRecord dispatching,
            DispatchOutbox outbox) {}

    private enum GatePosition {
        BEFORE_QUERY,
        AFTER_QUERY
    }

    /** Test-only DataSource proxy that pauses exactly around the BuildSession FOR UPDATE query. */
    private static final class QueryGateDataSource implements DataSource {
        private final DataSource delegate;
        private final GatePosition position;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private QueryGateDataSource(DataSource delegate, GatePosition position) {
            this.delegate = delegate;
            this.position = position;
        }

        void awaitReached() throws InterruptedException {
            assertTrue(reached.await(10, TimeUnit.SECONDS), "BuildSession lock query was not reached");
        }

        void release() {
            released.countDown();
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        Object value = invoke(method, connection, arguments);
                        if ("prepareStatement".equals(method.getName())
                                && arguments != null
                                && arguments.length > 0
                                && arguments[0] instanceof String sql
                                && sql.contains("FROM factory_build_session")
                                && sql.contains("FOR UPDATE")
                                && value instanceof PreparedStatement statement) {
                            return wrap(statement);
                        }
                        return value;
                    });
        }

        private PreparedStatement wrap(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    (proxy, method, arguments) -> {
                        if (!"executeQuery".equals(method.getName())) {
                            return invoke(method, statement, arguments);
                        }
                        if (position == GatePosition.BEFORE_QUERY) {
                            pause();
                            return invoke(method, statement, arguments);
                        }
                        Object result = invoke(method, statement, arguments);
                        pause();
                        return result;
                    });
        }

        private void pause() throws SQLException {
            reached.countDown();
            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new SQLException("timed out waiting to release BuildSession lock query");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted while coordinating BuildSession lock query", exception);
            }
        }

        private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    /** Signals exactly when a BuildSession CAS reaches JDBC execution and may block on a row lock. */
    private static final class UpdateProbeDataSource implements DataSource {
        private final DataSource delegate;
        private final CountDownLatch updateAttempted = new CountDownLatch(1);

        private UpdateProbeDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        void awaitUpdateAttempted() throws InterruptedException {
            assertTrue(
                    updateAttempted.await(10, TimeUnit.SECONDS),
                    "BuildSession update did not reach executeUpdate");
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        Object value = invoke(method, connection, arguments);
                        if ("prepareStatement".equals(method.getName())
                                && arguments != null
                                && arguments.length > 0
                                && arguments[0] instanceof String sql
                                && sql.contains("UPDATE factory_build_session")
                                && value instanceof PreparedStatement statement) {
                            return wrap(statement);
                        }
                        return value;
                    });
        }

        private PreparedStatement wrap(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    (proxy, method, arguments) -> {
                        if ("executeUpdate".equals(method.getName())) {
                            updateAttempted.countDown();
                        }
                        return invoke(method, statement, arguments);
                    });
        }

        private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        void set(Instant next) {
            instant.set(next);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock is fixed to UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
