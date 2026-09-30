package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.application.work.WorkerDispatchAcceptanceTransaction;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import javax.sql.DataSource;

/** Atomically accepts an exact external dispatch into the WorkerRun and outbox ledgers. */
public final class JdbcWorkerDispatchAcceptanceTransaction
        implements WorkerDispatchAcceptanceTransaction {
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

    public JdbcWorkerDispatchAcceptanceTransaction(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcWorkerDispatchAcceptanceTransaction(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.workerRuns = new JdbcWorkerRunRepository(dataSource, this.objectMapper);
        this.outbox = new JdbcDispatchOutboxRepository(dataSource);
    }

    @Override
    public boolean accept(
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String externalSessionRef,
            Instant acceptedAt,
            Instant recordedAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        Objects.requireNonNull(outboxId, "outboxId");
        operationId = requireText(operationId, "operationId");
        claimToken = requireText(claimToken, "claimToken");
        externalSessionRef = requireText(externalSessionRef, "externalSessionRef");
        Instant canonicalAcceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt")
                .truncatedTo(ChronoUnit.MICROS);
        Instant canonicalRecordedAt = Objects.requireNonNull(recordedAt, "recordedAt")
                .truncatedTo(ChronoUnit.MICROS);
        if (canonicalRecordedAt.isBefore(canonicalAcceptedAt)) {
            throw new IllegalArgumentException("recordedAt must not precede acceptedAt");
        }
        String exactOperationId = operationId;
        String exactClaimToken = claimToken;
        String exactExternalSessionRef = externalSessionRef;
        return withConnection(dataSource, "accept Worker dispatch", connection -> inTransaction(
                connection,
                tenantId,
                workerRunId,
                outboxId,
                exactOperationId,
                exactClaimToken,
                exactExternalSessionRef,
                canonicalAcceptedAt,
                canonicalRecordedAt));
    }

    private boolean inTransaction(
            Connection connection,
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String externalSessionRef,
            Instant acceptedAt,
            Instant recordedAt) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            WorkerRunRecord initialRun = workerRuns.find(connection, tenantId, workerRunId)
                    .orElse(null);
            if (initialRun == null || !lockAndValidateActionOwner(connection, initialRun)) {
                connection.rollback();
                return false;
            }
            WorkerRunRecord observedRun = workerRuns.find(connection, tenantId, workerRunId, true)
                    .orElse(null);
            DispatchOutbox claimed = outbox.find(connection, tenantId, outboxId, true).orElse(null);
            if (!initialRun.equals(observedRun)
                    || !exactBinding(observedRun, claimed, outboxId, operationId, claimToken)) {
                connection.rollback();
                return false;
            }

            DispatchOutbox dispatched = claimed.dispatched(
                    claimToken, "WORKER_DISPATCH_ACCEPTED", recordedAt);
            boolean workerAccepted = observedRun.status() == WorkerRunStatus.CANCEL_REQUESTED
                    || workerRuns.compareAndSet(
                            connection,
                            observedRun,
                            observedRun.awaitExternal(externalSessionRef, acceptedAt));
            if (!workerAccepted || !outbox.compareAndSet(connection, claimed, dispatched)) {
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

    private static boolean exactBinding(
            WorkerRunRecord workerRun,
            DispatchOutbox claimed,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken) {
        return workerRun != null
                && claimed != null
                && (workerRun.status() == WorkerRunStatus.DISPATCHING
                        || workerRun.status() == WorkerRunStatus.CANCEL_REQUESTED)
                && workerRun.operationId().equals(operationId)
                && workerRun.dispatchOutboxId().filter(outboxId::equals).isPresent()
                && workerRun.actionRunId().isPresent()
                && workerRun.attemptTokenHash().isPresent()
                && claimed.status() == DispatchOutboxStatus.DISPATCHING
                && claimed.claimToken().filter(claimToken::equals).isPresent()
                && claimed.operationType().equals(WorkerOutboxOperations.DISPATCH)
                && claimed.aggregateType().equals("WORKER_RUN")
                && claimed.aggregateId().equals(workerRun.workerRunId().value())
                && claimed.operationId().equals(operationId)
                && claimed.outboxId().equals(outboxId)
                && claimed.tenantId().equals(workerRun.tenantId());
    }

    private boolean lockAndValidateActionOwner(Connection connection, WorkerRunRecord workerRun)
            throws SQLException {
        if (workerRun.actionRunId().isEmpty() || workerRun.attemptTokenHash().isEmpty()) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACTION_OWNER)) {
            statement.setString(1, workerRun.actionRunId().orElseThrow());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !workerRun.tenantId().value().equals(resultSet.getString("tenant_id"))
                        || !WorkerDispatchAction.ACTION_ID.equals(resultSet.getString("action_id"))
                        || !workerRun.operationId().equals(resultSet.getString("external_operation_id"))
                        || !workerRun.attemptTokenHash().orElseThrow().equals(
                                WorkerDispatchOperationIds.hashAttemptToken(
                                        resultSet.getString("attempt_token")))) {
                    return false;
                }
                String actionStatus = resultSet.getString("status");
                if (workerRun.status() == WorkerRunStatus.DISPATCHING
                        ? !"WAITING_EXTERNAL".equals(actionStatus)
                        : workerRun.status() != WorkerRunStatus.CANCEL_REQUESTED
                                || (!"WAITING_EXTERNAL".equals(actionStatus)
                                        && !"CANCELLED".equals(actionStatus))) {
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

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
