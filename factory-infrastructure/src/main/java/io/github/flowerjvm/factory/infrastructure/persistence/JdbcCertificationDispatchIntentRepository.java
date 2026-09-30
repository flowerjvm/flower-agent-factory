package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntent;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntentStatus;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC version-CAS queue dedicated to governed deferred Certification issuance. */
public final class JdbcCertificationDispatchIntentRepository
        implements CertificationDispatchIntentRepository {
    private static final String INSERT = """
            INSERT INTO factory_certification_dispatch_intent (
                operation_id, tenant_id, certification_id, input_lock_manifest_hash,
                expected_certification_version, action_run_id, attempt_token_hash, deadline_at,
                status, claim_token, lease_until, attempt_count, last_code, version,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND = """
            SELECT * FROM factory_certification_dispatch_intent
            WHERE operation_id = ?
            """;
    private static final String FIND_LATEST = """
            SELECT * FROM factory_certification_dispatch_intent
            WHERE tenant_id = ? AND certification_id = ?
            ORDER BY expected_certification_version DESC, created_at DESC, operation_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String FIND_CLAIMABLE = """
            SELECT * FROM factory_certification_dispatch_intent
            WHERE status = 'PENDING'
               OR (status = 'UNCERTAIN' AND lease_until <= ?)
            ORDER BY created_at, operation_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String FIND_EXPIRED_RUNNING = """
            SELECT * FROM factory_certification_dispatch_intent
            WHERE status = 'RUNNING' AND lease_until <= ?
            ORDER BY lease_until, operation_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String CAS = """
            UPDATE factory_certification_dispatch_intent SET
                status = ?, claim_token = ?, lease_until = ?, attempt_count = ?, last_code = ?,
                version = ?, updated_at = ?
            WHERE operation_id = ? AND version = ?
            """;

    private final DataSource dataSource;

    public JdbcCertificationDispatchIntentRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void create(CertificationDispatchIntent intent) {
        withConnection(dataSource, "create Certification dispatch intent", connection -> {
            create(connection, intent);
            return null;
        });
    }

    void create(Connection connection, CertificationDispatchIntent intent) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(intent, "intent");
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            int index = 1;
            statement.setString(index++, intent.operationId());
            statement.setString(index++, intent.tenantId().value());
            statement.setString(index++, intent.certificationId().value());
            statement.setString(index++, intent.inputLockManifestHash().sha256());
            statement.setLong(index++, intent.expectedCertificationVersion());
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
    public Optional<CertificationDispatchIntent> find(String operationId) {
        return withConnection(dataSource, "find Certification dispatch intent", connection ->
                find(connection, operationId));
    }

    Optional<CertificationDispatchIntent> find(Connection connection, String operationId)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        try (PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setString(1, operationId.trim());
            return findOne(statement);
        }
    }

    @Override
    public Optional<CertificationDispatchIntent> findLatest(
            TenantId tenantId, CertificationId certificationId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(certificationId, "certificationId");
        return withConnection(dataSource, "find latest Certification dispatch intent", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_LATEST)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, certificationId.value());
                return findOne(statement);
            }
        });
    }

    @Override
    public Optional<CertificationDispatchIntent> claimNext(
            Instant now, Duration lease, String claimToken) {
        validateClaim(now, lease, claimToken);
        return withConnection(dataSource, "claim Certification dispatch intent", connection -> {
            for (int attempt = 0; attempt < 16; attempt++) {
                Optional<CertificationDispatchIntent> candidate;
                try (PreparedStatement statement = connection.prepareStatement(FIND_CLAIMABLE)) {
                    setInstant(statement, 1, now);
                    candidate = findOne(statement);
                }
                if (candidate.isEmpty()) {
                    return Optional.empty();
                }
                CertificationDispatchIntent observed = candidate.orElseThrow();
                CertificationDispatchIntent claimed = observed.claim(claimToken, now, lease);
                if (compareAndSet(connection, observed, claimed)) {
                    return Optional.of(claimed);
                }
            }
            return Optional.empty();
        });
    }

    @Override
    public Optional<CertificationDispatchIntent> claimExpiredRunning(
            Instant now, Duration lease, String claimToken) {
        validateClaim(now, lease, claimToken);
        return withConnection(dataSource, "claim expired Certification dispatch intent", connection -> {
            for (int attempt = 0; attempt < 16; attempt++) {
                Optional<CertificationDispatchIntent> candidate;
                try (PreparedStatement statement = connection.prepareStatement(FIND_EXPIRED_RUNNING)) {
                    setInstant(statement, 1, now);
                    candidate = findOne(statement);
                }
                if (candidate.isEmpty()) {
                    return Optional.empty();
                }
                CertificationDispatchIntent observed = candidate.orElseThrow();
                CertificationDispatchIntent claimed = observed.claim(claimToken, now, lease);
                if (compareAndSet(connection, observed, claimed)) {
                    return Optional.of(claimed);
                }
            }
            return Optional.empty();
        });
    }

    @Override
    public boolean compareAndSet(
            CertificationDispatchIntent expected, CertificationDispatchIntent next) {
        return withConnection(dataSource, "CAS Certification dispatch intent", connection ->
                compareAndSet(connection, expected, next));
    }

    boolean compareAndSet(
            Connection connection,
            CertificationDispatchIntent expected,
            CertificationDispatchIntent next) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        if (!expected.sameImmutableIdentity(next) || next.version() != expected.version() + 1) {
            throw new IllegalArgumentException(
                    "Certification dispatch CAS changed immutable identity or version");
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

    private static void validateClaim(Instant now, Duration lease, String claimToken) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("claimToken must not be blank");
        }
    }

    private static Optional<CertificationDispatchIntent> findOne(PreparedStatement statement)
            throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
        }
    }

    private static CertificationDispatchIntent map(ResultSet resultSet) throws SQLException {
        return new CertificationDispatchIntent(
                resultSet.getString("operation_id"),
                new TenantId(resultSet.getString("tenant_id")),
                new CertificationId(resultSet.getString("certification_id")),
                new ContentHash(resultSet.getString("input_lock_manifest_hash")),
                resultSet.getLong("expected_certification_version"),
                resultSet.getString("action_run_id"),
                resultSet.getString("attempt_token_hash"),
                getInstant(resultSet, "deadline_at"),
                CertificationDispatchIntentStatus.valueOf(resultSet.getString("status")),
                getOptionalText(resultSet, "claim_token"),
                getOptionalInstant(resultSet, "lease_until"),
                resultSet.getInt("attempt_count"),
                getOptionalText(resultSet, "last_code"),
                resultSet.getLong("version"),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "updated_at"));
    }
}
