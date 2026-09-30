package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class JdbcLedgerOwnershipConstraintsTest {
    @Test
    void sameRequestKeyHasOneActiveBuildSessionAndAllowsReplacementAfterTerminal() throws Exception {
        var dataSource = FactoryDatabaseMigrationsTest.h2("active_build_session");
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcLedgerOwnershipConstraints.assertOneActiveBuildSessionPerRequest(dataSource, "h2");
    }

    @Test
    void workOrderHasOneActiveWorkerAttemptAndAllowsReplacementAfterTerminal() throws Exception {
        var dataSource = FactoryDatabaseMigrationsTest.h2("active_worker_attempt");
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcLedgerOwnershipConstraints.assertOneActiveWorkerAttemptPerWorkOrder(dataSource, "h2");
    }

    @Test
    void workerRunCannotPairAWorkOrderWithAnotherBuildSession() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("worker_session_order_fk");
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcLedgerOwnershipConstraints.assertWorkerRunCannotCrossSessionAndWorkOrder(dataSource, "h2");
    }

    @Test
    void supersededWorkOrderMustExistInTheSameTenant() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("work_order_lineage_fk");
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcLedgerOwnershipConstraints.assertSupersededWorkOrderMustExistInTenant(dataSource, "h2");
    }

    @Test
    void decisionsAndTerminalReferencesAreBoundToThePointAndSubjectHash() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("decision_reference_fk");
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcLedgerOwnershipConstraints.assertDecisionReferencesArePointAndHashBound(dataSource, "h2");
    }

    @Test
    void databaseRejectsTerminalWorkerRunWithoutDispatchOwnership() throws Exception {
        var dataSource = FactoryDatabaseMigrationsTest.h2("terminal_worker_ownership");
        FactoryDatabaseMigrations.migrate(dataSource);
        var sessions = new JdbcBuildSessionRepository(dataSource);
        var orders = new JdbcWorkOrderRepository(dataSource);
        var session = PersistenceFixtures.buildSession("terminal-worker-ownership");
        var order = PersistenceFixtures.workOrder(session, "terminal-worker-ownership");
        sessions.create(session);
        orders.create(order);

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO factory_worker_run (
                          worker_run_id, tenant_id, build_session_id, work_order_id, attempt_no,
                          worker_binding_id, worker_adapter_version, worker_capability_snapshot_json,
                          status, active_owner_key, action_run_id, operation_id, attempt_token_hash,
                          external_session_ref, dispatch_outbox_id, started_at, deadline_at, heartbeat_at,
                          cancel_requested_at, completed_at, result_artifact_manifest_ref, result_hash,
                          code, message, retry_disposition, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 1, 'fake', '1', '[]', 'FAILED', NULL, NULL, ?, NULL,
                          NULL, NULL, NULL, ?, NULL, NULL, ?, NULL, NULL, 'FAILED', 'failed', 'NEVER', 0, ?, ?)
                        """)) {
            statement.setString(1, "malformed-terminal-worker");
            statement.setString(2, session.tenantId().value());
            statement.setString(3, session.buildSessionId().value());
            statement.setString(4, order.workOrderId().value());
            statement.setString(5, "malformed-terminal-operation");
            statement.setObject(6, java.time.OffsetDateTime.now().plusHours(1));
            statement.setObject(7, java.time.OffsetDateTime.now());
            statement.setObject(8, java.time.OffsetDateTime.now().minusMinutes(1));
            statement.setObject(9, java.time.OffsetDateTime.now());

            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }
}
