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
import io.github.flowerjvm.factory.application.work.WorkerDispatchPublisher;
import io.github.flowerjvm.factory.application.work.WorkerDispatchStartTransaction;
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

/** JDBC serialization point immediately before a Coding Worker external submit may begin. */
public final class JdbcWorkerDispatchStartTransaction implements WorkerDispatchStartTransaction {
    private static final int MAX_CLAIM_ATTEMPTS = 32;
    private static final String FIND_DUE = """
            SELECT tenant_id, outbox_id
            FROM factory_dispatch_outbox
            WHERE operation_type = 'WORKER_DISPATCH'
              AND status IN ('PENDING', 'RETRY_WAIT')
              AND available_at <= ?
            ORDER BY available_at, created_at, outbox_id
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
                   result_status, failure_reason
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

    public JdbcWorkerDispatchStartTransaction(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcWorkerDispatchStartTransaction(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.json = new JdbcJsonCodec(this.objectMapper);
        this.buildSessions = new JdbcBuildSessionRepository(dataSource);
        this.workerRuns = new JdbcWorkerRunRepository(dataSource, this.objectMapper);
        this.outboxes = new JdbcDispatchOutboxRepository(dataSource);
    }

    @Override
    public Optional<DispatchOutbox> claimNext(
            Instant now, Duration lease, String claimToken) {
        Instant canonicalNow = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
        Objects.requireNonNull(lease, "lease");
        String exactClaimToken = requireText(claimToken, "claimToken");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return withConnection(dataSource, "claim Worker dispatch start boundary", connection -> {
            for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
                Optional<OutboxKey> key = findDue(connection, canonicalNow);
                if (key.isEmpty()) return Optional.empty();
                ClaimCandidateResult result = claimCandidate(
                        connection, key.orElseThrow(), canonicalNow, lease, exactClaimToken);
                if (result.stopScanning()) return result.outbox();
            }
            return Optional.empty();
        });
    }

    private ClaimCandidateResult claimCandidate(
            Connection connection,
            OutboxKey key,
            Instant now,
            Duration lease,
            String claimToken) throws SQLException {
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

            if (!isSameFreshCandidate(initial, observed, key, now)) {
                connection.rollback();
                return ClaimCandidateResult.retryScan();
            }
            if (action != null && action.exactRunningPrePark()) {
                // Action Runtime 0.3.3 has not yet durably parked the deferred attempt. Neither a
                // normal submit nor cancellation may reinterpret this row. The separate bounded
                // orphan transaction owns the 30-second fail-closed classification.
                connection.rollback();
                return ClaimCandidateResult.stopWithoutClaim();
            }

            String rejection = rejectionCode(connection, observed, run, order, session, action, now);
            DispatchOutbox claimed = observed.claimForSubmission(claimToken, now, lease);
            if (!outboxes.compareAndSet(connection, observed, claimed)) {
                connection.rollback();
                return ClaimCandidateResult.retryScan();
            }
            if (rejection == null) {
                connection.commit();
                return ClaimCandidateResult.claimed(claimed);
            }
            DispatchOutbox terminal = claimed.manualReview(claimToken, rejection, now);
            if (!outboxes.compareAndSet(connection, claimed, terminal)) {
                connection.rollback();
                return ClaimCandidateResult.retryScan();
            }
            connection.commit();
            return ClaimCandidateResult.claimed(terminal);
        } catch (SQLException | RuntimeException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private WorkerRunRecord loadAggregate(Connection connection, DispatchOutbox outbox)
            throws SQLException {
        if (!WorkerOutboxOperations.DISPATCH.equals(outbox.operationType())
                || !"WORKER_RUN".equals(outbox.aggregateType())) {
            return null;
        }
        WorkerRunId workerRunId;
        try {
            workerRunId = new WorkerRunId(outbox.aggregateId());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return workerRuns.find(connection, outbox.tenantId(), workerRunId).orElse(null);
    }

    private String rejectionCode(
            Connection connection,
            DispatchOutbox outbox,
            WorkerRunRecord run,
            LockedWorkOrder order,
            BuildSession session,
            ActionOwner action,
            Instant now) throws SQLException {
        if (session != null
                && (session.status() == BuildSessionStatus.CANCELLING
                        || session.cancellationRequestedAt().isPresent())) {
            return WorkerDispatchPublisher.CANCELLED_BEFORE_SUBMIT;
        }
        if (run != null && run.status() == WorkerRunStatus.CANCEL_REQUESTED) {
            return WorkerDispatchPublisher.CANCELLED_BEFORE_SUBMIT;
        }
        if (action != null && "CANCELLED".equals(action.status())) {
            return WorkerDispatchPublisher.CANCELLED_BEFORE_SUBMIT;
        }
        if ((session != null && !now.isBefore(session.deadlineAt()))
                || (order != null && !now.isBefore(order.deadlineAt()))
                || (run != null && !now.isBefore(run.deadlineAt()))) {
            return WorkerDispatchPublisher.DISPATCH_DEADLINE_EXCEEDED;
        }
        if (!exactBinding(outbox, run, order, session, action)
                || !exactCandidateBinding(connection, run, order, session)) {
            return WorkerDispatchPublisher.START_BOUNDARY_INVALID;
        }
        return null;
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

    private static boolean exactBinding(
            DispatchOutbox outbox,
            WorkerRunRecord run,
            LockedWorkOrder order,
            BuildSession session,
            ActionOwner action) {
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
                && run.attemptNo() <= order.maxAttempts()
                && run.workerCapabilitySnapshot().supportsAll(order.requiredCapabilities())
                && run.status() == WorkerRunStatus.DISPATCHING
                && order.tenantId().equals(run.tenantId())
                && order.workOrderId().equals(run.workOrderId().value())
                && order.buildSessionId().equals(run.buildSessionId().value())
                && order.phase().equals(session.currentPhase().id())
                && session.tenantId().equals(run.tenantId())
                && session.buildSessionId().equals(run.buildSessionId())
                && (session.status() == BuildSessionStatus.RUNNING
                        || session.status() == BuildSessionStatus.REPAIRING)
                && session.cancellationRequestedAt().isEmpty()
                && session.selectedCodingWorkerBinding().filter(run.workerBindingId()::equals).isPresent()
                && action.exactOwner();
    }

    private ActionOwner lockActionOwner(Connection connection, WorkerRunRecord run) throws SQLException {
        if (run.actionRunId().isEmpty() || run.attemptTokenHash().isEmpty()) return null;
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACTION)) {
            statement.setString(1, run.actionRunId().orElseThrow());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return null;
                JsonNode input;
                try {
                    input = objectMapper.readTree(resultSet.getString("input_json"));
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                    throw new SQLException("ActionRun input_json is not valid JSON", exception);
                }
                JsonNode metadata;
                try {
                    metadata = objectMapper.readTree(
                            resultSet.getString("external_operation_metadata_json"));
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                    throw new SQLException("ActionRun external metadata is not valid JSON", exception);
                }
                String status = resultSet.getString("status");
                String attemptToken = resultSet.getString("attempt_token");
                boolean exactBinding = run.tenantId().value().equals(resultSet.getString("tenant_id"))
                        && WorkerDispatchAction.ACTION_ID.equals(resultSet.getString("action_id"))
                        && attemptToken != null
                        && !attemptToken.isBlank()
                        && run.attemptTokenHash().orElseThrow().equals(
                                WorkerDispatchOperationIds.hashAttemptToken(
                                        attemptToken))
                        && run.workerRunId().value().equals(
                                input.path(WorkerDispatchAction.WORKER_RUN_ID).asText())
                        && run.workOrderId().value().equals(
                                input.path(WorkerDispatchAction.WORK_ORDER_ID).asText())
                        && input.path(WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION).isIntegralNumber()
                        && input.path(WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION).asLong()
                                == run.version() - 1;
                boolean waiting = exactBinding
                        && "WAITING_EXTERNAL".equals(status)
                        && run.operationId().equals(resultSet.getString("external_operation_id"));
                boolean runningPrePark = exactBinding
                        && "RUNNING".equals(status)
                        && "execute-action".equals(resultSet.getString("current_stage"))
                        && "".equals(resultSet.getString("external_operation_id"))
                        && metadata != null
                        && metadata.isObject()
                        && metadata.size() == 0
                        && resultSet.getObject("due_at") == null
                        && resultSet.getString("result_status") == null
                        && "".equals(resultSet.getString("failure_reason"));
                return new ActionOwner(status, waiting, runningPrePark);
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

    private static Optional<OutboxKey> findDue(Connection connection, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_DUE)) {
            setInstant(statement, 1, now);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(new OutboxKey(
                                new TenantId(resultSet.getString("tenant_id")),
                                new DispatchOutboxId(resultSet.getString("outbox_id"))))
                        : Optional.empty();
            }
        }
    }

    private static boolean isSameFreshCandidate(
            DispatchOutbox initial,
            DispatchOutbox observed,
            OutboxKey key,
            Instant now) {
        return initial != null
                && observed != null
                && initial.equals(observed)
                && key.tenantId().equals(observed.tenantId())
                && key.outboxId().equals(observed.outboxId())
                && WorkerOutboxOperations.DISPATCH.equals(observed.operationType())
                && (observed.status() == DispatchOutboxStatus.PENDING
                        || observed.status() == DispatchOutboxStatus.RETRY_WAIT)
                && !now.isBefore(observed.availableAt());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record OutboxKey(TenantId tenantId, DispatchOutboxId outboxId) {}

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

    private record ActionOwner(
            String status, boolean exactOwner, boolean exactRunningPrePark) {}

    private record ClaimCandidateResult(
            Optional<DispatchOutbox> outbox, boolean stopScanning) {
        private static ClaimCandidateResult retryScan() {
            return new ClaimCandidateResult(Optional.empty(), false);
        }

        private static ClaimCandidateResult stopWithoutClaim() {
            return new ClaimCandidateResult(Optional.empty(), true);
        }

        private static ClaimCandidateResult claimed(DispatchOutbox outbox) {
            return new ClaimCandidateResult(Optional.of(outbox), true);
        }
    }
}
