package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntent;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentStatus;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC CAS queue for PR4 independent verification; it is deliberately not the generic Worker outbox. */
public final class JdbcVerificationDispatchIntentRepository implements VerificationDispatchIntentRepository {
    private static final String INSERT = """
            INSERT INTO factory_verification_dispatch_intent (
                operation_id, tenant_id, verification_run_id, candidate_id,
                expected_verification_run_version, action_run_id, attempt_token_hash, deadline_at,
                status, claim_token, lease_until, attempt_count, last_code, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND = """
            SELECT * FROM factory_verification_dispatch_intent WHERE operation_id = ?
            """;
    private static final String FIND_LATEST = """
            SELECT * FROM factory_verification_dispatch_intent
            WHERE tenant_id = ? AND verification_run_id = ?
            ORDER BY expected_verification_run_version DESC, created_at DESC
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String FIND_CLAIMABLE = """
            SELECT * FROM factory_verification_dispatch_intent
            WHERE status = 'PENDING' OR (status = 'UNCERTAIN' AND lease_until <= ?)
            ORDER BY created_at, operation_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String FIND_EXPIRED_RUNNING = """
            SELECT * FROM factory_verification_dispatch_intent
            WHERE status = 'RUNNING' AND lease_until <= ?
            ORDER BY lease_until, operation_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String LOCK_START_BOUNDARY = """
            SELECT bs.status, bs.current_phase, bs.current_candidate_id, bs.current_candidate_hash,
                   bs.cancellation_requested_at, bs.deadline_at, vr.candidate_hash
            FROM factory_verification_run vr
            JOIN factory_build_session bs
              ON bs.tenant_id = vr.tenant_id AND bs.build_session_id = vr.build_session_id
            WHERE vr.tenant_id = ? AND vr.verification_run_id = ?
            FOR UPDATE
            """;
    private static final String NO_LONGER_LAUNCHABLE = "VERIFICATION_DISPATCH_NO_LONGER_LAUNCHABLE";
    private static final String CAS = """
            UPDATE factory_verification_dispatch_intent SET
                status = ?, claim_token = ?, lease_until = ?, attempt_count = ?, last_code = ?,
                version = ?, updated_at = ?
            WHERE operation_id = ? AND version = ?
            """;

    private final DataSource dataSource;

    public JdbcVerificationDispatchIntentRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void create(VerificationDispatchIntent intent) {
        withConnection(dataSource, "create verification dispatch intent", connection -> {
            create(connection, intent);
            return null;
        });
    }

