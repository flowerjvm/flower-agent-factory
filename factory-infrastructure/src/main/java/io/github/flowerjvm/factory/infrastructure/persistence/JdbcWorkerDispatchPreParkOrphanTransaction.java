package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.application.work.WorkerDispatchPreParkOrphanTransaction;
import io.github.flowerjvm.factory.application.work.WorkerDispatchPublisher;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/** Fail-closed JDBC classification for the Action Runtime 0.3.3 pre-park crash window. */
public final class JdbcWorkerDispatchPreParkOrphanTransaction
        implements WorkerDispatchPreParkOrphanTransaction {
    private static final int MAX_SCAN_ATTEMPTS = 32;
    private static final String FIND_STALE_PRE_PARK = """
            SELECT o.tenant_id, o.outbox_id
            FROM factory_dispatch_outbox o
            JOIN factory_worker_run w
              ON w.tenant_id = o.tenant_id
             AND w.worker_run_id = o.aggregate_id
             AND w.operation_id = o.operation_id
            JOIN action_run a ON a.run_id = w.action_run_id
            WHERE o.operation_type = 'WORKER_DISPATCH'
              AND o.aggregate_type = 'WORKER_RUN'
              AND o.status = 'PENDING'
              AND o.attempt_count = 0
              AND o.claim_token IS NULL
              AND o.claim_purpose IS NULL
              AND o.claimed_at IS NULL
              AND o.lease_until IS NULL
              AND w.status = 'DISPATCHING'
              AND a.status = 'RUNNING'
              AND a.current_stage = 'execute-action'
              AND o.updated_at <= ?
              AND w.updated_at <= ?
              AND a.updated_at <= ?
            ORDER BY o.updated_at, o.created_at, o.outbox_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String LOCK_WORK_ORDER = """
            SELECT tenant_id, work_order_id, build_session_id, phase, candidate_id,
                   input_artifact_manifest_ref, required_capabilities_json, max_attempts, deadline_at
            FROM factory_work_order
            WHERE tenant_id = ? AND work_order_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_CANDIDATE = """
            SELECT tenant_id, candidate_id, build_session_id, source_hash
            FROM factory_candidate_version
            WHERE tenant_id = ? AND candidate_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_ACTION = """
            SELECT tenant_id, action_id, input_json, status, current_stage, attempt_token,
                   external_operation_id, external_operation_metadata_json, due_at,
                   result_status, failure_reason, updated_at
            FROM action_run
            WHERE run_id = ?
            FOR UPDATE
            """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final JdbcJsonCodec json;
    private final JdbcBuildSessionRepository buildSessions;
    private final JdbcWorkerRunRepository workerRuns;
    private final JdbcDispatchOutboxRepository outboxes;

    public JdbcWorkerDispatchPreParkOrphanTransaction(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcWorkerDispatchPreParkOrphanTransaction(
            DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.json = new JdbcJsonCodec(this.objectMapper);
        this.buildSessions = new JdbcBuildSessionRepository(dataSource);
        this.workerRuns = new JdbcWorkerRunRepository(dataSource, this.objectMapper);
        this.outboxes = new JdbcDispatchOutboxRepository(dataSource);
    }

    @Override
    public boolean reconcileNext(Instant observedAt, Duration grace) {
        Instant now = Objects.requireNonNull(observedAt, "observedAt")
                .truncatedTo(ChronoUnit.MICROS);
        Objects.requireNonNull(grace, "grace");
        if (grace.isNegative() || grace.isZero() || grace.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("grace must be positive and at most one hour");
        }
        Instant cutoff = now.minus(grace);
        return withConnection(dataSource, "reconcile Worker dispatch pre-park orphan", connection -> {
            for (int attempt = 0; attempt < MAX_SCAN_ATTEMPTS; attempt++) {
                Optional<OutboxKey> key = findCandidate(connection, cutoff);
                if (key.isEmpty()) return false;
                ReconcileResult result = reconcileCandidate(connection, key.orElseThrow(), now, cutoff);
                if (result == ReconcileResult.RECONCILED) return true;
                if (result == ReconcileResult.NOT_STALE) return false;
            }
            return false;
        });
    }

    private ReconcileResult reconcileCandidate(
            Connection connection, OutboxKey key, Instant now, Instant cutoff) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            DispatchOutbox initial = outboxes.find(connection, key.tenantId(), key.outboxId())
                    .orElse(null);
            WorkerRunRecord initialRun = initial == null ? null : loadAggregate(connection, initial);
            BuildSession session = initialRun == null
                    ? null
                    : buildSessions.find(
                                    connection,
                                    initialRun.tenantId(),
                                    initialRun.buildSessionId(),
                                    true)
                            .orElse(null);
            WorkerRunRecord run = initialRun == null
                    ? null
                    : workerRuns.find(
                                    connection,
                                    initialRun.tenantId(),
                                    initialRun.workerRunId(),
                                    true)
                            .orElse(null);
            LockedWorkOrder order = run == null
                    ? null
                    : lockWorkOrder(connection, run.tenantId(), run.workOrderId().value());
            ActionOwner action = run == null ? null : lockActionOwner(connection, run);
            DispatchOutbox observed = outboxes.find(connection, key.tenantId(), key.outboxId(), true)
                    .orElse(null);

            if (!sameFreshOutbox(initial, observed, key)) {
                connection.rollback();
                return ReconcileResult.RETRY_SCAN;
            }
            if (!exactBinding(connection, observed, run, order, session, action, now)) {
                connection.rollback();
                return ReconcileResult.RETRY_SCAN;
            }
            if (observed.updatedAt().isAfter(cutoff)
                    || run.updatedAt().isAfter(cutoff)
                    || action.updatedAt().isAfter(cutoff)) {
                connection.rollback();
                return ReconcileResult.NOT_STALE;
            }

            boolean cancelling = session.status() == BuildSessionStatus.CANCELLING;
            String code = cancelling
                    ? WorkerDispatchPublisher.ACTION_PARK_CANCEL_ORPHANED
                    : WorkerDispatchPublisher.ACTION_PARK_ORPHANED;
            DispatchOutbox reviewed = observed.manualReviewBeforeSubmit(code, now);
            String message = "Action Runtime stopped before the deferred Worker dispatch was durably parked";
            BuildSession reviewedSession = cancelling
                    ? session.manualReviewCancellation(code, message, now)
                    : session.manualReview(code, message, now);
            if (!outboxes.compareAndSet(connection, observed, reviewed)
                    || !buildSessions.compareAndSet(connection, session, reviewedSession)) {
                connection.rollback();
                return ReconcileResult.RETRY_SCAN;
            }
            connection.commit();
            return ReconcileResult.RECONCILED;
        } catch (SQLException | RuntimeException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private boolean exactBinding(
            Connection connection,
            DispatchOutbox outbox,
            WorkerRunRecord run,
            LockedWorkOrder order,
            BuildSession session,
            ActionOwner action,
            Instant now) throws SQLException {
        return run != null
                && order != null
                && session != null
                && action != null
                && outbox.tenantId().equals(run.tenantId())
                && outbox.outboxId().equals(run.dispatchOutboxId().orElse(null))
                && outbox.aggregateId().equals(run.workerRunId().value())
                && outbox.operationId().equals(run.operationId())
                && outbox.payloadArtifactRef().value().equals(order.inputManifestRef())
                && WorkerDispatchOperationIds.derive(run).equals(run.operationId())
                && run.status() == WorkerRunStatus.DISPATCHING
                && run.attemptNo() <= order.maxAttempts()
                && run.workerCapabilitySnapshot().supportsAll(order.requiredCapabilities())
                && order.tenantId().equals(run.tenantId())
                && order.workOrderId().equals(run.workOrderId().value())
                && order.buildSessionId().equals(run.buildSessionId().value())
                && order.phase().equals(session.currentPhase().id())
                && session.tenantId().equals(run.tenantId())
                && session.buildSessionId().equals(run.buildSessionId())
                && exactSessionAuthority(session)
                && session.selectedCodingWorkerBinding().filter(run.workerBindingId()::equals).isPresent()
                && exactCandidateBinding(connection, run, order, session)
                && action.exactOwner();
    }

    private static boolean exactSessionAuthority(BuildSession session) {
        if (session.status() == BuildSessionStatus.CANCELLING) {
            return session.cancellationRequestedAt().isPresent();
        }
        return (session.status() == BuildSessionStatus.RUNNING
                        || session.status() == BuildSessionStatus.REPAIRING)
                && session.cancellationRequestedAt().isEmpty();
    }

    private boolean exactCandidateBinding(
            Connection connection,
            WorkerRunRecord run,
            LockedWorkOrder order,
            BuildSession session) throws SQLException {
        if (order.candidateId().isEmpty()) {
            return session.status() != BuildSessionStatus.REPAIRING;
        }
        if (session.currentCandidateId().isEmpty()
                || session.currentCandidateHash().isEmpty()
                || !order.candidateId().orElseThrow().equals(
                        session.currentCandidateId().orElseThrow().value())) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(LOCK_CANDIDATE)) {
            statement.setString(1, run.tenantId().value());
            statement.setString(2, order.candidateId().orElseThrow());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && run.tenantId().value().equals(resultSet.getString("tenant_id"))
                        && order.candidateId().orElseThrow().equals(resultSet.getString("candidate_id"))
                        && run.buildSessionId().value().equals(resultSet.getString("build_session_id"))
                        && session.currentCandidateHash().orElseThrow().sha256()
                                .equals(resultSet.getString("source_hash"));
            }
        }
    }

    private ActionOwner lockActionOwner(Connection connection, WorkerRunRecord run) throws SQLException {
        if (run.actionRunId().isEmpty() || run.attemptTokenHash().isEmpty()) return null;
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACTION)) {
            statement.setString(1, run.actionRunId().orElseThrow());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return null;
                JsonNode input;
                JsonNode metadata;
                try {
                    input = objectMapper.readTree(resultSet.getString("input_json"));
                    metadata = objectMapper.readTree(
                            resultSet.getString("external_operation_metadata_json"));
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                    throw new SQLException("ActionRun JSON is invalid", exception);
                }
                String token = resultSet.getString("attempt_token");
                boolean exact = run.tenantId().value().equals(resultSet.getString("tenant_id"))
                        && WorkerDispatchAction.ACTION_ID.equals(resultSet.getString("action_id"))
                        && "RUNNING".equals(resultSet.getString("status"))
                        && "execute-action".equals(resultSet.getString("current_stage"))
                        && token != null
                        && !token.isBlank()
                        && run.attemptTokenHash().orElseThrow().equals(
                                WorkerDispatchOperationIds.hashAttemptToken(token))
                        && "".equals(resultSet.getString("external_operation_id"))
                        && metadata != null
                        && metadata.isObject()
                        && metadata.size() == 0
                        && resultSet.getObject("due_at") == null
                        && resultSet.getString("result_status") == null
                        && "".equals(resultSet.getString("failure_reason"))
                        && run.workerRunId().value().equals(
                                input.path(WorkerDispatchAction.WORKER_RUN_ID).asText())
                        && run.workOrderId().value().equals(
                                input.path(WorkerDispatchAction.WORK_ORDER_ID).asText())
                        && input.path(WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION).isIntegralNumber()
                        && input.path(WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION).asLong()
                                == run.version() - 1;
                return new ActionOwner(exact, Instant.ofEpochMilli(resultSet.getLong("updated_at")));
            }
        }
    }

    private LockedWorkOrder lockWorkOrder(
            Connection connection, TenantId tenantId, String workOrderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_WORK_ORDER)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, workOrderId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return null;
                return new LockedWorkOrder(
                        new TenantId(resultSet.getString("tenant_id")),
                        resultSet.getString("work_order_id"),
                        resultSet.getString("build_session_id"),
                        resultSet.getString("phase"),
                        getOptionalText(resultSet, "candidate_id"),
                        resultSet.getString("input_artifact_manifest_ref"),
                        json.readCapabilities(resultSet.getString("required_capabilities_json")),
                        resultSet.getInt("max_attempts"),
                        getInstant(resultSet, "deadline_at"));
            }
        }
    }

    private WorkerRunRecord loadAggregate(Connection connection, DispatchOutbox outbox)
            throws SQLException {
        if (!WorkerOutboxOperations.DISPATCH.equals(outbox.operationType())
                || !"WORKER_RUN".equals(outbox.aggregateType())) {
            return null;
        }
        try {
            return workerRuns.find(
                            connection,
                            outbox.tenantId(),
                            new WorkerRunId(outbox.aggregateId()))
                    .orElse(null);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Optional<OutboxKey> findCandidate(Connection connection, Instant cutoff)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_STALE_PRE_PARK)) {
            setInstant(statement, 1, cutoff);
            setInstant(statement, 2, cutoff);
            statement.setLong(3, cutoff.toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(new OutboxKey(
                                new TenantId(resultSet.getString("tenant_id")),
                                new DispatchOutboxId(resultSet.getString("outbox_id"))))
                        : Optional.empty();
            }
        }
    }

    private static boolean sameFreshOutbox(
            DispatchOutbox initial, DispatchOutbox observed, OutboxKey key) {
        return initial != null
                && observed != null
                && initial.equals(observed)
                && key.tenantId().equals(observed.tenantId())
                && key.outboxId().equals(observed.outboxId())
                && WorkerOutboxOperations.DISPATCH.equals(observed.operationType())
                && observed.status() == DispatchOutboxStatus.PENDING
                && observed.attemptCount() == 0
                && observed.version() == 0
                && observed.claimToken().isEmpty()
                && observed.claimPurpose().isEmpty()
                && observed.claimedAt().isEmpty()
                && observed.leaseUntil().isEmpty();
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private enum ReconcileResult {
        RECONCILED,
        NOT_STALE,
        RETRY_SCAN
    }

    private record OutboxKey(TenantId tenantId, DispatchOutboxId outboxId) {}

    private record ActionOwner(boolean exactOwner, Instant updatedAt) {}

    private record LockedWorkOrder(
            TenantId tenantId,
            String workOrderId,
            String buildSessionId,
            String phase,
            Optional<String> candidateId,
            String inputManifestRef,
            Set<WorkerCapability> requiredCapabilities,
            int maxAttempts,
            Instant deadlineAt) {}
}
