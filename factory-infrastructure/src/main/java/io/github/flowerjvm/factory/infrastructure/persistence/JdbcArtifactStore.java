package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC storage for immutable artifacts.
 *
 * <p>Artifact references are opaque database keys. They are never interpreted as filesystem paths.
 */
public final class JdbcArtifactStore implements ArtifactStore {
    public static final int MAX_ARTIFACT_BYTES = 32 * 1024 * 1024;
    private static final String INSERT = """
            INSERT INTO factory_artifact (
                artifact_ref, tenant_id, content_hash, media_type,
                content_size, content_base64, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND = """
            SELECT artifact_ref, tenant_id, content_hash, media_type, content_size, content_base64
            FROM factory_artifact
            WHERE tenant_id = ? AND artifact_ref = ?
            """;

    private final DataSource dataSource;
    private final Clock clock;

    public JdbcArtifactStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public JdbcArtifactStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ArtifactReference store(Artifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        byte[] content = artifact.content();
        if (content.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("artifact exceeds the global immutable-store bound");
        }
        ContentHash actualHash = sha256(content);
        if (!actualHash.equals(artifact.contentHash())) {
            throw new IllegalArgumentException("artifact content does not match its declared SHA-256 hash");
        }

        try {
            withConnection(dataSource, "store immutable Artifact", connection -> {
                try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                    statement.setString(1, artifact.reference().value());
                    statement.setString(2, artifact.tenantId().value());
                    statement.setString(3, artifact.contentHash().sha256());
                    statement.setString(4, artifact.mediaType());
                    statement.setLong(5, content.length);
                    statement.setString(6, Base64.getEncoder().encodeToString(content));
                    setInstant(statement, 7, clock.instant());
                    statement.executeUpdate();
                    return null;
                }
            });
            return artifact.reference();
        } catch (DuplicateLedgerRecordException duplicate) {
            Optional<Artifact> existing = find(artifact.tenantId(), artifact.reference());
            if (existing.filter(value -> exactlyMatches(value, artifact)).isPresent()) {
                return artifact.reference();
            }
            throw new FactoryPersistenceException(
                    "artifact reference already identifies different immutable content", duplicate);
        }
    }

    @Override
    public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(reference, "reference");
        return withConnection(dataSource, "find immutable Artifact", connection ->
                find(connection, tenantId, reference));
    }

    /** Same-connection immutable insert. A savepoint preserves PostgreSQL transaction health on a concurrent insert. */
    void store(Connection connection, Artifact artifact) throws SQLException {
        storeIfAbsent(connection, artifact);
    }

    /** Returns true only for this transaction's insert; false means an exact committed artifact won. */
    boolean storeIfAbsent(Connection connection, Artifact artifact) throws SQLException {
        byte[] content = artifact.content();
        if (content.length > MAX_ARTIFACT_BYTES || !sha256(content).equals(artifact.contentHash())) {
            throw new IllegalArgumentException("artifact content exceeds its bound or differs from its SHA-256");
        }
        Optional<Artifact> existing = find(connection, artifact.tenantId(), artifact.reference());
        if (existing.isPresent()) {
            if (!exactlyMatches(existing.orElseThrow(), artifact)) {
                throw corrupt("artifact reference identifies different immutable content", null);
            }
            return false;
        }
        var savepoint = connection.setSavepoint();
        boolean inserted = true;
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, artifact.reference().value());
            statement.setString(2, artifact.tenantId().value());
            statement.setString(3, artifact.contentHash().sha256());
            statement.setString(4, artifact.mediaType());
            statement.setLong(5, content.length);
            statement.setString(6, Base64.getEncoder().encodeToString(content));
            setInstant(statement, 7, clock.instant());
            statement.executeUpdate();
        } catch (SQLException exception) {
            connection.rollback(savepoint);
            if (!"23505".equals(exception.getSQLState())
                    || find(connection, artifact.tenantId(), artifact.reference())
                            .filter(value -> exactlyMatches(value, artifact)).isEmpty()) {
                throw exception;
            }
            inserted = false;
        } finally {
            connection.releaseSavepoint(savepoint);
        }
        return inserted;
    }

    Optional<Artifact> find(
            java.sql.Connection connection,
            TenantId tenantId,
            ArtifactReference reference) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(reference, "reference");
        try (PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, reference.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapAndVerify(resultSet)) : Optional.empty();
            }
        }
    }

    private static Artifact mapAndVerify(ResultSet resultSet) throws SQLException {
        long declaredSize = resultSet.getLong("content_size");
        if (resultSet.wasNull() || declaredSize < 0 || declaredSize > MAX_ARTIFACT_BYTES) {
            throw corrupt("stored artifact has an invalid content size", null);
        }
        // Check the small numeric column before asking the JDBC driver to materialize large content.
        String encoded = resultSet.getString("content_base64");

        byte[] content;
        try {
            content = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw corrupt("stored artifact content is not valid base64", exception);
        }
        if (content.length != declaredSize) {
            throw corrupt("stored artifact size does not match its content", null);
        }

        ContentHash declaredHash;
        try {
            declaredHash = new ContentHash(resultSet.getString("content_hash"));
        } catch (IllegalArgumentException exception) {
            throw corrupt("stored artifact has an invalid content hash", exception);
        }
        if (!declaredHash.equals(sha256(content))) {
            throw corrupt("stored artifact content does not match its hash", null);
        }

        return new Artifact(
                new TenantId(resultSet.getString("tenant_id")),
                new ArtifactReference(resultSet.getString("artifact_ref")),
                declaredHash,
                resultSet.getString("media_type"),
                content);
    }

    private static boolean exactlyMatches(Artifact first, Artifact second) {
        return first.tenantId().equals(second.tenantId())
                && first.reference().equals(second.reference())
                && first.contentHash().equals(second.contentHash())
                && first.mediaType().equals(second.mediaType())
                && Arrays.equals(first.content(), second.content());
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static FactoryPersistenceException corrupt(String message, Throwable cause) {
        return new FactoryPersistenceException(
                message,
                cause == null ? new IllegalStateException("artifact integrity violation") : cause);
    }
}
