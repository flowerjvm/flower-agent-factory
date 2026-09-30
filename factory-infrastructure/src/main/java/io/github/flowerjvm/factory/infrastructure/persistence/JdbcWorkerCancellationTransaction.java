package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.application.work.WorkerCancellationTransaction;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/** Persists a WAITING_EXTERNAL cancellation request and its command before the hook returns. */
public final class JdbcWorkerCancellationTransaction implements WorkerCancellationTransaction {
    private static final String LOCK_ACTION_OWNER = """
            SELECT tenant_id, action_id, input_json, status, attempt_token, external_operation_id
            FROM action_run
            WHERE run_id = ?
            FOR UPDATE
            """;

    private final DataSource dataSource;
    private final JdbcWorkerRunRepository workerRuns;
    private final JdbcDispatchOutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public JdbcWorkerCancellationTransaction(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcWorkerCancellationTransaction(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.workerRuns = new JdbcWorkerRunRepository(dataSource, this.objectMapper);
        this.outbox = new JdbcDispatchOutboxRepository(dataSource);
    }

    @Override
    public boolean prepare(
            WorkOrder workOrder,
            WorkerRunRecord expected,
            WorkerRunRecord cancelRequested,
            DispatchOutbox cancelOutbox) {
        Objects.requireNonNull(workOrder, "workOrder");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(cancelRequested, "cancelRequested");
        Objects.requireNonNull(cancelOutbox, "cancelOutbox");
        if (!exactRequest(workOrder, expected, cancelRequested, cancelOutbox)) return false;
        return withConnection(dataSource, "prepare Worker cancellation", connection ->
                inTransaction(connection, expected, cancelRequested, cancelOutbox));
    }

    private boolean inTransaction(
            Connection connection,
            WorkerRunRecord expected,
            WorkerRunRecord cancelRequested,
            DispatchOutbox cancelOutbox) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            WorkerRunRecord current = workerRuns.find(
                            connection, expected.tenantId(), expected.workerRunId())
                    .orElse(null);
            if (!expected.equals(current) || !lockAndValidateActionOwner(connection, expected)) {
                connection.rollback();
                return false;
            }
            if (!workerRuns.compareAndSet(connection, expected, cancelRequested)) {
                connection.rollback();
                return false;
            }
            outbox.create(connection, cancelOutbox);
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static boolean exactRequest(
            WorkOrder workOrder,
            WorkerRunRecord expected,
            WorkerRunRecord cancelRequested,
            DispatchOutbox cancelOutbox) {
        return (expected.status() == WorkerRunStatus.DISPATCHING
                    || expected.status() == WorkerRunStatus.WAITING_EXTERNAL)
                && expected.tenantId().equals(workOrder.tenantId())
                && expected.buildSessionId().equals(workOrder.buildSessionId())
                && expected.workOrderId().equals(workOrder.workOrderId())
                && cancelRequested.status() == WorkerRunStatus.CANCEL_REQUESTED
                && cancelRequested.workerRunId().equals(expected.workerRunId())
                && cancelRequested.tenantId().equals(expected.tenantId())
                && cancelRequested.buildSessionId().equals(expected.buildSessionId())
                && cancelRequested.workOrderId().equals(expected.workOrderId())
                && cancelRequested.operationId().equals(expected.operationId())
                && cancelRequested.version() == expected.version() + 1
                && cancelRequested.cancelRequestedAt().isPresent()
                && cancelOutbox.tenantId().equals(expected.tenantId())
                && WorkerOutboxOperations.CANCEL.equals(cancelOutbox.operationType())
                && "WORKER_RUN".equals(cancelOutbox.aggregateType())
                && expected.workerRunId().value().equals(cancelOutbox.aggregateId())
                && expected.operationId().equals(cancelOutbox.operationId())
                && workOrder.inputArtifactManifestRef().equals(cancelOutbox.payloadArtifactRef())
                && cancelOutbox.status() == DispatchOutboxStatus.PENDING
                && cancelOutbox.attemptCount() == 0
                && cancelOutbox.version() == 0
                && expected.dispatchOutboxId().filter(cancelOutbox.outboxId()::equals).isEmpty();
    }

    private boolean lockAndValidateActionOwner(Connection connection, WorkerRunRecord workerRun)
            throws SQLException {
        if (workerRun.actionRunId().isEmpty() || workerRun.attemptTokenHash().isEmpty()) return false;
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACTION_OWNER)) {
            statement.setString(1, workerRun.actionRunId().orElseThrow());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !workerRun.tenantId().value().equals(resultSet.getString("tenant_id"))
                        || !WorkerDispatchAction.ACTION_ID.equals(resultSet.getString("action_id"))
                        || !"WAITING_EXTERNAL".equals(resultSet.getString("status"))
                        || !workerRun.operationId().equals(resultSet.getString("external_operation_id"))
                        || !workerRun.attemptTokenHash().orElseThrow().equals(
                                WorkerDispatchOperationIds.hashAttemptToken(
                                        resultSet.getString("attempt_token")))) {
                    return false;
                }
                JsonNode input;
                try {
                    input = objectMapper.readTree(resultSet.getString("input_json"));
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                    throw new SQLException("ActionRun input_json is not valid JSON", exception);
                }
                return workerRun.workerRunId().value().equals(
                                input.path(WorkerDispatchAction.WORKER_RUN_ID).asText())
                        && workerRun.workOrderId().value().equals(
                                input.path(WorkerDispatchAction.WORK_ORDER_ID).asText());
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
