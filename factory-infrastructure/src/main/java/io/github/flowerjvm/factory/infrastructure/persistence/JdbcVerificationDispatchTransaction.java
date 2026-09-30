package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.action.VerificationRunIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.verification.VerificationAttemptTokens;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntent;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchOperationIds;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchTransaction;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import javax.sql.DataSource;

/** Locks the exact PR4 domain boundary and creates the verifier intent in one JDBC transaction. */
public final class JdbcVerificationDispatchTransaction implements VerificationDispatchTransaction {
    private static final String LOCK_VERIFICATION = """
            SELECT build_session_id, candidate_id, candidate_hash, status, version
            FROM factory_verification_run
            WHERE tenant_id = ? AND verification_run_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_SESSION = """
            SELECT status, current_phase, current_candidate_id, current_candidate_hash,
                   cancellation_requested_at, deadline_at
            FROM factory_build_session
            WHERE tenant_id = ? AND build_session_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_ACTION_RUN = """
            SELECT tenant_id, action_id, status, attempt_token, input_json,
                   context_metadata_json, duplicate_key
            FROM action_run WHERE run_id = ? FOR UPDATE
            """;

    private final DataSource dataSource;
    private final JdbcVerificationDispatchIntentRepository intents;
    private final JdbcVerificationRunRepository verificationRuns;
    private final JdbcCandidateVersionRepository candidates;
    private final ObjectMapper objectMapper;

