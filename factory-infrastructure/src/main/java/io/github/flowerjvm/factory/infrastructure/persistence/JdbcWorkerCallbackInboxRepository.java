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

import io.github.flowerjvm.factory.application.work.WorkerCallbackInboxEntry;
import io.github.flowerjvm.factory.application.work.WorkerCallbackInboxRepository;
import io.github.flowerjvm.factory.application.work.WorkerCallbackInboxStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC tokenless callback inbox with bounded owner-aware claims. */
public final class JdbcWorkerCallbackInboxRepository implements WorkerCallbackInboxRepository {
    private static final int MAX_CLAIM_CAS_ATTEMPTS = 32;
    private static final long MAX_CALLBACK_PAYLOAD_BYTES = 65_536L;
    private static final String CALLBACK_MEDIA_TYPE = "application/json";
    private static final String INSERT = """
            INSERT INTO factory_worker_callback_inbox (
                callback_id, tenant_id, worker_binding_id, event_id, work_order_id,
                worker_run_id, operation_id, attempt_token_hash, payload_artifact_ref, payload_hash,
                status, available_at, claim_token, lease_until, delivery_count, last_code,
                version, received_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND = """
            SELECT * FROM factory_worker_callback_inbox
            WHERE tenant_id = ? AND callback_id = ?
            """;
    private static final String FIND_EVENT = """
            SELECT * FROM factory_worker_callback_inbox
            WHERE tenant_id = ? AND worker_binding_id = ? AND event_id = ?
            """;
    private static final String FIND_DUE = """
            SELECT * FROM factory_worker_callback_inbox
            WHERE status = 'RECEIVED' AND available_at <= ?
            ORDER BY available_at, received_at, callback_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String FIND_EXPIRED = """
            SELECT * FROM factory_worker_callback_inbox
            WHERE status = 'PROCESSING' AND lease_until <= ?
            ORDER BY lease_until, updated_at, callback_id
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String CAS = """
            UPDATE factory_worker_callback_inbox SET
                status = ?, available_at = ?, claim_token = ?, lease_until = ?,
                delivery_count = ?, last_code = ?, version = ?, updated_at = ?
            WHERE tenant_id = ?
              AND callback_id = ?
              AND worker_binding_id = ?
              AND event_id = ?
              AND work_order_id = ?
              AND worker_run_id = ?
              AND operation_id = ?
              AND attempt_token_hash = ?
              AND payload_artifact_ref = ?
              AND payload_hash = ?
              AND status = ?
              AND version = ?
            """;
    private static final String FIND_PAYLOAD_ARTIFACT = """
            SELECT content_hash, media_type, content_size
            FROM factory_artifact
            WHERE tenant_id = ? AND artifact_ref = ?
            """;
    private static final String FIND_BOUNDED_PAYLOAD_CONTENT = """
            SELECT content_base64
            FROM factory_artifact
            WHERE tenant_id = ?
              AND artifact_ref = ?
              AND content_hash = ?
              AND media_type = ?
              AND content_size = ?
              AND CHAR_LENGTH(content_base64) = ?
            """;

    private final DataSource dataSource;

    public JdbcWorkerCallbackInboxRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void create(WorkerCallbackInboxEntry entry) {
        validateCreate(entry);
        withConnection(dataSource, "create Worker callback inbox entry", connection -> {
            validatePayloadArtifact(connection, entry);
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                bindInsert(statement, entry);
                statement.executeUpdate();
                return null;
            }
        });
    }

    private static void validatePayloadArtifact(Connection connection, WorkerCallbackInboxEntry entry)
            throws SQLException {
        String storedHash;
        long declaredSize;
        try (PreparedStatement statement = connection.prepareStatement(FIND_PAYLOAD_ARTIFACT)) {
            statement.setString(1, entry.tenantId().value());
            statement.setString(2, entry.payloadArtifactRef().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("WORKER_CALLBACK_PAYLOAD_ARTIFACT_UNRESOLVED");
                }
                storedHash = resultSet.getString("content_hash");
                String mediaType = resultSet.getString("media_type");
                declaredSize = resultSet.getLong("content_size");
                if (!entry.payloadHash().sha256().equals(storedHash)
                        || declaredSize < 0
                        || declaredSize > MAX_CALLBACK_PAYLOAD_BYTES
                        || !CALLBACK_MEDIA_TYPE.equals(mediaType)) {
                    throw new IllegalArgumentException("WORKER_CALLBACK_PAYLOAD_ARTIFACT_MISMATCH");
                }
            }
        }

        int exactEncodedLength = Math.toIntExact(((declaredSize + 2L) / 3L) * 4L);
        try (PreparedStatement statement = connection.prepareStatement(FIND_BOUNDED_PAYLOAD_CONTENT)) {
            statement.setString(1, entry.tenantId().value());
            statement.setString(2, entry.payloadArtifactRef().value());
            statement.setString(3, storedHash);
            statement.setString(4, CALLBACK_MEDIA_TYPE);
            statement.setLong(5, declaredSize);
            statement.setInt(6, exactEncodedLength);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("WORKER_CALLBACK_PAYLOAD_ARTIFACT_MISMATCH");
                }
                byte[] content;
                try {
                    content = Base64.getDecoder().decode(resultSet.getString("content_base64"));
                } catch (IllegalArgumentException invalidBase64) {
                    throw new IllegalArgumentException(
                            "WORKER_CALLBACK_PAYLOAD_ARTIFACT_CORRUPT", invalidBase64);
                }
                if (content.length != declaredSize || !storedHash.equals(sha256(content))) {
                    throw new IllegalArgumentException("WORKER_CALLBACK_PAYLOAD_ARTIFACT_MISMATCH");
                }
            }
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    @Override
    public Optional<WorkerCallbackInboxEntry> find(TenantId tenantId, String callbackId) {
        Objects.requireNonNull(tenantId, "tenantId");
        callbackId = requireText(callbackId, "callbackId");
        String exactCallbackId = callbackId;
        return withConnection(dataSource, "find Worker callback inbox entry", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, exactCallbackId);
                return findOne(statement);
            }
        });
    }

    @Override
    public Optional<WorkerCallbackInboxEntry> findByEvent(
            TenantId tenantId, String workerBindingId, String eventId) {
        Objects.requireNonNull(tenantId, "tenantId");
        workerBindingId = requireText(workerBindingId, "workerBindingId");
        eventId = requireText(eventId, "eventId");
        String exactBinding = workerBindingId;
        String exactEvent = eventId;
        return withConnection(dataSource, "find Worker callback event", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_EVENT)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, exactBinding);
                statement.setString(3, exactEvent);
                return findOne(statement);
            }
        });
    }

    @Override
    public Optional<WorkerCallbackInboxEntry> claimNext(
            Instant now, Duration lease, String claimToken) {
        validateClaimArguments(now, lease, claimToken);
        return withConnection(dataSource, "claim Worker callback inbox entry", connection -> {
            for (int attempt = 0; attempt < MAX_CLAIM_CAS_ATTEMPTS; attempt++) {
                Optional<WorkerCallbackInboxEntry> candidate = findCandidate(connection, FIND_DUE, now);
                if (candidate.isEmpty()) return Optional.empty();
                WorkerCallbackInboxEntry observed = candidate.orElseThrow();
                WorkerCallbackInboxEntry claimed = observed.claim(claimToken, now, lease);
                if (compareAndSet(connection, observed, claimed)) return Optional.of(claimed);
            }
            return Optional.empty();
        });
    }

    @Override
    public Optional<WorkerCallbackInboxEntry> claimExpired(
            Instant now, Duration lease, String claimToken) {
        validateClaimArguments(now, lease, claimToken);
        return withConnection(dataSource, "reclaim expired Worker callback inbox entry", connection -> {
            for (int attempt = 0; attempt < MAX_CLAIM_CAS_ATTEMPTS; attempt++) {
                Optional<WorkerCallbackInboxEntry> candidate = findCandidate(connection, FIND_EXPIRED, now);
                if (candidate.isEmpty()) return Optional.empty();
                WorkerCallbackInboxEntry observed = candidate.orElseThrow();
                WorkerCallbackInboxEntry claimed = observed.claim(claimToken, now, lease);
                if (compareAndSet(connection, observed, claimed)) return Optional.of(claimed);
            }
            return Optional.empty();
        });
    }

    @Override
    public boolean compareAndSet(WorkerCallbackInboxEntry expected, WorkerCallbackInboxEntry next) {
        validateCas(expected, next);
        return withConnection(dataSource, "CAS Worker callback inbox entry", connection ->
                compareAndSet(connection, expected, next));
    }

    private static boolean compareAndSet(
            Connection connection, WorkerCallbackInboxEntry expected, WorkerCallbackInboxEntry next)
            throws SQLException {
        validateCas(expected, next);
        try (PreparedStatement statement = connection.prepareStatement(CAS)) {
            int index = 1;
            statement.setString(index++, next.status().name());
            setInstant(statement, index++, next.availableAt());
            setOptionalText(statement, index++, next.claimToken());
            setOptionalInstant(statement, index++, next.leaseUntil());
            statement.setInt(index++, next.deliveryCount());
            setOptionalText(statement, index++, next.lastCode());
            statement.setLong(index++, next.version());
            setInstant(statement, index++, next.updatedAt());
            statement.setString(index++, expected.tenantId().value());
            statement.setString(index++, expected.callbackId());
            statement.setString(index++, expected.workerBindingId());
            statement.setString(index++, expected.eventId());
            statement.setString(index++, expected.workOrderId().value());
            statement.setString(index++, expected.workerRunId().value());
            statement.setString(index++, expected.operationId());
            statement.setString(index++, expected.attemptTokenHash().sha256());
            statement.setString(index++, expected.payloadArtifactRef().value());
            statement.setString(index++, expected.payloadHash().sha256());
            statement.setString(index++, expected.status().name());
            statement.setLong(index, expected.version());
            return statement.executeUpdate() == 1;
        }
    }

    private static Optional<WorkerCallbackInboxEntry> findCandidate(
            Connection connection, String sql, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setInstant(statement, 1, now);
            return findOne(statement);
        }
    }

    private static Optional<WorkerCallbackInboxEntry> findOne(PreparedStatement statement)
            throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
        }
    }

    private static void validateCreate(WorkerCallbackInboxEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.status() != WorkerCallbackInboxStatus.RECEIVED
                || entry.version() != 0
                || entry.deliveryCount() != 1
                || entry.claimToken().isPresent()
                || entry.leaseUntil().isPresent()
                || entry.lastCode().isPresent()) {
            throw new IllegalArgumentException("callback inbox entry must be created as pristine RECEIVED");
        }
    }

    private static void validateCas(WorkerCallbackInboxEntry expected, WorkerCallbackInboxEntry next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        requireCas(expected.version(), next.version());
        requireSame(expected.callbackId().equals(next.callbackId()), "callbackId");
        requireSame(expected.tenantId().equals(next.tenantId()), "tenantId");
        requireSame(expected.workerBindingId().equals(next.workerBindingId()), "workerBindingId");
        requireSame(expected.eventId().equals(next.eventId()), "eventId");
        requireSame(expected.workOrderId().equals(next.workOrderId()), "workOrderId");
        requireSame(expected.workerRunId().equals(next.workerRunId()), "workerRunId");
        requireSame(expected.operationId().equals(next.operationId()), "operationId");
        requireSame(expected.attemptTokenHash().equals(next.attemptTokenHash()), "attemptTokenHash");
        requireSame(expected.payloadArtifactRef().equals(next.payloadArtifactRef()), "payloadArtifactRef");
        requireSame(expected.payloadHash().equals(next.payloadHash()), "payloadHash");
        requireSame(expected.receivedAt().equals(next.receivedAt()), "receivedAt");
        requireSame(expected.deliveryCount() == next.deliveryCount(), "deliveryCount");
        if (expected.status().isTerminal()) {
            throw new IllegalArgumentException("terminal callback inbox entry must not be overwritten");
        }
        if (next.updatedAt().isBefore(expected.updatedAt())) {
            throw new IllegalArgumentException("callback inbox time must not move backwards");
        }
        if (expected.status() == WorkerCallbackInboxStatus.RECEIVED) {
            if (next.status() != WorkerCallbackInboxStatus.PROCESSING
                    || next.claimToken().isEmpty()
                    || next.leaseUntil().isEmpty()
                    || next.updatedAt().isBefore(expected.availableAt())) {
                throw new IllegalArgumentException("RECEIVED callback may transition only to PROCESSING");
            }
            return;
        }
        if (next.status() == WorkerCallbackInboxStatus.PROCESSING) {
            if (next.claimToken().isEmpty()
                    || next.leaseUntil().isEmpty()
                    || next.updatedAt().isBefore(expected.leaseUntil().orElseThrow())) {
                throw new IllegalArgumentException("active callback may be reclaimed only after lease expiry");
            }
            return;
        }
        if (next.status() == WorkerCallbackInboxStatus.RECEIVED) {
            if (next.claimToken().isPresent()
                    || next.leaseUntil().isPresent()
                    || next.lastCode().isEmpty()
                    || next.availableAt().isBefore(next.updatedAt())) {
                throw new IllegalArgumentException("deferred callback requires a stable retry schedule");
            }
            return;
        }
        if (!next.status().isTerminal()
                || next.claimToken().isPresent()
                || next.leaseUntil().isPresent()
                || next.lastCode().isEmpty()) {
            throw new IllegalArgumentException("illegal PROCESSING callback transition");
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

    private static void bindInsert(PreparedStatement statement, WorkerCallbackInboxEntry entry)
            throws SQLException {
        int index = 1;
        statement.setString(index++, entry.callbackId());
        statement.setString(index++, entry.tenantId().value());
        statement.setString(index++, entry.workerBindingId());
        statement.setString(index++, entry.eventId());
        statement.setString(index++, entry.workOrderId().value());
        statement.setString(index++, entry.workerRunId().value());
        statement.setString(index++, entry.operationId());
        statement.setString(index++, entry.attemptTokenHash().sha256());
        statement.setString(index++, entry.payloadArtifactRef().value());
        statement.setString(index++, entry.payloadHash().sha256());
        statement.setString(index++, entry.status().name());
        setInstant(statement, index++, entry.availableAt());
        setOptionalText(statement, index++, entry.claimToken());
        setOptionalInstant(statement, index++, entry.leaseUntil());
        statement.setInt(index++, entry.deliveryCount());
        setOptionalText(statement, index++, entry.lastCode());
        statement.setLong(index++, entry.version());
        setInstant(statement, index++, entry.receivedAt());
        setInstant(statement, index, entry.updatedAt());
    }

    private static WorkerCallbackInboxEntry map(ResultSet resultSet) throws SQLException {
        return new WorkerCallbackInboxEntry(
                resultSet.getString("callback_id"),
                new TenantId(resultSet.getString("tenant_id")),
                resultSet.getString("worker_binding_id"),
                resultSet.getString("event_id"),
                new WorkOrderId(resultSet.getString("work_order_id")),
                new WorkerRunId(resultSet.getString("worker_run_id")),
                resultSet.getString("operation_id"),
                new ContentHash(resultSet.getString("attempt_token_hash")),
                new ArtifactReference(resultSet.getString("payload_artifact_ref")),
                new ContentHash(resultSet.getString("payload_hash")),
                WorkerCallbackInboxStatus.valueOf(resultSet.getString("status")),
                getInstant(resultSet, "available_at"),
                getOptionalText(resultSet, "claim_token"),
                getOptionalInstant(resultSet, "lease_until"),
                resultSet.getInt("delivery_count"),
                getOptionalText(resultSet, "last_code"),
                resultSet.getLong("version"),
                getInstant(resultSet, "received_at"),
                getInstant(resultSet, "updated_at"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
