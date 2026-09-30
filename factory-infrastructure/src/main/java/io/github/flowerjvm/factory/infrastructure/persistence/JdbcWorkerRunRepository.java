package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.requireCas;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.requireSame;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC WorkerRun ledger with an atomic tenant/id/version mutation predicate. */
public final class JdbcWorkerRunRepository implements WorkerRunRepository {
    private static final String INSERT = """
            INSERT INTO factory_worker_run (
                worker_run_id, tenant_id, build_session_id, work_order_id, attempt_no,
                worker_binding_id, worker_adapter_version, worker_capability_snapshot_json,
                status, active_owner_key, action_run_id, operation_id, attempt_token_hash, external_session_ref,
                dispatch_outbox_id, started_at, deadline_at, heartbeat_at, cancel_requested_at,
                completed_at, result_artifact_manifest_ref, result_hash, code, message,
                retry_disposition, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND = """
            SELECT * FROM factory_worker_run
            WHERE tenant_id = ? AND worker_run_id = ?
            """;

    private static final String FIND_LATEST_BY_WORK_ORDER = """
            SELECT * FROM factory_worker_run
            WHERE tenant_id = ? AND work_order_id = ?
            ORDER BY attempt_no DESC, created_at DESC, worker_run_id DESC
            FETCH FIRST 1 ROW ONLY
            """;

    private static final String FIND_ACTIVE_FOR_RECONCILIATION = """
            SELECT * FROM factory_worker_run
            WHERE status IN ('DISPATCHING', 'WAITING_EXTERNAL', 'CANCEL_REQUESTED')
              AND updated_at <= ?
            ORDER BY deadline_at, updated_at, tenant_id, worker_run_id
            FETCH FIRST ? ROWS ONLY
            """;

    private static final String FIND_WAITING_EXTERNAL_FOR_STATUS_POLL = """
            SELECT * FROM factory_worker_run
            WHERE status = 'WAITING_EXTERNAL'
              AND updated_at <= ?
            ORDER BY deadline_at, updated_at, tenant_id, worker_run_id
            FETCH FIRST ? ROWS ONLY
            """;

    private static final String FIND_CANCELLATION_RECOVERY_CANDIDATES = """
            SELECT wr.*
            FROM factory_worker_run wr
            JOIN factory_work_order wo
              ON wo.tenant_id = wr.tenant_id
             AND wo.work_order_id = wr.work_order_id
             AND wo.build_session_id = wr.build_session_id
            JOIN factory_build_session bs
              ON bs.tenant_id = wr.tenant_id
             AND bs.build_session_id = wr.build_session_id
             AND bs.current_phase = wo.phase
             AND bs.selected_coding_worker_binding = wr.worker_binding_id
            JOIN action_run ar
              ON ar.run_id = wr.action_run_id
             AND ar.tenant_id = wr.tenant_id
             AND ar.action_id = 'factory.worker.dispatch'
             AND ar.external_operation_id = wr.operation_id
            WHERE wr.status IN ('DISPATCHING', 'WAITING_EXTERNAL', 'CANCEL_REQUESTED', 'RECONCILING')
              AND wr.updated_at <= ?
              AND (ar.status = 'CANCELLED' OR bs.status = 'CANCELLING')
            ORDER BY wr.deadline_at, wr.updated_at, wr.tenant_id, wr.worker_run_id
            FETCH FIRST ? ROWS ONLY
            """;

    private static final String FIND_ACTIVE_BY_BUILD_SESSION = """
            SELECT * FROM factory_worker_run
            WHERE tenant_id = ?
              AND build_session_id = ?
              AND status IN ('DISPATCHING', 'WAITING_EXTERNAL', 'CANCEL_REQUESTED', 'RECONCILING')
            ORDER BY updated_at DESC, attempt_no DESC, worker_run_id DESC
            FETCH FIRST 2 ROWS ONLY
            """;

    private static final String CAS = """
            UPDATE factory_worker_run SET
                status = ?, active_owner_key = ?, action_run_id = ?, attempt_token_hash = ?, external_session_ref = ?,
                dispatch_outbox_id = ?, started_at = ?, heartbeat_at = ?, cancel_requested_at = ?,
                completed_at = ?, result_artifact_manifest_ref = ?, result_hash = ?, code = ?,
                message = ?, retry_disposition = ?, version = ?, updated_at = ?
            WHERE tenant_id = ? AND worker_run_id = ? AND version = ?
            """;

    private static final String FIND_ACTION_RUN_OWNER = """
            SELECT tenant_id, action_id, input_json
            FROM action_run
            WHERE run_id = ?
            """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final JdbcJsonCodec json;

    public JdbcWorkerRunRepository(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcWorkerRunRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.json = new JdbcJsonCodec(this.objectMapper);
    }

    @Override
    public void create(WorkerRunRecord workerRun) {
        Objects.requireNonNull(workerRun, "workerRun");
        withConnection(dataSource, "create WorkerRun", connection -> {
            create(connection, workerRun);
            return null;
        });
    }

    void create(Connection connection, WorkerRunRecord workerRun) throws SQLException {
        validateCreate(workerRun);
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            bindInsert(statement, workerRun);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        return withConnection(dataSource, "find WorkerRun", connection ->
                find(connection, tenantId, workerRunId));
    }

    Optional<WorkerRunRecord> find(
            Connection connection, TenantId tenantId, WorkerRunId workerRunId) throws SQLException {
        return find(connection, tenantId, workerRunId, false);
    }

    Optional<WorkerRunRecord> find(
            Connection connection,
            TenantId tenantId,
            WorkerRunId workerRunId,
            boolean forUpdate) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        String sql = forUpdate ? FIND + " FOR UPDATE" : FIND;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, workerRunId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<WorkerRunRecord> findLatestByWorkOrder(TenantId tenantId, WorkOrderId workOrderId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workOrderId, "workOrderId");
        return withConnection(dataSource, "find latest WorkerRun by WorkOrder", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_LATEST_BY_WORK_ORDER)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, workOrderId.value());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<WorkerRunRecord> findActiveForReconciliation(Instant updatedBefore, int limit) {
        Objects.requireNonNull(updatedBefore, "updatedBefore");
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("reconciliation limit must be between 1 and 1000");
        }
        return withConnection(dataSource, "scan active WorkerRuns for reconciliation", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_ACTIVE_FOR_RECONCILIATION)) {
                setInstant(statement, 1, updatedBefore);
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<WorkerRunRecord> records = new ArrayList<>(Math.min(limit, 64));
                    while (resultSet.next()) {
                        records.add(map(resultSet));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    @Override
    public List<WorkerRunRecord> findWaitingExternalForStatusPoll(
            Instant updatedBefore, int limit) {
        return findBounded(
                updatedBefore,
                limit,
                FIND_WAITING_EXTERNAL_FOR_STATUS_POLL,
                "scan accepted WorkerRuns for status polling");
    }

    @Override
    public List<WorkerRunRecord> findCancellationRecoveryCandidates(
            Instant updatedBefore, int limit) {
        return findBounded(
                updatedBefore,
                limit,
                FIND_CANCELLATION_RECOVERY_CANDIDATES,
                "scan governed Worker cancellation recovery candidates");
    }

    private List<WorkerRunRecord> findBounded(
            Instant updatedBefore, int limit, String sql, String operation) {
        Objects.requireNonNull(updatedBefore, "updatedBefore");
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("reconciliation limit must be between 1 and 1000");
        }
        return withConnection(dataSource, operation, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                setInstant(statement, 1, updatedBefore);
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<WorkerRunRecord> records = new ArrayList<>(Math.min(limit, 64));
                    while (resultSet.next()) {
                        records.add(map(resultSet));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    @Override
    public Optional<WorkerRunRecord> findActiveByBuildSession(
            TenantId tenantId, BuildSessionId buildSessionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        return withConnection(dataSource, "find active WorkerRun by BuildSession", connection ->
                findActiveByBuildSession(connection, tenantId, buildSessionId, false));
    }

    Optional<WorkerRunRecord> findActiveByBuildSession(
            Connection connection,
            TenantId tenantId,
            BuildSessionId buildSessionId,
            boolean forUpdate) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        String sql = forUpdate ? FIND_ACTIVE_BY_BUILD_SESSION + " FOR UPDATE" : FIND_ACTIVE_BY_BUILD_SESSION;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, buildSessionId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Optional.empty();
                WorkerRunRecord active = map(resultSet);
                if (resultSet.next()) {
                    throw new IllegalStateException(
                            "multiple active WorkerRuns exist for one BuildSession cancellation owner");
                }
                return Optional.of(active);
            }
        }
    }

    @Override
    public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
        validateCas(expected, next, false);
        return withConnection(dataSource, "CAS WorkerRun", connection -> executeCas(connection, expected, next));
    }

    boolean compareAndSet(Connection connection, WorkerRunRecord expected, WorkerRunRecord next)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        validateCas(expected, next, false);
        return executeCas(connection, expected, next);
    }

    /** Dispatch-only claim path used by the WorkerRun/outbox transaction. */
    boolean compareAndSetDispatchClaim(
            Connection connection,
            WorkerRunRecord expected,
            WorkerRunRecord next)
            throws SQLException {
        validateCas(expected, next, true);
        if (expected.status() != WorkerRunStatus.REQUESTED
                || next.status() != WorkerRunStatus.DISPATCHING) {
            throw new IllegalArgumentException(
                    "dispatch claim must transition REQUESTED WorkerRun to DISPATCHING");
        }
        if (!hasMatchingActionRunOwner(connection, next)) {
            return false;
        }
        return executeCas(connection, expected, next);
    }

    private boolean executeCas(
            Connection connection,
            WorkerRunRecord expected,
            WorkerRunRecord next)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CAS)) {
            int index = 1;
            statement.setString(index++, next.status().name());
            setOptionalText(statement, index++, activeOwnerKey(next));
            setOptionalText(statement, index++, next.actionRunId());
            setOptionalText(statement, index++, next.attemptTokenHash().map(ContentHash::sha256));
            setOptionalText(statement, index++, next.externalSessionRef());
            setOptionalText(statement, index++, next.dispatchOutboxId().map(DispatchOutboxId::value));
            setOptionalInstant(statement, index++, next.startedAt());
            setOptionalInstant(statement, index++, next.heartbeatAt());
            setOptionalInstant(statement, index++, next.cancelRequestedAt());
            setOptionalInstant(statement, index++, next.completedAt());
            setOptionalText(statement, index++, next.resultArtifactManifestRef().map(ArtifactReference::value));
            setOptionalText(statement, index++, next.resultHash().map(ContentHash::sha256));
            setOptionalText(statement, index++, next.code());
            setOptionalText(statement, index++, next.message());
            setOptionalText(statement, index++, next.retryDisposition().map(Enum::name));
            statement.setLong(index++, next.version());
            setInstant(statement, index++, next.updatedAt());
            statement.setString(index++, expected.tenantId().value());
            statement.setString(index++, expected.workerRunId().value());
            statement.setLong(index, expected.version());
            return statement.executeUpdate() == 1;
        }
    }

    private static void validateCreate(WorkerRunRecord workerRun) {
        Objects.requireNonNull(workerRun, "workerRun");
        if (workerRun.status() != WorkerRunStatus.REQUESTED) {
            throw new IllegalArgumentException("WorkerRun must be created in REQUESTED status");
        }
    }

    private static void validateCas(
            WorkerRunRecord expected,
            WorkerRunRecord next,
            boolean dispatchClaimAllowed) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        requireCas(expected.version(), next.version());
        requireSame(expected.workerRunId().equals(next.workerRunId()), "workerRunId");
        requireSame(expected.tenantId().equals(next.tenantId()), "tenantId");
        requireSame(expected.buildSessionId().equals(next.buildSessionId()), "buildSessionId");
        requireSame(expected.workOrderId().equals(next.workOrderId()), "workOrderId");
        requireSame(expected.attemptNo() == next.attemptNo(), "attemptNo");
        requireSame(expected.workerBindingId().equals(next.workerBindingId()), "workerBindingId");
        requireSame(expected.workerAdapterVersion().equals(next.workerAdapterVersion()), "workerAdapterVersion");
        requireSame(expected.workerCapabilitySnapshot().equals(next.workerCapabilitySnapshot()), "workerCapabilitySnapshot");
        requireSame(expected.operationId().equals(next.operationId()), "operationId");
        requireSame(expected.deadlineAt().equals(next.deadlineAt()), "deadlineAt");
        requireSame(expected.createdAt().equals(next.createdAt()), "createdAt");
        if (expected.status().isTerminal()) {
            throw new IllegalArgumentException("terminal WorkerRun must not be overwritten");
        }
        if (!isLegalTransition(expected.status(), next.status())) {
            throw new IllegalArgumentException(
                    "illegal WorkerRun transition: " + expected.status() + " -> " + next.status());
        }
        if (expected.status() == WorkerRunStatus.REQUESTED
                && next.status() == WorkerRunStatus.DISPATCHING
                && !dispatchClaimAllowed) {
            throw new IllegalArgumentException(
                    "REQUESTED -> DISPATCHING is reserved for the dispatch/outbox transaction");
        }
        if (expected.status() != WorkerRunStatus.REQUESTED
                || next.status() != WorkerRunStatus.DISPATCHING) {
            requireSame(expected.actionRunId().equals(next.actionRunId()), "actionRunId");
            requireSame(expected.attemptTokenHash().equals(next.attemptTokenHash()), "attemptTokenHash");
            requireSame(expected.dispatchOutboxId().equals(next.dispatchOutboxId()), "dispatchOutboxId");
            requireSame(expected.startedAt().equals(next.startedAt()), "startedAt");
        }
        if (next.updatedAt().isBefore(expected.updatedAt())) {
            throw new IllegalArgumentException("updatedAt must not move backwards");
        }
    }

    private static boolean isLegalTransition(WorkerRunStatus expected, WorkerRunStatus next) {
        return switch (expected) {
            case REQUESTED -> next == WorkerRunStatus.DISPATCHING
                    || next == WorkerRunStatus.CANCEL_REQUESTED;
            case DISPATCHING -> next == WorkerRunStatus.WAITING_EXTERNAL
                    || next == WorkerRunStatus.CANCEL_REQUESTED
                    || next == WorkerRunStatus.RECONCILING;
            case WAITING_EXTERNAL -> next == WorkerRunStatus.SUCCEEDED
                    || next == WorkerRunStatus.FAILED
                    || next == WorkerRunStatus.CANCEL_REQUESTED
                    || next == WorkerRunStatus.MANUAL_REVIEW
                    || next == WorkerRunStatus.TIMED_OUT
                    || next == WorkerRunStatus.RECONCILING;
            // Cancellation and completion race on the owning ActionRun's first terminal CAS.
            // Once the durable cancel hook has moved the domain row to CANCEL_REQUESTED, the
            // canonical Action winner may still be a success, failure, timeout, manual-review,
            // or confirmed cancellation outcome.
            case CANCEL_REQUESTED -> next.isTerminal();
            case RECONCILING -> next == WorkerRunStatus.DISPATCHING
                    || next == WorkerRunStatus.WAITING_EXTERNAL
                    || next.isTerminal();
            case SUCCEEDED, FAILED, CANCELLED, MANUAL_REVIEW, TIMED_OUT -> false;
        };
    }

    private boolean hasMatchingActionRunOwner(Connection connection, WorkerRunRecord dispatching)
            throws SQLException {
        String actionRunId = dispatching.actionRunId().orElseThrow();
        try (PreparedStatement statement = connection.prepareStatement(FIND_ACTION_RUN_OWNER)) {
            statement.setString(1, actionRunId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !dispatching.tenantId().value().equals(resultSet.getString("tenant_id"))
                        || !WorkerDispatchAction.ACTION_ID.equals(resultSet.getString("action_id"))) {
                    return false;
                }
                JsonNode input;
                try {
                    input = objectMapper.readTree(resultSet.getString("input_json"));
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                    throw new SQLException("ActionRun input_json is not valid JSON", exception);
                }
                return dispatching.workerRunId().value().equals(input.path(WorkerDispatchAction.WORKER_RUN_ID).asText())
                        && dispatching.workOrderId().value().equals(
                                input.path(WorkerDispatchAction.WORK_ORDER_ID).asText());
            }
        }
    }

    private void bindInsert(PreparedStatement statement, WorkerRunRecord workerRun) throws SQLException {
        int index = 1;
        statement.setString(index++, workerRun.workerRunId().value());
        statement.setString(index++, workerRun.tenantId().value());
        statement.setString(index++, workerRun.buildSessionId().value());
        statement.setString(index++, workerRun.workOrderId().value());
        statement.setInt(index++, workerRun.attemptNo());
        statement.setString(index++, workerRun.workerBindingId());
        statement.setString(index++, workerRun.workerAdapterVersion());
        statement.setString(index++, json.writeCapabilities(workerRun.workerCapabilitySnapshot().values()));
        statement.setString(index++, workerRun.status().name());
        setOptionalText(statement, index++, activeOwnerKey(workerRun));
        setOptionalText(statement, index++, workerRun.actionRunId());
        statement.setString(index++, workerRun.operationId());
        setOptionalText(statement, index++, workerRun.attemptTokenHash().map(ContentHash::sha256));
        setOptionalText(statement, index++, workerRun.externalSessionRef());
        setOptionalText(statement, index++, workerRun.dispatchOutboxId().map(DispatchOutboxId::value));
        setOptionalInstant(statement, index++, workerRun.startedAt());
        setInstant(statement, index++, workerRun.deadlineAt());
        setOptionalInstant(statement, index++, workerRun.heartbeatAt());
        setOptionalInstant(statement, index++, workerRun.cancelRequestedAt());
        setOptionalInstant(statement, index++, workerRun.completedAt());
        setOptionalText(statement, index++, workerRun.resultArtifactManifestRef().map(ArtifactReference::value));
        setOptionalText(statement, index++, workerRun.resultHash().map(ContentHash::sha256));
        setOptionalText(statement, index++, workerRun.code());
        setOptionalText(statement, index++, workerRun.message());
        setOptionalText(statement, index++, workerRun.retryDisposition().map(Enum::name));
        statement.setLong(index++, workerRun.version());
        setInstant(statement, index++, workerRun.createdAt());
        setInstant(statement, index, workerRun.updatedAt());
    }

    private static Optional<String> activeOwnerKey(WorkerRunRecord workerRun) {
        return workerRun.status().isTerminal()
                ? Optional.empty()
                : Optional.of(workerRun.workOrderId().value());
    }

    private WorkerRunRecord map(ResultSet resultSet) throws SQLException {
        return new WorkerRunRecord(
                new WorkerRunId(resultSet.getString("worker_run_id")),
                new TenantId(resultSet.getString("tenant_id")),
                new BuildSessionId(resultSet.getString("build_session_id")),
                new WorkOrderId(resultSet.getString("work_order_id")),
                resultSet.getInt("attempt_no"),
                resultSet.getString("worker_binding_id"),
                resultSet.getString("worker_adapter_version"),
                json.readWorkerCapabilities(resultSet.getString("worker_capability_snapshot_json")),
                WorkerRunStatus.valueOf(resultSet.getString("status")),
                getOptionalText(resultSet, "action_run_id"),
                resultSet.getString("operation_id"),
                getOptionalText(resultSet, "attempt_token_hash").map(ContentHash::new),
                getOptionalText(resultSet, "external_session_ref"),
                getOptionalText(resultSet, "dispatch_outbox_id").map(DispatchOutboxId::new),
                getOptionalInstant(resultSet, "started_at"),
                getInstant(resultSet, "deadline_at"),
                getOptionalInstant(resultSet, "heartbeat_at"),
                getOptionalInstant(resultSet, "cancel_requested_at"),
                getOptionalInstant(resultSet, "completed_at"),
                getOptionalText(resultSet, "result_artifact_manifest_ref").map(ArtifactReference::new),
                getOptionalText(resultSet, "result_hash").map(ContentHash::new),
                getOptionalText(resultSet, "code"),
                getOptionalText(resultSet, "message"),
                getOptionalText(resultSet, "retry_disposition").map(WorkerRetryDisposition::valueOf),
                resultSet.getLong("version"),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "updated_at"));
    }
}
