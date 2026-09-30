package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchTransaction;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Atomically records a WorkerRun dispatch transition and its outbound intent.
 *
 * <p>The remote send is deliberately outside this transaction and remains at-least-once capable.
 */
public final class JdbcWorkerDispatchTransaction implements WorkerDispatchTransaction {
    private static final String LOCK_BUILD_SESSION = """
            SELECT tenant_id, build_session_id, status, current_phase,
                   selected_coding_worker_binding, cancellation_requested_at, deadline_at
            FROM factory_build_session
            WHERE tenant_id = ? AND build_session_id = ?
            FOR UPDATE
            """;

    private final DataSource dataSource;
    private final JdbcWorkerRunRepository workerRuns;
    private final JdbcDispatchOutboxRepository outbox;
    private final Clock clock;

    public JdbcWorkerDispatchTransaction(DataSource dataSource) {
        this(dataSource, new ObjectMapper(), Clock.systemUTC());
    }

    public JdbcWorkerDispatchTransaction(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, Clock.systemUTC());
    }

    public JdbcWorkerDispatchTransaction(
            DataSource dataSource,
            ObjectMapper objectMapper,
            Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.workerRuns = new JdbcWorkerRunRepository(dataSource, mapper);
        this.outbox = new JdbcDispatchOutboxRepository(dataSource);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns false when the WorkerRun version lost the CAS race; no outbox row is then committed. */
    @Override
    public boolean prepare(
            WorkOrder workOrder,
            WorkerRunRecord expected,
            WorkerRunRecord dispatching,
            DispatchOutbox dispatchOutbox) {
        Objects.requireNonNull(workOrder, "workOrder");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(dispatching, "dispatching");
        Objects.requireNonNull(dispatchOutbox, "dispatchOutbox");
        if (!isRequestConsistent(workOrder, expected, dispatching, dispatchOutbox)) {
            return false;
        }
        return withConnection(dataSource, "prepare Worker dispatch", connection ->
                inTransaction(connection, workOrder, expected, dispatching, dispatchOutbox));
    }

    private boolean inTransaction(
            Connection connection,
            WorkOrder workOrder,
            WorkerRunRecord expected,
            WorkerRunRecord dispatching,
            DispatchOutbox dispatchOutbox)
            throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (!lockAndValidateBuildSession(connection, workOrder, expected)) {
                connection.rollback();
                return false;
            }
            // The BuildSession lock serializes every legal REQUESTED -> DISPATCHING path.
            // Re-read before inserting the FK parent so a stale contender returns false
            // without colliding with the winner's deterministic outbox identity.
            if (workerRuns.find(connection, expected.tenantId(), expected.workerRunId())
                    .filter(expected::equals)
                    .isEmpty()) {
                connection.rollback();
                return false;
            }
            // V9 binds WorkerRun to its exact dispatch intent with an immediate composite FK.
            // Insert the intent first; a lost WorkerRun CAS rolls the same transaction back, so
            // no orphan outbox effect can commit.
            outbox.create(connection, dispatchOutbox);
            if (!workerRuns.compareAndSetDispatchClaim(connection, expected, dispatching)) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static boolean isRequestConsistent(
            WorkOrder workOrder,
            WorkerRunRecord expected,
            WorkerRunRecord dispatching,
            DispatchOutbox dispatchOutbox) {
        return workOrder.tenantId().equals(expected.tenantId())
                && workOrder.buildSessionId().equals(expected.buildSessionId())
                && workOrder.workOrderId().equals(expected.workOrderId())
                && expected.attemptNo() <= workOrder.maxAttempts()
                && expected.workerCapabilitySnapshot().supportsAll(workOrder.requiredCapabilities())
                && expected.status() == WorkerRunStatus.REQUESTED
                && expected.actionRunId().isEmpty()
                && expected.attemptTokenHash().isEmpty()
                && expected.dispatchOutboxId().isEmpty()
                && expected.startedAt().isEmpty()
                && dispatching.workerRunId().equals(expected.workerRunId())
                && dispatching.tenantId().equals(expected.tenantId())
                && dispatching.buildSessionId().equals(expected.buildSessionId())
                && dispatching.workOrderId().equals(expected.workOrderId())
                && dispatching.status() == WorkerRunStatus.DISPATCHING
                && dispatching.actionRunId().isPresent()
                && dispatching.attemptTokenHash().isPresent()
                && dispatching.operationId().equals(expected.operationId())
                && dispatching.version() == expected.version() + 1
                && expected.tenantId().equals(dispatchOutbox.tenantId())
                && "WORKER_DISPATCH".equals(dispatchOutbox.operationType())
                && "WORKER_RUN".equals(dispatchOutbox.aggregateType())
                && expected.workerRunId().value().equals(dispatchOutbox.aggregateId())
                && expected.operationId().equals(dispatchOutbox.operationId())
                && workOrder.inputArtifactManifestRef().equals(dispatchOutbox.payloadArtifactRef())
                && dispatchOutbox.status() == DispatchOutboxStatus.PENDING
                && dispatchOutbox.attemptCount() == 0
                && dispatchOutbox.version() == 0
                && dispatching.dispatchOutboxId().filter(dispatchOutbox.outboxId()::equals).isPresent();
    }

    private boolean lockAndValidateBuildSession(
            Connection connection,
            WorkOrder workOrder,
            WorkerRunRecord expected)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_BUILD_SESSION)) {
            statement.setString(1, expected.tenantId().value());
            statement.setString(2, expected.buildSessionId().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                Instant lockedAt = clock.instant();
                String status = resultSet.getString("status");
                String selectedBinding = resultSet.getString("selected_coding_worker_binding");
                return expected.tenantId().value().equals(resultSet.getString("tenant_id"))
                        && expected.buildSessionId().value().equals(resultSet.getString("build_session_id"))
                        && ("RUNNING".equals(status) || "REPAIRING".equals(status))
                        && resultSet.getTimestamp("cancellation_requested_at") == null
                        && workOrder.phase().equals(resultSet.getString("current_phase"))
                        && expected.workerBindingId().equals(selectedBinding)
                        && lockedAt.isBefore(getInstant(resultSet, "deadline_at"))
                        && lockedAt.isBefore(workOrder.deadlineAt())
                        && lockedAt.isBefore(expected.deadlineAt());
            }
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
