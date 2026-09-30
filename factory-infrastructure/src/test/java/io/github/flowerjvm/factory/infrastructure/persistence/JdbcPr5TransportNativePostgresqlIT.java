package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Native PostgreSQL coverage for the additive PR5 transport migration. */
@Testcontainers
class JdbcPr5TransportNativePostgresqlIT {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void v9MigratesAndScopesOperationUniquenessByCommandType() throws Exception {
        PGSimpleDataSource dataSource = isolatedDataSource("factory_pr5_transport");
        FactoryDatabaseMigrations.migrate(dataSource);

        assertTrue(hasColumn(dataSource, "factory_dispatch_outbox", "claim_purpose"));
        assertTrue(hasTable(dataSource, "factory_worker_callback_inbox"));
        assertTrue(hasTable(dataSource, "factory_worker_callback_audit"));

        insertPending(dataSource, "dispatch-outbox", "WORKER_DISPATCH", "shared-operation");
        insertPending(dataSource, "cancel-outbox", "WORKER_CANCEL", "shared-operation");
        assertEquals(2, countOutboxRows(dataSource));

        assertThrows(
                SQLException.class,
                () -> insertPending(dataSource, "duplicate-dispatch", "WORKER_DISPATCH", "shared-operation"));
        assertThrows(SQLException.class, () -> insertInvalidUnclaimedDispatching(dataSource));
    }

    @Test
    void v9RejectsLegacyDispatchingBeforeAnySchemaMutationOnNativePostgresql() throws Exception {
        PGSimpleDataSource dataSource = isolatedDataSource("factory_pr5_legacy_dispatching");
        FactoryDatabaseMigrationsTest.migrateToV8(dataSource);
        insertLegacyDispatching(dataSource);

        FlywayException failure = assertThrows(
                FlywayException.class, () -> FactoryDatabaseMigrations.migrate(dataSource));

        assertTrue(FactoryDatabaseMigrationsTest.causeChainContains(
                failure, FactoryDatabaseMigrationsTest.V9_LEGACY_DISPATCHING_REJECTION));
        assertFalse(hasColumn(dataSource, "factory_dispatch_outbox", "claim_token"));
    }

    private static void insertPending(
            PGSimpleDataSource dataSource, String outboxId, String operationType, String operationId)
            throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO factory_dispatch_outbox (
                            outbox_id, tenant_id, operation_type, aggregate_type, aggregate_id,
                            operation_id, payload_artifact_ref, status, available_at, attempt_count,
                            last_code, version, created_at, updated_at,
                            claim_token, claim_purpose, claimed_at, lease_until
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            int index = 1;
            statement.setString(index++, outboxId);
            statement.setString(index++, "tenant-pr5");
            statement.setString(index++, operationType);
            statement.setString(index++, "WORKER_RUN");
            statement.setString(index++, "worker-pr5");
            statement.setString(index++, operationId);
            statement.setString(index++, "artifact:pr5-payload");
            statement.setString(index++, "PENDING");
            statement.setTimestamp(index++, Timestamp.from(NOW));
            statement.setInt(index++, 0);
            statement.setString(index++, null);
            statement.setLong(index++, 0);
            statement.setTimestamp(index++, Timestamp.from(NOW));
            statement.setTimestamp(index++, Timestamp.from(NOW));
            statement.setString(index++, null);
            statement.setString(index++, null);
            statement.setTimestamp(index++, null);
            statement.setTimestamp(index, null);
            statement.executeUpdate();
        }
    }

    private static void insertInvalidUnclaimedDispatching(PGSimpleDataSource dataSource)
            throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO factory_dispatch_outbox (
                            outbox_id, tenant_id, operation_type, aggregate_type, aggregate_id,
                            operation_id, payload_artifact_ref, status, available_at, attempt_count,
                            last_code, version, created_at, updated_at,
                            claim_token, claim_purpose, claimed_at, lease_until
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            int index = 1;
            statement.setString(index++, "invalid-unclaimed-dispatching");
            statement.setString(index++, "tenant-pr5");
            statement.setString(index++, "WORKER_DISPATCH");
            statement.setString(index++, "WORKER_RUN");
            statement.setString(index++, "worker-invalid");
            statement.setString(index++, "operation-invalid");
            statement.setString(index++, "artifact:pr5-invalid");
            statement.setString(index++, "DISPATCHING");
            statement.setTimestamp(index++, Timestamp.from(NOW));
            statement.setInt(index++, 1);
            statement.setString(index++, "DISPATCH_STARTED");
            statement.setLong(index++, 1);
            statement.setTimestamp(index++, Timestamp.from(NOW));
            statement.setTimestamp(index++, Timestamp.from(NOW));
            statement.setString(index++, null);
            statement.setString(index++, null);
            statement.setTimestamp(index++, null);
            statement.setTimestamp(index, null);
            statement.executeUpdate();
        }
    }

    private static void insertLegacyDispatching(PGSimpleDataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO factory_dispatch_outbox (
                            outbox_id, tenant_id, operation_type, aggregate_type, aggregate_id,
                            operation_id, payload_artifact_ref, status, available_at, attempt_count,
                            last_code, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            int index = 1;
            statement.setString(index++, "legacy-native-v9");
            statement.setString(index++, "tenant-native-v9");
            statement.setString(index++, "WORKER_DISPATCH");
            statement.setString(index++, "WORKER_RUN");
            statement.setString(index++, "worker-native-v9");
            statement.setString(index++, "operation-native-v9");
            statement.setString(index++, "artifact:native-v9");
            statement.setString(index++, "DISPATCHING");
            statement.setTimestamp(index++, Timestamp.from(NOW));
            statement.setInt(index++, 1);
            statement.setString(index++, "LEGACY_DISPATCH_STARTED");
            statement.setLong(index++, 1);
            statement.setTimestamp(index++, Timestamp.from(NOW));
            statement.setTimestamp(index, Timestamp.from(NOW));
            statement.executeUpdate();
        }
    }

    private static int countOutboxRows(PGSimpleDataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT COUNT(*) FROM factory_dispatch_outbox");
                var resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static boolean hasTable(PGSimpleDataSource dataSource, String table) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = current_schema() AND table_name = ?
                        """)) {
            statement.setString(1, table);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getInt(1) == 1;
            }
        }
    }

    private static boolean hasColumn(
            PGSimpleDataSource dataSource, String table, String column) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getInt(1) == 1;
            }
        }
    }

    private static PGSimpleDataSource isolatedDataSource(String schema) throws SQLException {
        PGSimpleDataSource administrator = new PGSimpleDataSource();
        administrator.setURL(POSTGRES.getJdbcUrl());
        administrator.setUser(POSTGRES.getUsername());
        administrator.setPassword(POSTGRES.getPassword());
        try (var connection = administrator.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        PGSimpleDataSource isolated = new PGSimpleDataSource();
        isolated.setURL(POSTGRES.getJdbcUrl());
        isolated.setUser(POSTGRES.getUsername());
        isolated.setPassword(POSTGRES.getPassword());
        isolated.setCurrentSchema(schema);
        return isolated;
    }
}
