package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntent;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentStatus;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC version-CAS queue dedicated to governed Reference Assembly release. */
public final class JdbcReferenceAssemblyReleaseDispatchIntentRepository
        implements ReferenceAssemblyReleaseDispatchIntentRepository {
    private static final String INSERT = """
            INSERT INTO factory_reference_assembly_release_intent (
                operation_id, tenant_id, reference_assembly_id,
                assembly_manifest_hash, inspection_report_hash,
                release_decision_point_id, release_subject_hash,
                expected_reference_assembly_version, action_run_id, attempt_token_hash,
                deadline_at, status, claim_token, lease_until, attempt_count, last_code,
                version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND = """
            SELECT * FROM factory_reference_assembly_release_intent
            WHERE operation_id = ?
            """;
    private static final String FIND_LATEST = """
            SELECT * FROM factory_reference_assembly_release_intent
            WHERE tenant_id = ? AND reference_assembly_id = ?
            ORDER BY expected_reference_assembly_version DESC, created_at DESC, operation_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String FIND_CLAIMABLE = """
            SELECT * FROM factory_reference_assembly_release_intent
            WHERE status = 'PENDING'
               OR (status = 'UNCERTAIN' AND lease_until <= ?)
            ORDER BY created_at, operation_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String FIND_EXPIRED_RUNNING = """
            SELECT * FROM factory_reference_assembly_release_intent
            WHERE status = 'RUNNING' AND lease_until <= ?
            ORDER BY lease_until, operation_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String CAS = """
            UPDATE factory_reference_assembly_release_intent SET
                status = ?, claim_token = ?, lease_until = ?, attempt_count = ?, last_code = ?,
                version = ?, updated_at = ?
            WHERE operation_id = ? AND version = ?
            """;

    private final DataSource dataSource;

    public JdbcReferenceAssemblyReleaseDispatchIntentRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void create(ReferenceAssemblyReleaseDispatchIntent intent) {
        withConnection(dataSource, "create Reference Assembly release intent", connection -> {
            create(connection, intent);
            return null;
        });
    }

    void create(Connection connection, ReferenceAssemblyReleaseDispatchIntent intent)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(intent, "intent");
        if (intent.status() != ReferenceAssemblyReleaseDispatchIntentStatus.PENDING
                || intent.version() != 0) {
            throw new IllegalArgumentException(
                    "Reference Assembly release intent create requires pristine PENDING version zero");
        }
        Optional<ReferenceAssemblyReleaseDispatchIntent> before =
                find(connection, intent.operationId());
        if (before.isPresent()) {
            if (before.filter(intent::equals).isPresent()) {
                return;
            }
            throw new DuplicateLedgerRecordException(
                    "create Reference Assembly release intent",
                    new IllegalStateException("operationId is already bound to another owner snapshot"));
        }
        Savepoint savepoint = connection.getAutoCommit()
                ? null
                : connection.setSavepoint("reference_release_intent_create");
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            bindInsert(statement, intent);
            statement.executeUpdate();
        } catch (SQLException possibleDuplicate) {
            if (savepoint != null) {
                connection.rollback(savepoint);
            }
            if ("23505".equals(possibleDuplicate.getSQLState())
                    && find(connection, intent.operationId()).filter(intent::equals).isPresent()) {
                return;
            }
            throw possibleDuplicate;
        } finally {
            if (savepoint != null) {
                try {
                    connection.releaseSavepoint(savepoint);
                } catch (SQLException ignoredAfterRollback) {
                    // Some drivers release savepoints during rollback-to-savepoint. The enclosing
                    // transaction remains authoritative and the insert/read checks still apply.
                }
            }
        }
        ReferenceAssemblyReleaseDispatchIntent stored = find(connection, intent.operationId())
                .orElseThrow(() -> persistenceFailure(
                        "created Reference Assembly release intent is not readable"));
        if (!stored.equals(intent)) {
            throw persistenceFailure(
                    "created Reference Assembly release intent differs from its canonical snapshot");
        }
    }

    @Override
    public Optional<ReferenceAssemblyReleaseDispatchIntent> find(String operationId) {
        return withConnection(dataSource, "find Reference Assembly release intent", connection ->
                find(connection, operationId));
    }

    Optional<ReferenceAssemblyReleaseDispatchIntent> find(
            Connection connection, String operationId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        requireOperationId(operationId);
        try (PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setString(1, operationId);
            return findOne(statement);
        }
    }

    @Override
    public Optional<ReferenceAssemblyReleaseDispatchIntent> findLatest(
            TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(referenceAssemblyId, "referenceAssemblyId");
        return withConnection(dataSource, "find latest Reference Assembly release intent", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_LATEST)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, referenceAssemblyId.value());
                return findOne(statement);
            }
        });
    }

    @Override
    public Optional<ReferenceAssemblyReleaseDispatchIntent> claimNext(
            Instant now, Duration lease, String claimToken) {
        validateClaim(now, lease, claimToken);
        return withConnection(dataSource, "claim Reference Assembly release intent", connection -> {
            for (int attempt = 0; attempt < 16; attempt++) {
                Optional<ReferenceAssemblyReleaseDispatchIntent> candidate;
                try (PreparedStatement statement = connection.prepareStatement(FIND_CLAIMABLE)) {
                    setInstant(statement, 1, now);
                    candidate = findOne(statement);
                }
                if (candidate.isEmpty()) {
                    return Optional.empty();
                }
                ReferenceAssemblyReleaseDispatchIntent observed = candidate.orElseThrow();
                ReferenceAssemblyReleaseDispatchIntent claimed =
                        observed.claim(claimToken, now, lease);
                if (compareAndSet(connection, observed, claimed)) {
                    return Optional.of(claimed);
                }
            }
            return Optional.empty();
        });
    }

    @Override
    public Optional<ReferenceAssemblyReleaseDispatchIntent> claimExpiredRunning(
            Instant now, Duration lease, String claimToken) {
        validateClaim(now, lease, claimToken);
        return withConnection(
                dataSource, "claim expired Reference Assembly release intent", connection -> {
                    for (int attempt = 0; attempt < 16; attempt++) {
                        Optional<ReferenceAssemblyReleaseDispatchIntent> candidate;
                        try (PreparedStatement statement =
                                connection.prepareStatement(FIND_EXPIRED_RUNNING)) {
                            setInstant(statement, 1, now);
                            candidate = findOne(statement);
                        }
                        if (candidate.isEmpty()) {
                            return Optional.empty();
                        }
                        ReferenceAssemblyReleaseDispatchIntent observed = candidate.orElseThrow();
                        ReferenceAssemblyReleaseDispatchIntent claimed =
                                observed.claim(claimToken, now, lease);
                        if (compareAndSet(connection, observed, claimed)) {
                            return Optional.of(claimed);
                        }
                    }
                    return Optional.empty();
                });
    }

    @Override
    public boolean compareAndSet(
            ReferenceAssemblyReleaseDispatchIntent expected,
            ReferenceAssemblyReleaseDispatchIntent next) {
        return withConnection(dataSource, "CAS Reference Assembly release intent", connection ->
                compareAndSet(connection, expected, next));
    }

    boolean compareAndSet(
            Connection connection,
            ReferenceAssemblyReleaseDispatchIntent expected,
            ReferenceAssemblyReleaseDispatchIntent next) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        if (!expected.sameImmutableIdentity(next)
                || !expected.createdAt().equals(next.createdAt())
                || next.version() != Math.addExact(expected.version(), 1)
                || !isCanonicalNext(expected, next)) {
            throw new IllegalArgumentException(
                    "Reference Assembly release CAS is not one canonical domain transition");
        }
        Optional<ReferenceAssemblyReleaseDispatchIntent> canonical =
                find(connection, expected.operationId());
        if (canonical.filter(expected::equals).isEmpty()) {
            return false;
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
            if (statement.executeUpdate() != 1) {
                return false;
            }
        }
        ReferenceAssemblyReleaseDispatchIntent stored = find(connection, next.operationId())
                .orElseThrow(() -> persistenceFailure(
                        "CAS-updated Reference Assembly release intent is not readable"));
        if (!stored.equals(next)) {
            throw persistenceFailure(
                    "CAS-updated Reference Assembly release intent differs from its next snapshot");
        }
        return true;
    }

    private static boolean isCanonicalNext(
            ReferenceAssemblyReleaseDispatchIntent expected,
            ReferenceAssemblyReleaseDispatchIntent next) {
        try {
            return switch (next.status()) {
                case RUNNING -> expected.claim(
                                next.claimToken().orElseThrow(),
                                next.updatedAt(),
                                Duration.between(
                                        next.updatedAt(), next.leaseUntil().orElseThrow()))
                        .equals(next);
                case UNCERTAIN -> expected.uncertain(
                                expected.claimToken().orElseThrow(),
                                next.lastCode().orElseThrow(),
                                next.updatedAt(),
                                next.leaseUntil().orElseThrow())
                        .equals(next);
                case COMPLETED -> expected.complete(
                                expected.claimToken().orElseThrow(),
                                next.lastCode().orElseThrow(),
                                next.updatedAt())
                        .equals(next);
                case ORPHANED -> expected.orphan(
                                expected.claimToken().orElseThrow(),
                                next.lastCode().orElseThrow(),
                                next.updatedAt())
                        .equals(next);
                case ORPHANED_BEFORE_WAITING -> expected.orphanBeforeWaiting(
                                expected.claimToken().orElseThrow(),
                                next.lastCode().orElseThrow(),
                                next.updatedAt())
                        .equals(next);
                case PENDING -> false;
            };
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static void bindInsert(
            PreparedStatement statement, ReferenceAssemblyReleaseDispatchIntent intent)
            throws SQLException {
        int index = 1;
        statement.setString(index++, intent.operationId());
        statement.setString(index++, intent.tenantId().value());
        statement.setString(index++, intent.referenceAssemblyId().value());
        statement.setString(index++, intent.assemblyManifestHash().sha256());
        statement.setString(index++, intent.inspectionReportHash().sha256());
        statement.setString(index++, intent.releaseDecisionPointId().value());
        statement.setString(index++, intent.releaseSubjectHash().sha256());
        statement.setLong(index++, intent.expectedReferenceAssemblyVersion());
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
    }

    private static Optional<ReferenceAssemblyReleaseDispatchIntent> findOne(
            PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
        }
    }

    private static ReferenceAssemblyReleaseDispatchIntent map(ResultSet resultSet)
            throws SQLException {
        return new ReferenceAssemblyReleaseDispatchIntent(
                resultSet.getString("operation_id"),
                new TenantId(resultSet.getString("tenant_id")),
                new ReferenceAssemblyId(resultSet.getString("reference_assembly_id")),
                exactContentHash(resultSet, "assembly_manifest_hash"),
                exactContentHash(resultSet, "inspection_report_hash"),
                new DecisionPointId(resultSet.getString("release_decision_point_id")),
                exactContentHash(resultSet, "release_subject_hash"),
                resultSet.getLong("expected_reference_assembly_version"),
                resultSet.getString("action_run_id"),
                exactSha256(resultSet, "attempt_token_hash"),
                getInstant(resultSet, "deadline_at"),
                ReferenceAssemblyReleaseDispatchIntentStatus.valueOf(
                        resultSet.getString("status")),
                getOptionalText(resultSet, "claim_token"),
                getOptionalInstant(resultSet, "lease_until"),
                resultSet.getInt("attempt_count"),
                getOptionalText(resultSet, "last_code"),
                resultSet.getLong("version"),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "updated_at"));
    }

    private static ContentHash exactContentHash(ResultSet resultSet, String column)
            throws SQLException {
        return new ContentHash(exactSha256(resultSet, column));
    }

    private static String exactSha256(ResultSet resultSet, String column) throws SQLException {
        String raw = resultSet.getString(column);
        try {
            ContentHash hash = new ContentHash(raw);
            if (!hash.sha256().equals(raw)) {
                throw new SQLException(column + " is not canonical lowercase SHA-256");
            }
            return raw;
        } catch (IllegalArgumentException invalid) {
            throw new SQLException(column + " is not canonical lowercase SHA-256", invalid);
        }
    }

    private static void validateClaim(Instant now, Duration lease, String claimToken) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        if (!now.equals(now.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("claim time must use microsecond precision");
        }
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        Instant leaseUntil = now.plus(lease);
        if (!leaseUntil.equals(leaseUntil.truncatedTo(ChronoUnit.MICROS))
                || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("lease must advance by exact microseconds");
        }
        if (claimToken == null
                || claimToken.isBlank()
                || claimToken.length() > 128
                || !claimToken.equals(claimToken.trim())
                || claimToken.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("claimToken must be a bounded exact identity");
        }
    }

    private static void requireOperationId(String operationId) {
        if (operationId == null
                || !operationId.matches("reference-assembly-release:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "operationId must be an exact Reference Assembly release identity");
        }
    }

    private static FactoryPersistenceException persistenceFailure(String message) {
        return new FactoryPersistenceException(message, new IllegalStateException(message));
    }
}
