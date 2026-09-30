package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class JdbcArtifactStoreTest {
    private static final TenantId TENANT = new TenantId("tenant-artifact");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void storesOpaqueReferenceAndRoundTripsOnlyWithinTenant() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("artifact_roundtrip");
        FactoryDatabaseMigrations.migrate(dataSource);
        var store = new JdbcArtifactStore(dataSource, CLOCK);
        byte[] content = "immutable evidence".getBytes(StandardCharsets.UTF_8);
        var artifact = artifact(TENANT, "../../opaque:not-a-path", "application/json", content);

        assertEquals(artifact.reference(), store.store(artifact));
        assertEquals(artifact.reference(), store.store(artifact));

        Artifact stored = store.find(TENANT, artifact.reference()).orElseThrow();
        assertEquals(artifact.tenantId(), stored.tenantId());
        assertEquals(artifact.reference(), stored.reference());
        assertEquals(artifact.contentHash(), stored.contentHash());
        assertEquals(artifact.mediaType(), stored.mediaType());
        assertArrayEquals(content, stored.content());
        assertTrue(store.find(new TenantId("tenant-other"), artifact.reference()).isEmpty());
    }

    @Test
    void rejectsWrongDeclaredHashAndSameTenantImmutableReferenceConflicts() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("artifact_conflict");
        FactoryDatabaseMigrations.migrate(dataSource);
        var store = new JdbcArtifactStore(dataSource, CLOCK);
        var reference = new ArtifactReference("artifact:global-reference");
        byte[] original = "first".getBytes(StandardCharsets.UTF_8);
        Artifact first = artifact(TENANT, reference.value(), "text/plain", original);

        assertThrows(
                IllegalArgumentException.class,
                () -> store.store(new Artifact(
                        TENANT,
                        new ArtifactReference("artifact:wrong-hash"),
                        new ContentHash("0".repeat(64)),
                        "text/plain",
                        original)));
        store.store(first);

        Artifact differentContent = artifact(
                TENANT,
                reference.value(),
                "text/plain",
                "second".getBytes(StandardCharsets.UTF_8));
        assertThrows(FactoryPersistenceException.class, () -> store.store(differentContent));

        Artifact otherTenant = artifact(
                new TenantId("tenant-other"),
                reference.value(),
                "application/octet-stream",
                "tenant-private".getBytes(StandardCharsets.UTF_8));
        assertEquals(reference, store.store(otherTenant));
        assertArrayEquals(
                otherTenant.content(),
                store.find(otherTenant.tenantId(), reference).orElseThrow().content());
        assertArrayEquals(first.content(), store.find(TENANT, reference).orElseThrow().content());
    }

    @Test
    void verifiesBase64SizeAndHashAgainWheneverStoredContentIsRead() throws Exception {
        var dataSource = FactoryDatabaseMigrationsTest.h2("artifact_read_integrity");
        FactoryDatabaseMigrations.migrate(dataSource);
        var store = new JdbcArtifactStore(dataSource, CLOCK);
        Artifact invalidBase64 = artifact(
                TENANT,
                "artifact:tamper-base64",
                "application/octet-stream",
                new byte[] {1, 2, 3, 4});
        Artifact wrongSize = artifact(
                TENANT,
                "artifact:tamper-size",
                "application/octet-stream",
                new byte[] {5, 6, 7, 8});
        Artifact wrongHash = artifact(
                TENANT,
                "artifact:tamper-hash",
                "application/octet-stream",
                new byte[] {9, 10, 11, 12});
        store.store(invalidBase64);
        store.store(wrongSize);
        store.store(wrongHash);

        update(
                dataSource,
                "UPDATE factory_artifact SET content_base64 = ? WHERE tenant_id = ? AND artifact_ref = ?",
                "not valid base64!",
                invalidBase64);
        update(
                dataSource,
                "UPDATE factory_artifact SET content_size = ? WHERE tenant_id = ? AND artifact_ref = ?",
                99L,
                wrongSize);
        update(
                dataSource,
                "UPDATE factory_artifact SET content_hash = ? WHERE tenant_id = ? AND artifact_ref = ?",
                "f".repeat(64),
                wrongHash);

        assertThrows(
                FactoryPersistenceException.class,
                () -> store.find(TENANT, invalidBase64.reference()));
        assertThrows(
                FactoryPersistenceException.class,
                () -> store.find(TENANT, wrongSize.reference()));
        assertThrows(
                FactoryPersistenceException.class,
                () -> store.find(TENANT, wrongHash.reference()));
    }

    private static void update(
            org.h2.jdbcx.JdbcDataSource dataSource,
            String sql,
            Object value,
            Artifact artifact) throws Exception {
        try (var connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            statement.setString(2, artifact.tenantId().value());
            statement.setString(3, artifact.reference().value());
            statement.executeUpdate();
        }
    }

    private static Artifact artifact(
            TenantId tenantId,
            String reference,
            String mediaType,
            byte[] content) {
        return new Artifact(
                tenantId,
                new ArtifactReference(reference),
                hash(content),
                mediaType,
                content);
    }

    private static ContentHash hash(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