    public JdbcVerificationDispatchTransaction(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcVerificationDispatchTransaction(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.intents = new JdbcVerificationDispatchIntentRepository(dataSource);
        this.verificationRuns = new JdbcVerificationRunRepository(dataSource);
        this.candidates = new JdbcCandidateVersionRepository(dataSource);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    @Override
    public VerificationDispatchIntent prepare(
            TenantId tenantId,
            VerificationRunInput input,
            String actionRunId,
            String attemptTokenHash,
            Instant preparedAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(input, "input");
        requireText(actionRunId, "actionRunId");
        requireHash(attemptTokenHash);
        Objects.requireNonNull(preparedAt, "preparedAt");
        var domain = verificationRuns.find(tenantId, input.verificationRunId())
                .orElseThrow(() -> new IllegalStateException("VerificationRun is not visible in tenant scope"));
        CandidateVersion candidate = candidates.find(tenantId, input.candidateId())
                .orElseThrow(() -> new IllegalStateException("CandidateVersion is not visible in tenant scope"));
        if (!domain.tenantId().equals(tenantId)
                || !domain.candidateId().equals(input.candidateId())
                || domain.version() != input.expectedVerificationRunVersion()
                || !candidate.tenantId().equals(tenantId)
                || !candidate.candidateId().equals(input.candidateId())
                || !candidate.buildSessionId().equals(domain.buildSessionId())
                || !candidate.sourceHash().equals(domain.candidateHash())
                || !candidate.toolchainLockHash().equals(domain.toolchainLockHash())) {
            throw new IllegalStateException("verification input is not bound to immutable candidate locks");
        }
        String expectedDuplicateKey = VerificationRunIdempotencyKeys.derive(
                domain, candidate, input.expectedVerificationRunVersion());
        return withConnection(dataSource, "prepare verification dispatch", connection ->
                inTransaction(
                        connection, tenantId, input, actionRunId,
                        attemptTokenHash, expectedDuplicateKey, preparedAt));
    }

    @Override
    public Optional<VerificationDispatchIntent> findExact(
            TenantId tenantId,
            VerificationRunInput input,
            String actionRunId,
            String attemptTokenHash) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(input, "input");
        return intents.find(VerificationDispatchOperationIds.derive(input))
                .filter(intent -> exact(intent, tenantId, input, actionRunId, attemptTokenHash));
    }

    private VerificationDispatchIntent inTransaction(
            Connection connection,
            TenantId tenantId,
            VerificationRunInput input,
            String actionRunId,
            String attemptTokenHash,
            String expectedDuplicateKey,
            Instant preparedAt) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            LockedVerification verification = lockVerification(connection, tenantId, input);
            String operationId = VerificationDispatchOperationIds.derive(input);
            Optional<VerificationDispatchIntent> existing = intents.find(connection, operationId);
            if (existing.isPresent()) {
                if (!exact(existing.orElseThrow(), tenantId, input, actionRunId, attemptTokenHash)) {
                    throw new IllegalStateException("deterministic verification operation has a different owner");
                }
                connection.commit();
                return existing.orElseThrow();
            }
            Instant deadlineAt = lockAndValidateSession(connection, tenantId, input, verification, preparedAt);
            lockAndValidateActionRun(
                    connection, tenantId, input, actionRunId, attemptTokenHash,
                    expectedDuplicateKey);
            VerificationDispatchIntent intent = VerificationDispatchIntent.pending(
                    operationId,
                    tenantId,
                    input.verificationRunId(),
                    input.candidateId(),
                    input.expectedVerificationRunVersion(),
                    actionRunId,
                    attemptTokenHash,
                    deadlineAt,
                    preparedAt);
            intents.create(connection, intent);
            VerificationDispatchIntent canonical = intents.find(connection, operationId).orElseThrow();
            connection.commit();
            return canonical;
        } catch (SQLException | RuntimeException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static LockedVerification lockVerification(
            Connection connection, TenantId tenantId, VerificationRunInput input) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_VERIFICATION)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, input.verificationRunId().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("VerificationRun is not visible in tenant scope");
                }
                return new LockedVerification(
                        resultSet.getString("build_session_id"),
                        resultSet.getString("candidate_id"),
                        resultSet.getString("candidate_hash"),
                        resultSet.getString("status"),
                        resultSet.getLong("version"));
            }
        }
    }

    private static Instant lockAndValidateSession(
            Connection connection,
            TenantId tenantId,
            VerificationRunInput input,
            LockedVerification verification,
            Instant preparedAt) throws SQLException {
        if (!verification.candidateId.equals(input.candidateId().value())
                || !"REQUESTED".equals(verification.status)
                || verification.version != input.expectedVerificationRunVersion()) {
            throw new IllegalStateException("VerificationRun is not the exact REQUESTED version");
        }
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SESSION)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, verification.buildSessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("BuildSession is not visible in tenant scope");
                }
                Instant deadlineAt = getInstant(resultSet, "deadline_at");
                if (!"VERIFYING".equals(resultSet.getString("status"))
                        || !"test".equals(resultSet.getString("current_phase"))
                        || !input.candidateId().value().equals(resultSet.getString("current_candidate_id"))
                        || !verification.candidateHash.equals(resultSet.getString("current_candidate_hash"))
                        || resultSet.getTimestamp("cancellation_requested_at") != null
                        || !preparedAt.isBefore(deadlineAt)) {
                    throw new IllegalStateException("BuildSession is not launchable for verification");
                }
                return deadlineAt;
            }
        }
    }

    private void lockAndValidateActionRun(
            Connection connection,
            TenantId tenantId,
            VerificationRunInput expectedInput,
            String actionRunId,
            String tokenHash,
            String expectedDuplicateKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACTION_RUN)) {
            statement.setString(1, actionRunId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("ActionRun is not visible for verification dispatch");
                }
                Map<String, Object> storedInput = readMap(resultSet.getString("input_json"));
                Map<String, Object> metadata = readMap(resultSet.getString("context_metadata_json"));
                if (!tenantId.value().equals(resultSet.getString("tenant_id"))
                        || !VerificationRunAction.ACTION_ID.equals(resultSet.getString("action_id"))
                        || !"RUNNING".equals(resultSet.getString("status"))
                        || !tokenHash.equals(VerificationAttemptTokens.hash(resultSet.getString("attempt_token")))
                        || !expectedInput.equals(VerificationRunInput.from(storedInput))
                        || !VerificationRunAction.RESOURCE_TYPE.equals(metadata.get("resource.type"))
                        || !expectedInput.candidateId().value().equals(metadata.get("resource.id"))
                        || !expectedDuplicateKey.equals(resultSet.getString("duplicate_key"))) {
                    throw new IllegalStateException("ActionRun is not the exact RUNNING verification owner");
                }
            }
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            throw new FactoryPersistenceException("ActionRun JSON deserialization failed", exception);
        }
    }

    private static boolean exact(
            VerificationDispatchIntent intent,
            TenantId tenantId,
            VerificationRunInput input,
            String actionRunId,
            String attemptTokenHash) {
        return intent.tenantId().equals(tenantId)
                && intent.verificationRunId().equals(input.verificationRunId())
                && intent.candidateId().equals(input.candidateId())
                && intent.expectedVerificationRunVersion() == input.expectedVerificationRunVersion()
                && intent.actionRunId().equals(actionRunId)
                && intent.attemptTokenHash().equals(attemptTokenHash);
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("attemptTokenHash must be SHA-256 hex");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record LockedVerification(
            String buildSessionId, String candidateId, String candidateHash, String status, long version) {}
}
