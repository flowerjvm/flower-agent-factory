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

import io.github.flowerjvm.factory.application.outbox.DispatchClaimPurpose;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxRepository;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
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

/** JDBC outbox ledger with separate submit and status-only reconciliation claims. */
public final class JdbcDispatchOutboxRepository implements DispatchOutboxRepository {
    private static final int MAX_CLAIM_CAS_ATTEMPTS = 32;
    private static final String INSERT = """
            INSERT INTO factory_dispatch_outbox (
                outbox_id, tenant_id, operation_type, aggregate_type, aggregate_id,
                operation_id, payload_artifact_ref, status,
                claim_token, claim_purpose, claimed_at, lease_until,
                available_at, attempt_count, last_code, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND = """
            SELECT * FROM factory_dispatch_outbox
            WHERE tenant_id = ? AND outbox_id = ?
            """;
    private static final String FIND_BY_OPERATION = """
            SELECT * FROM factory_dispatch_outbox
            WHERE tenant_id = ? AND operation_type = ? AND operation_id = ?
            """;
    private static final String FIND_DUE_SUBMISSION = """
            SELECT * FROM factory_dispatch_outbox
            WHERE operation_type = ?
              AND status IN ('PENDING', 'RETRY_WAIT')
              AND available_at <= ?
            ORDER BY available_at, created_at, outbox_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String FIND_EXPIRED_RECONCILIATION = """
            SELECT * FROM factory_dispatch_outbox
            WHERE operation_type = ?
              AND status = 'DISPATCHING'
              AND lease_until <= ?
            ORDER BY lease_until, updated_at, outbox_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String CAS = """
            UPDATE factory_dispatch_outbox SET
                status = ?, claim_token = ?, claim_purpose = ?, claimed_at = ?, lease_until = ?,
                available_at = ?, attempt_count = ?, last_code = ?, version = ?, updated_at = ?
            WHERE tenant_id = ?
              AND outbox_id = ?
              AND operation_type = ?
              AND aggregate_type = ?
              AND aggregate_id = ?
              AND operation_id = ?
              AND payload_artifact_ref = ?
              AND status = ?
              AND version = ?
            """;

    private final DataSource dataSource;

    public JdbcDispatchOutboxRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void create(DispatchOutbox outbox) {
        Objects.requireNonNull(outbox, "outbox");
        withConnection(dataSource, "create DispatchOutbox", connection -> {
            create(connection, outbox);
            return null;
        });
    }

    void create(Connection connection, DispatchOutbox outbox) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        validateCreate(outbox);
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            bindInsert(statement, outbox);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<DispatchOutbox> find(TenantId tenantId, DispatchOutboxId outboxId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(outboxId, "outboxId");
        return withConnection(dataSource, "find DispatchOutbox", connection ->
                find(connection, tenantId, outboxId));
    }

    Optional<DispatchOutbox> find(
            Connection connection, TenantId tenantId, DispatchOutboxId outboxId) throws SQLException {
        return find(connection, tenantId, outboxId, false);
    }

    Optional<DispatchOutbox> find(
            Connection connection,
            TenantId tenantId,
            DispatchOutboxId outboxId,
            boolean forUpdate) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(outboxId, "outboxId");
        String sql = forUpdate ? FIND + " FOR UPDATE" : FIND;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, outboxId.value());
            return findOne(statement);
        }
    }

    @Override
    public Optional<DispatchOutbox> findByOperation(
            TenantId tenantId, String operationType, String operationId) {
        Objects.requireNonNull(tenantId, "tenantId");
        String exactOperationType = requireText(operationType, "operationType");
        String exactOperationId = requireText(operationId, "operationId");
        return withConnection(dataSource, "find DispatchOutbox by operation", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_BY_OPERATION)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, exactOperationType);
                statement.setString(3, exactOperationId);
                return findOne(statement);
            }
        });
    }

    @Override
    public Optional<DispatchOutbox> claimNextForSubmission(
            String operationType, Instant now, Duration lease, String claimToken) {
        String exactOperationType = requireText(operationType, "operationType");
        validateClaimArguments(now, lease, claimToken);
        return withConnection(dataSource, "claim DispatchOutbox for submission", connection -> {
            for (int attempt = 0; attempt < MAX_CLAIM_CAS_ATTEMPTS; attempt++) {
                Optional<DispatchOutbox> candidate = findDueSubmission(connection, exactOperationType, now);
                if (candidate.isEmpty()) return Optional.empty();
                DispatchOutbox observed = candidate.orElseThrow();
                DispatchOutbox claimed = observed.claimForSubmission(claimToken, now, lease);
                if (compareAndSet(connection, observed, claimed)) return Optional.of(claimed);
            }
            return Optional.empty();
        });
    }

    @Override
    public Optional<DispatchOutbox> claimExpiredForReconciliation(
            String operationType, Instant now, Duration lease, String claimToken) {
        String exactOperationType = requireText(operationType, "operationType");
        validateClaimArguments(now, lease, claimToken);
        return withConnection(dataSource, "claim DispatchOutbox for reconciliation", connection -> {
            for (int attempt = 0; attempt < MAX_CLAIM_CAS_ATTEMPTS; attempt++) {
                Optional<DispatchOutbox> candidate = findExpiredReconciliation(
                        connection, exactOperationType, now);
                if (candidate.isEmpty()) return Optional.empty();
                DispatchOutbox observed = candidate.orElseThrow();
                DispatchOutbox claimed = observed.claimForReconciliation(claimToken, now, lease);
                if (compareAndSet(connection, observed, claimed)) return Optional.of(claimed);
            }
            return Optional.empty();
        });
    }

    @Override
    public boolean compareAndSet(DispatchOutbox expected, DispatchOutbox next) {
        validateCas(expected, next);
        return withConnection(dataSource, "CAS DispatchOutbox", connection ->
                compareAndSet(connection, expected, next));
    }

    boolean compareAndSet(Connection connection, DispatchOutbox expected, DispatchOutbox next)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        validateCas(expected, next);
        try (PreparedStatement statement = connection.prepareStatement(CAS)) {
            int index = 1;
            statement.setString(index++, next.status().name());
            setOptionalText(statement, index++, next.claimToken());
            setOptionalText(statement, index++, next.claimPurpose().map(Enum::name));
            setOptionalInstant(statement, index++, next.claimedAt());
            setOptionalInstant(statement, index++, next.leaseUntil());
            setInstant(statement, index++, next.availableAt());
            statement.setInt(index++, next.attemptCount());
            setOptionalText(statement, index++, next.lastCode());
            statement.setLong(index++, next.version());
            setInstant(statement, index++, next.updatedAt());
            statement.setString(index++, expected.tenantId().value());
            statement.setString(index++, expected.outboxId().value());
            statement.setString(index++, expected.operationType());
            statement.setString(index++, expected.aggregateType());
            statement.setString(index++, expected.aggregateId());
            statement.setString(index++, expected.operationId());
            statement.setString(index++, expected.payloadArtifactRef().value());
            statement.setString(index++, expected.status().name());
            statement.setLong(index, expected.version());
            return statement.executeUpdate() == 1;
        }
    }

    private static Optional<DispatchOutbox> findDueSubmission(
            Connection connection, String operationType, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_DUE_SUBMISSION)) {
            statement.setString(1, operationType);
            setInstant(statement, 2, now);
            return findOne(statement);
        }
    }

    private static Optional<DispatchOutbox> findExpiredReconciliation(
            Connection connection, String operationType, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_EXPIRED_RECONCILIATION)) {
            statement.setString(1, operationType);
            setInstant(statement, 2, now);
            return findOne(statement);
        }
    }

    private static Optional<DispatchOutbox> findOne(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
        }
    }

    private static void validateCreate(DispatchOutbox outbox) {
        Objects.requireNonNull(outbox, "outbox");
        if (outbox.status() != DispatchOutboxStatus.PENDING
                || outbox.version() != 0
                || outbox.attemptCount() != 0
                || outbox.claimToken().isPresent()
                || outbox.claimPurpose().isPresent()
                || outbox.claimedAt().isPresent()
                || outbox.leaseUntil().isPresent()) {
            throw new IllegalArgumentException("DispatchOutbox must be created as an unclaimed PENDING row");
        }
    }

    private static void validateCas(DispatchOutbox expected, DispatchOutbox next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        requireCas(expected.version(), next.version());
        requireSame(expected.outboxId().equals(next.outboxId()), "outboxId");
        requireSame(expected.tenantId().equals(next.tenantId()), "tenantId");
        requireSame(expected.operationType().equals(next.operationType()), "operationType");
        requireSame(expected.aggregateType().equals(next.aggregateType()), "aggregateType");
        requireSame(expected.aggregateId().equals(next.aggregateId()), "aggregateId");
        requireSame(expected.operationId().equals(next.operationId()), "operationId");
        requireSame(expected.payloadArtifactRef().equals(next.payloadArtifactRef()), "payloadArtifactRef");
        requireSame(expected.createdAt().equals(next.createdAt()), "createdAt");
        if (expected.status().isTerminal()) {
            throw new IllegalArgumentException("terminal DispatchOutbox must not be overwritten");
        }
        if (next.updatedAt().isBefore(expected.updatedAt())) {
            throw new IllegalArgumentException("updatedAt must not move backwards");
        }
        switch (expected.status()) {
            case PENDING, RETRY_WAIT -> validateFreshSubmission(expected, next);
            case DISPATCHING -> validateClaimedTransition(expected, next);
            case DISPATCHED, CONFIRMED, SUPERSEDED, MANUAL_REVIEW ->
                    throw new IllegalArgumentException("terminal DispatchOutbox must not be overwritten");
        }
    }

    private static void validateFreshSubmission(DispatchOutbox expected, DispatchOutbox next) {
        if (next.status() == DispatchOutboxStatus.MANUAL_REVIEW) {
            if (!WorkerOutboxOperations.DISPATCH.equals(expected.operationType())
                    || expected.claimToken().isPresent()
                    || expected.claimPurpose().isPresent()
                    || expected.claimedAt().isPresent()
                    || expected.leaseUntil().isPresent()
                    || next.claimToken().isPresent()
                    || next.claimPurpose().isPresent()
                    || next.claimedAt().isPresent()
                    || next.leaseUntil().isPresent()
                    || next.attemptCount() != expected.attemptCount()
                    || !next.availableAt().equals(expected.availableAt())
                    || next.lastCode().isEmpty()) {
                throw new IllegalArgumentException(
                        "only a fresh Worker dispatch may enter pre-submit manual review");
            }
            return;
        }
        if (next.status() != DispatchOutboxStatus.DISPATCHING
                || next.claimPurpose().filter(DispatchClaimPurpose.SUBMIT::equals).isEmpty()
                || next.claimToken().isEmpty()
                || next.claimedAt().isEmpty()
                || next.leaseUntil().isEmpty()
                || next.attemptCount() != expected.attemptCount() + 1
                || !next.availableAt().equals(expected.availableAt())
                || !next.lastCode().equals(expected.lastCode())
                || next.claimedAt().orElseThrow().isBefore(expected.availableAt())) {
            throw new IllegalArgumentException(
                    "PENDING/RETRY_WAIT may transition only to a fresh SUBMIT claim");
        }
    }

    private static void validateClaimedTransition(DispatchOutbox expected, DispatchOutbox next) {
        if (expected.claimToken().isEmpty()
                || expected.claimPurpose().isEmpty()
                || expected.claimedAt().isEmpty()
                || expected.leaseUntil().isEmpty()) {
            throw new IllegalArgumentException("DISPATCHING snapshot must contain a complete claim");
        }
        if (next.status() == DispatchOutboxStatus.DISPATCHING) {
            boolean sameClaimObservation = next.claimToken().equals(expected.claimToken())
                    && next.claimPurpose().equals(expected.claimPurpose())
                    && next.claimedAt().equals(expected.claimedAt())
                    && next.leaseUntil().equals(expected.leaseUntil())
                    && next.attemptCount() == expected.attemptCount()
                    && next.availableAt().equals(expected.availableAt());
            if (sameClaimObservation) {
                if (next.lastCode().isEmpty()) {
                    throw new IllegalArgumentException(
                            "a retained claim observation requires a stable code");
                }
                return;
            }
            if (next.claimPurpose().filter(DispatchClaimPurpose.RECONCILE::equals).isEmpty()
                    || next.claimToken().isEmpty()
                    || next.claimedAt().isEmpty()
                    || next.leaseUntil().isEmpty()
                    || next.attemptCount() != expected.attemptCount()
                    || !next.availableAt().equals(expected.availableAt())
                    || !next.lastCode().equals(expected.lastCode())
                    || next.claimedAt().orElseThrow().isBefore(expected.leaseUntil().orElseThrow())) {
                throw new IllegalArgumentException(
                        "expired DISPATCHING may be reclaimed only for reconciliation");
            }
            return;
        }
        if (next.status() == DispatchOutboxStatus.RETRY_WAIT) {
            if (next.attemptCount() != expected.attemptCount()
                    || next.lastCode().isEmpty()
                    || next.availableAt().isBefore(next.updatedAt())) {
                throw new IllegalArgumentException("retry requires proven no-effect metadata");
            }
            return;
        }
        if (!next.status().isTerminal() || next.attemptCount() != expected.attemptCount()) {
            throw new IllegalArgumentException("illegal claimed DispatchOutbox transition");
        }
    }

    private static void validateClaimArguments(Instant now, Duration lease, String claimToken) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        requireText(claimToken, "claimToken");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
    }

    private static void bindInsert(PreparedStatement statement, DispatchOutbox outbox) throws SQLException {
        int index = 1;
        statement.setString(index++, outbox.outboxId().value());
        statement.setString(index++, outbox.tenantId().value());
        statement.setString(index++, outbox.operationType());
        statement.setString(index++, outbox.aggregateType());
        statement.setString(index++, outbox.aggregateId());
        statement.setString(index++, outbox.operationId());
        statement.setString(index++, outbox.payloadArtifactRef().value());
        statement.setString(index++, outbox.status().name());
        setOptionalText(statement, index++, outbox.claimToken());
        setOptionalText(statement, index++, outbox.claimPurpose().map(Enum::name));
        setOptionalInstant(statement, index++, outbox.claimedAt());
        setOptionalInstant(statement, index++, outbox.leaseUntil());
        setInstant(statement, index++, outbox.availableAt());
        statement.setInt(index++, outbox.attemptCount());
        setOptionalText(statement, index++, outbox.lastCode());
        statement.setLong(index++, outbox.version());
        setInstant(statement, index++, outbox.createdAt());
        setInstant(statement, index, outbox.updatedAt());
    }

    private static DispatchOutbox map(ResultSet resultSet) throws SQLException {
        return new DispatchOutbox(
                new DispatchOutboxId(resultSet.getString("outbox_id")),
                new TenantId(resultSet.getString("tenant_id")),
                resultSet.getString("operation_type"),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                resultSet.getString("operation_id"),
                new ArtifactReference(resultSet.getString("payload_artifact_ref")),
                DispatchOutboxStatus.valueOf(resultSet.getString("status")),
                getOptionalText(resultSet, "claim_token"),
                getOptionalText(resultSet, "claim_purpose").map(DispatchClaimPurpose::valueOf),
                getOptionalInstant(resultSet, "claimed_at"),
                getOptionalInstant(resultSet, "lease_until"),
                getInstant(resultSet, "available_at"),
                resultSet.getInt("attempt_count"),
                getOptionalText(resultSet, "last_code"),
                resultSet.getLong("version"),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "updated_at"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