    void create(Connection connection, VerificationDispatchIntent intent) throws SQLException {
        Objects.requireNonNull(intent, "intent");
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            int index = 1;
            statement.setString(index++, intent.operationId());
            statement.setString(index++, intent.tenantId().value());
            statement.setString(index++, intent.verificationRunId().value());
            statement.setString(index++, intent.candidateId().value());
            statement.setLong(index++, intent.expectedVerificationRunVersion());
            statement.setString(index++, intent.actionRunId());
            statement.setString(index++, intent.attemptTokenHash());
            setInstant(statement, index++, intent.deadlineAt());
            statement.setString(index++, intent.status().name());
            setOptionalText(statement, index++, intent.claimToken());
            setOptionalInstant(statement, index++, intent.leaseUntil());
            statement.setInt(index++, intent.attemptCount());
            setOptionalText(statement, index++, intent.lastCode());
            statement.setLong(index++, intent.version());
            setInstant(statement, index++, intent.createdAt());
            setInstant(statement, index, intent.updatedAt());
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<VerificationDispatchIntent> find(String operationId) {
        return withConnection(dataSource, "find verification dispatch intent", connection ->
                find(connection, operationId));
    }

    Optional<VerificationDispatchIntent> find(Connection connection, String operationId) throws SQLException {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        try (PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setString(1, operationId.trim());
            return findOne(statement);
        }
    }

    @Override
    public Optional<VerificationDispatchIntent> findLatest(TenantId tenantId, VerificationRunId runId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "verificationRunId");
        return withConnection(dataSource, "find latest verification dispatch intent", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_LATEST)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, runId.value());
                return findOne(statement);
            }
        });
    }

    @Override
    public Optional<VerificationDispatchIntent> claimNext(Instant now, Duration lease, String claimToken) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        return withConnection(dataSource, "claim verification dispatch intent", connection ->
                claimNextInTransaction(connection, now, lease, claimToken));
    }

    private Optional<VerificationDispatchIntent> claimNextInTransaction(
            Connection connection, Instant now, Duration lease, String claimToken) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (int attempt = 0; attempt < 16; attempt++) {
                Optional<VerificationDispatchIntent> candidate;
                try (PreparedStatement statement = connection.prepareStatement(FIND_CLAIMABLE)) {
                    setInstant(statement, 1, now);
                    candidate = findOne(statement);
                }
                if (candidate.isEmpty()) {
                    connection.commit();
                    return Optional.empty();
                }
                VerificationDispatchIntent observed = candidate.orElseThrow();
                boolean launchable = lockAndValidateStartBoundary(connection, observed, now);
                VerificationDispatchIntent claimed = observed.claim(claimToken, now, lease);
                if (!compareAndSet(connection, observed, claimed)) {
                    connection.rollback();
                    continue;
                }
                if (!launchable) {
                    VerificationDispatchIntent blocked = claimed.orphan(
                            claimToken, NO_LONGER_LAUNCHABLE, now);
                    if (!compareAndSet(connection, claimed, blocked)) {
                        throw new IllegalStateException("claimed verification intent lost its start-boundary CAS");
                    }
                    VerificationDispatchIntent persisted = readOwnTransition(connection, blocked).orElseThrow(() ->
                            new IllegalStateException("blocked verification intent lost its canonical readback"));
                    connection.commit();
                    return Optional.of(persisted);
                }
                VerificationDispatchIntent persisted = readOwnTransition(connection, claimed).orElseThrow(() ->
                        new IllegalStateException("claimed verification intent lost its canonical readback"));
                connection.commit();
                return Optional.of(persisted);
            }
            connection.commit();
            return Optional.empty();
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static boolean lockAndValidateStartBoundary(
            Connection connection, VerificationDispatchIntent intent, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_START_BOUNDARY)) {
            statement.setString(1, intent.tenantId().value());
            statement.setString(2, intent.verificationRunId().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                return "VERIFYING".equals(resultSet.getString("status"))
                        && "test".equals(resultSet.getString("current_phase"))
                        && intent.candidateId().value().equals(resultSet.getString("current_candidate_id"))
                        && resultSet.getString("candidate_hash")
                                .equals(resultSet.getString("current_candidate_hash"))
                        && resultSet.getTimestamp("cancellation_requested_at") == null
                        && now.isBefore(getInstant(resultSet, "deadline_at"));
            }
        }
    }

    @Override
    public Optional<VerificationDispatchIntent> claimExpiredRunningForReconciliation(
            Instant now, Duration lease, String claimToken) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        return withConnection(dataSource, "claim expired verification dispatch reconciliation", connection -> {
            for (int attempt = 0; attempt < 16; attempt++) {
                Optional<VerificationDispatchIntent> candidate;
                try (PreparedStatement statement = connection.prepareStatement(FIND_EXPIRED_RUNNING)) {
                    setInstant(statement, 1, now);
                    candidate = findOne(statement);
                }
                if (candidate.isEmpty()) {
                    return Optional.empty();
                }
                VerificationDispatchIntent claimed = candidate.orElseThrow().claim(claimToken, now, lease);
                if (compareAndSet(connection, candidate.orElseThrow(), claimed)) {
                    // This CAS is already committed. If another owner advanced it before readback,
                    // return no work rather than letting this caller act with that owner's snapshot.
                    return readOwnTransition(connection, claimed);
                }
            }
            return Optional.empty();
        });
    }

    /**
     * Timestamp precision belongs to the JDBC schema (microseconds on PostgreSQL), not the JVM
     * clock. Return the row actually stored by our CAS so downstream full-snapshot/lease fences
     * compare canonical values. Do not truncate all domain clocks or relax those exact fences.
     */
    private Optional<VerificationDispatchIntent> readOwnTransition(
            Connection connection, VerificationDispatchIntent expected) throws SQLException {
        return find(connection, expected.operationId()).filter(stored ->
                expected.sameImmutableIdentity(stored)
                        && stored.status() == expected.status()
                        && stored.version() == expected.version()
                        && stored.claimToken().equals(expected.claimToken())
                        && stored.attemptCount() == expected.attemptCount()
                        && stored.lastCode().equals(expected.lastCode())
                        && stored.createdAt().equals(expected.createdAt()));
    }

    @Override
    public boolean compareAndSet(VerificationDispatchIntent expected, VerificationDispatchIntent next) {
        return withConnection(dataSource, "CAS verification dispatch intent", connection ->
                compareAndSet(connection, expected, next));
    }

    boolean compareAndSet(Connection connection, VerificationDispatchIntent expected, VerificationDispatchIntent next)
            throws SQLException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        if (!expected.sameImmutableIdentity(next) || next.version() != expected.version() + 1) {
            throw new IllegalArgumentException("verification dispatch CAS changed immutable identity or version");
        }
        try (PreparedStatement statement = connection.prepareStatement(CAS)) {
            int index = 1;
            statement.setString(index++, next.status().name());
            setOptionalText(statement, index++, next.claimToken());
            setOptionalInstant(statement, index++, next.leaseUntil());
            statement.setInt(index++, next.attemptCount());
            setOptionalText(statement, index++, next.lastCode());
            statement.setLong(index++, next.version());
            setInstant(statement, index++, next.updatedAt());
            statement.setString(index++, expected.operationId());
            statement.setLong(index, expected.version());
            return statement.executeUpdate() == 1;
        }
    }

    private static Optional<VerificationDispatchIntent> findOne(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
        }
    }

    private static VerificationDispatchIntent map(ResultSet resultSet) throws SQLException {
        return new VerificationDispatchIntent(
                resultSet.getString("operation_id"),
                new TenantId(resultSet.getString("tenant_id")),
                new VerificationRunId(resultSet.getString("verification_run_id")),
                new CandidateId(resultSet.getString("candidate_id")),
                resultSet.getLong("expected_verification_run_version"),
                resultSet.getString("action_run_id"),
                resultSet.getString("attempt_token_hash"),
                getInstant(resultSet, "deadline_at"),
                VerificationDispatchIntentStatus.valueOf(resultSet.getString("status")),
                getOptionalText(resultSet, "claim_token"),
                getOptionalInstant(resultSet, "lease_until"),
                resultSet.getInt("attempt_count"),
                getOptionalText(resultSet, "last_code"),
                resultSet.getLong("version"),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "updated_at"));
    }
}
