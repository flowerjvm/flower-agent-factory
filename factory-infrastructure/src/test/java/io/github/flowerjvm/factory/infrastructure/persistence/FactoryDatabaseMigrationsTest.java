package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.flow.FlowPersistence;
import io.github.flowerjvm.flower.core.flow.FlowState;
import io.github.flowerjvm.flower.core.persistence.FlowCheckpoint;
import io.github.flowerjvm.flower.persistence.jdbc.JdbcCheckpointDialects;
import io.github.flowerjvm.flower.persistence.jdbc.JdbcFlowCheckpointStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class FactoryDatabaseMigrationsTest {
    static final String V7_DUPLICATE_ACTIVE_REJECTION =
            "V7_DUPLICATE_ACTIVE_VERIFICATION_RUNS_REQUIRE_OPERATOR_RESOLUTION";
    static final String V9_LEGACY_DISPATCHING_REJECTION =
            "V9_LEGACY_DISPATCHING_OUTBOX_REQUIRES_OPERATOR_RECONCILIATION";
    static final String V11_ORPHAN_CURRENT_CERTIFICATION_REJECTION =
            "V11_ORPHAN_CURRENT_CERTIFICATION_REQUIRES_OPERATOR_RESOLUTION";

    @Test
    void appliesFactoryAndPinnedUpstreamSchemasAndIsRepeatable() throws Exception {
        JdbcDataSource dataSource = h2("migration");

        MigrateResult first = FactoryDatabaseMigrations.migrate(dataSource);
        MigrateResult second = FactoryDatabaseMigrations.migrate(dataSource);

        assertEquals(16, first.migrationsExecuted);
        assertEquals(0, second.migrationsExecuted);
        try (Connection connection = dataSource.getConnection()) {
            Set<String> tables = new HashSet<>();
            try (ResultSet resultSet = connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString("TABLE_NAME").toLowerCase());
                }
            }
            assertTrue(tables.containsAll(Set.of(
                    "factory_build_session",
                    "factory_work_order",
                    "factory_worker_run",
                    "factory_worker_callback_inbox",
                    "factory_worker_callback_audit",
                    "factory_verification_dispatch_intent",
                    "factory_decision_point",
                    "factory_decision",
                    "factory_dispatch_outbox",
                    "factory_candidate_version",
                    "factory_verification_run",
                    "factory_artifact",
                    "factory_certification",
                    "factory_certification_dispatch_intent",
                    "factory_reference_assembly",
                    "factory_reference_assembly_release_intent",
                    "factory_incident_application",
                    "factory_incident_application_intent",
                    "flower_flow_checkpoint",
                    "action_run",
                    "action_duplicate",
                    "action_audit")));
            Set<String> checkpointColumns = new HashSet<>();
            try (ResultSet resultSet = connection.getMetaData().getColumns(
                    null, null, "FLOWER_FLOW_CHECKPOINT", "%")) {
                while (resultSet.next()) {
                    checkpointColumns.add(resultSet.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertTrue(checkpointColumns.containsAll(Set.of(
                    "tenant_id",
                    "user_id",
                    "session_id",
                    "run_id",
                    "trace_id",
                    "correlation_id")));

            try (ResultSet resultSet = connection.getMetaData().getColumns(
                    null, null, "FACTORY_BUILD_SESSION", "PRODUCT_LINE_ID")) {
                assertTrue(resultSet.next());
                assertEquals("NO", resultSet.getString("IS_NULLABLE"));
                assertEquals(null, resultSet.getString("COLUMN_DEF"));
            }

            Set<String> verificationColumns = new HashSet<>();
            try (ResultSet resultSet = connection.getMetaData().getColumns(
                    null, null, "FACTORY_VERIFICATION_RUN", "%")) {
                while (resultSet.next()) {
                    verificationColumns.add(resultSet.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertTrue(verificationColumns.containsAll(Set.of(
                    "result_manifest_hash",
                    "terminal_code",
                    "disposition",
                    "active_key")));

            Set<String> dispatchOutboxColumns = new HashSet<>();
            try (ResultSet resultSet = connection.getMetaData().getColumns(
                    null, null, "FACTORY_DISPATCH_OUTBOX", "%")) {
                while (resultSet.next()) {
                    dispatchOutboxColumns.add(resultSet.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertTrue(dispatchOutboxColumns.containsAll(Set.of(
                    "claim_token", "claim_purpose", "claimed_at", "lease_until")));
        }
    }

    @Test
    void v15BindsApplicationToExactComponentAndSeparateWholeProductArtifacts() throws Exception {
        JdbcDataSource dataSource = h2("migration_v15_application_constraints");
        FactoryDatabaseMigrations.migrate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(List.of("tenant_id", "request_key"), constraintColumns(connection,
                    "FACTORY_INCIDENT_APPLICATION", "UQ_INCIDENT_APPLICATION_REQUEST"));
            assertEquals(List.of("tenant_id", "build_session_id", "stage", "subject_version"),
                    constraintColumns(connection, "FACTORY_INCIDENT_APPLICATION_INTENT", "UQ_INCIDENT_APPLICATION_STAGE"));
            assertEquals(List.of("action_run_id"), constraintColumns(connection,
                    "FACTORY_INCIDENT_APPLICATION_INTENT", "UQ_INCIDENT_APPLICATION_ACTION"));
            assertForeignKey(connection, "FACTORY_INCIDENT_APPLICATION", "FK_INCIDENT_APPLICATION_COMPONENT",
                    "FACTORY_CERTIFICATION",
                    List.of("tenant_id", "component_certification_id", "component_candidate_hash", "component_manifest_ref", "component_manifest_hash"),
                    List.of("tenant_id", "certification_id", "candidate_hash", "certification_manifest_ref", "certification_manifest_hash"));
            assertForeignKey(connection, "FACTORY_INCIDENT_APPLICATION", "FK_INCIDENT_APPLICATION_CERTIFICATE", "FACTORY_ARTIFACT",
                    List.of("tenant_id", "certification_ref", "certification_hash"), List.of("tenant_id", "artifact_ref", "content_hash"));
            assertForeignKey(connection, "FACTORY_INCIDENT_APPLICATION", "FK_INCIDENT_APPLICATION_RELEASE", "FACTORY_ARTIFACT",
                    List.of("tenant_id", "release_ref", "release_hash"), List.of("tenant_id", "artifact_ref", "content_hash"));
            assertForeignKey(connection, "FACTORY_INCIDENT_APPLICATION_INTENT", "FK_INCIDENT_APPLICATION_INTENT_PRODUCT", "FACTORY_INCIDENT_APPLICATION",
                    List.of("tenant_id", "build_session_id"), List.of("tenant_id", "build_session_id"));
            assertForeignKey(connection, "FACTORY_INCIDENT_APPLICATION_INTENT", "FK_INCIDENT_APPLICATION_INTENT_ACTION", "ACTION_RUN",
                    List.of("tenant_id", "action_run_id"), List.of("tenant_id", "run_id"));
        }
    }

    @Test
    void v14BindsReferenceAssemblyReleaseIntentToTenantScopedAssemblyAndActionOwner()
            throws Exception {
        JdbcDataSource dataSource = h2("migration_v14_reference_release_intent_constraints");
        FactoryDatabaseMigrations.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(
                    List.of("action_run_id"),
                    constraintColumns(
                            connection,
                            "FACTORY_REFERENCE_ASSEMBLY_RELEASE_INTENT",
                            "UQ_FACTORY_REFERENCE_RELEASE_INTENT_ACTION_RUN"));
            assertEquals(
                    List.of(
                            "tenant_id",
                            "reference_assembly_id",
                            "expected_reference_assembly_version"),
                    constraintColumns(
                            connection,
                            "FACTORY_REFERENCE_ASSEMBLY_RELEASE_INTENT",
                            "UQ_FACTORY_REFERENCE_RELEASE_INTENT_ATTEMPT"));
            assertForeignKey(
                    connection,
                    "FACTORY_REFERENCE_ASSEMBLY_RELEASE_INTENT",
                    "FK_FACTORY_REFERENCE_RELEASE_INTENT_ASSEMBLY",
                    "FACTORY_REFERENCE_ASSEMBLY",
                    List.of("tenant_id", "reference_assembly_id"),
                    List.of("tenant_id", "reference_assembly_id"));
            assertForeignKey(
                    connection,
                    "FACTORY_REFERENCE_ASSEMBLY_RELEASE_INTENT",
                    "FK_FACTORY_REFERENCE_RELEASE_INTENT_ACTION_RUN",
                    "ACTION_RUN",
                    List.of("tenant_id", "action_run_id"),
                    List.of("tenant_id", "run_id"));
            assertEquals(
                    List.of("status", "lease_until", "created_at", "operation_id"),
                    indexColumns(
                            connection,
                            "FACTORY_REFERENCE_ASSEMBLY_RELEASE_INTENT",
                            "IDX_FACTORY_REFERENCE_RELEASE_INTENT_CLAIM"));
            assertEquals(
                    List.of("status", "lease_until", "operation_id"),
                    indexColumns(
                            connection,
                            "FACTORY_REFERENCE_ASSEMBLY_RELEASE_INTENT",
                            "IDX_FACTORY_REFERENCE_RELEASE_INTENT_RECOVERY"));
            assertEquals(
                    List.of(
                            "tenant_id",
                            "reference_assembly_id",
                            "expected_reference_assembly_version",
                            "created_at",
                            "operation_id"),
                    indexColumns(
                            connection,
                            "FACTORY_REFERENCE_ASSEMBLY_RELEASE_INTENT",
                            "IDX_FACTORY_REFERENCE_RELEASE_INTENT_ASSEMBLY"));

            Set<String> columns = new HashSet<>();
            try (ResultSet resultSet = connection.getMetaData().getColumns(
                    null, null, "FACTORY_REFERENCE_ASSEMBLY_RELEASE_INTENT", "%")) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertTrue(columns.containsAll(Set.of(
                    "operation_id",
                    "tenant_id",
                    "reference_assembly_id",
                    "assembly_manifest_hash",
                    "inspection_report_hash",
                    "release_decision_point_id",
                    "release_subject_hash",
                    "expected_reference_assembly_version",
                    "action_run_id",
                    "attempt_token_hash",
                    "deadline_at",
                    "status",
                    "claim_token",
                    "lease_until",
                    "attempt_count",
                    "last_code",
                    "version",
                    "created_at",
                    "updated_at")));
        }
    }

    @Test
    void v12BindsCertificationDispatchToTenantScopedCertificationAndActionOwner()
            throws Exception {
        JdbcDataSource dataSource = h2("migration_v12_certification_dispatch_constraints");
        FactoryDatabaseMigrations.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(
                    List.of("action_run_id"),
                    constraintColumns(
                            connection,
                            "FACTORY_CERTIFICATION_DISPATCH_INTENT",
                            "UQ_FACTORY_CERTIFICATION_DISPATCH_ACTION_RUN"));
            assertEquals(
                    List.of("tenant_id", "certification_id", "expected_certification_version"),
                    constraintColumns(
                            connection,
                            "FACTORY_CERTIFICATION_DISPATCH_INTENT",
                            "UQ_FACTORY_CERTIFICATION_DISPATCH_ATTEMPT"));
            assertForeignKey(
                    connection,
                    "FACTORY_CERTIFICATION_DISPATCH_INTENT",
                    "FK_FACTORY_CERTIFICATION_DISPATCH_CERTIFICATION",
                    "FACTORY_CERTIFICATION",
                    List.of("tenant_id", "certification_id"),
                    List.of("tenant_id", "certification_id"));
            assertForeignKey(
                    connection,
                    "FACTORY_CERTIFICATION_DISPATCH_INTENT",
                    "FK_FACTORY_CERTIFICATION_DISPATCH_ACTION_RUN",
                    "ACTION_RUN",
                    List.of("tenant_id", "action_run_id"),
                    List.of("tenant_id", "run_id"));
        }
    }

    @Test
    void v11BindsCertificationToExactVerificationSessionAndActionTenant() throws Exception {
        JdbcDataSource dataSource = h2("migration_v11_certification_constraints");
        FactoryDatabaseMigrations.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(
                    List.of(
                            "tenant_id",
                            "build_session_id",
                            "verification_run_id",
                            "candidate_id",
                            "candidate_hash",
                            "toolchain_lock_hash",
                            "gate_profile",
                            "fixture_set_hash",
                            "result_manifest_ref",
                            "result_manifest_hash"),
                    constraintColumns(
                            connection,
                            "FACTORY_VERIFICATION_RUN",
                            "UQ_FACTORY_VERIFICATION_RUN_CERTIFICATION_LOCK"));
            assertEquals(
                    List.of("tenant_id", "run_id"),
                    constraintColumns(connection, "ACTION_RUN", "UQ_FACTORY_ACTION_RUN_TENANT_ID"));
            assertEquals(
                    List.of("tenant_id", "build_session_id", "certification_id"),
                    constraintColumns(
                            connection,
                            "FACTORY_CERTIFICATION",
                            "UQ_FACTORY_CERTIFICATION_SESSION_ID"));

            assertForeignKey(
                    connection,
                    "FACTORY_CERTIFICATION",
                    "FK_FACTORY_CERTIFICATION_VERIFICATION",
                    "FACTORY_VERIFICATION_RUN",
                    List.of(
                            "tenant_id",
                            "build_session_id",
                            "verification_run_id",
                            "candidate_id",
                            "candidate_hash",
                            "toolchain_lock_hash",
                            "gate_profile",
                            "verification_fixture_set_hash",
                            "verification_result_manifest_ref",
                            "verification_result_manifest_hash"),
                    List.of(
                            "tenant_id",
                            "build_session_id",
                            "verification_run_id",
                            "candidate_id",
                            "candidate_hash",
                            "toolchain_lock_hash",
                            "gate_profile",
                            "fixture_set_hash",
                            "result_manifest_ref",
                            "result_manifest_hash"));
            assertForeignKey(
                    connection,
                    "FACTORY_CERTIFICATION",
                    "FK_FACTORY_CERTIFICATION_VERIFICATION_ACTION",
                    "ACTION_RUN",
                    List.of("tenant_id", "verification_action_run_id"),
                    List.of("tenant_id", "run_id"));
            assertForeignKey(
                    connection,
                    "FACTORY_CERTIFICATION",
                    "FK_FACTORY_CERTIFICATION_ACTION",
                    "ACTION_RUN",
                    List.of("tenant_id", "action_run_id"),
                    List.of("tenant_id", "run_id"));
            assertForeignKey(
                    connection,
                    "FACTORY_BUILD_SESSION",
                    "FK_FACTORY_BUILD_SESSION_CURRENT_CERTIFICATION",
                    "FACTORY_CERTIFICATION",
                    List.of("tenant_id", "build_session_id", "current_certification_id"),
                    List.of("tenant_id", "build_session_id", "certification_id"));
        }
    }

    @Test
    void v10BackfillsExistingSessionsAsAgentPackAndRequiresExplicitFutureValues() throws Exception {
        JdbcDataSource dataSource = h2("migration_v10_product_line");
        migrateToV9(dataSource);
        Instant now = Instant.parse("2026-09-02T00:00:00Z");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO factory_build_session (
                            build_session_id, tenant_id, project_id, request_idempotency_key,
                            active_request_key, created_by, status, current_phase,
                            requirements_artifact_ref, requirements_hash,
                            selected_manager_worker_binding, selected_coding_worker_binding,
                            current_blueprint_ref, current_candidate_id, current_candidate_hash,
                            current_certification_id, repair_round, max_repair_rounds, started_at,
                            deadline_at, cancellation_requested_at, terminal_code, terminal_message,
                            version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            int index = 1;
            statement.setString(index++, "legacy-v10-session");
            statement.setString(index++, "tenant-v10");
            statement.setString(index++, "project-v10");
            statement.setString(index++, "request-v10");
            statement.setString(index++, "request-v10");
            statement.setString(index++, "principal-v10");
            statement.setString(index++, "RUNNING");
            statement.setString(index++, "understand-customer");
            statement.setString(index++, "artifact:requirements-v10");
            statement.setString(index++, "a".repeat(64));
            statement.setString(index++, null);
            statement.setString(index++, null);
            statement.setString(index++, null);
            statement.setString(index++, null);
            statement.setString(index++, null);
            statement.setString(index++, null);
            statement.setInt(index++, 0);
            statement.setInt(index++, 3);
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setTimestamp(index++, Timestamp.from(now.plusSeconds(300)));
            statement.setTimestamp(index++, null);
            statement.setString(index++, null);
            statement.setString(index++, null);
            statement.setLong(index++, 0);
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setTimestamp(index, Timestamp.from(now));
            statement.executeUpdate();
        }

        assertEquals(7, FactoryDatabaseMigrations.migrate(dataSource).migrationsExecuted);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT product_line_id
                        FROM factory_build_session
                        WHERE build_session_id = 'legacy-v10-session'
                        """);
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            assertEquals("agent-pack", resultSet.getString(1));
        }
        try (Connection connection = dataSource.getConnection();
                ResultSet resultSet = connection.getMetaData().getColumns(
                        null, null, "FACTORY_BUILD_SESSION", "PRODUCT_LINE_ID")) {
            assertTrue(resultSet.next());
            assertEquals("NO", resultSet.getString("IS_NULLABLE"));
            assertEquals(null, resultSet.getString("COLUMN_DEF"));
        }
    }

    @Test
    void v11RejectsOrphanCurrentCertificationBeforeAnySchemaMutation() throws Exception {
        JdbcDataSource dataSource = h2("migration_v11_orphan_current_certification");
        migrateToV10(dataSource);
        BuildSession session = PersistenceFixtures.buildSession("legacy-v11-orphan-certification");
        new JdbcBuildSessionRepository(dataSource).create(session);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE factory_build_session
                        SET current_certification_id = ?
                        WHERE tenant_id = ? AND build_session_id = ?
                        """)) {
            statement.setString(1, "orphan-certification-v11");
            statement.setString(2, session.tenantId().value());
            statement.setString(3, session.buildSessionId().value());
            assertEquals(1, statement.executeUpdate());
        }

        FlywayException failure = assertThrows(
                FlywayException.class, () -> FactoryDatabaseMigrations.migrate(dataSource));

        assertTrue(causeChainContains(failure, V11_ORPHAN_CURRENT_CERTIFICATION_REJECTION));
        try (Connection connection = dataSource.getConnection();
                ResultSet resultSet = connection.getMetaData().getTables(
                        null, null, "FACTORY_CERTIFICATION", new String[] {"TABLE"})) {
            assertFalse(resultSet.next(), "V11 must reject before creating the Certification ledger");
        }
    }

    @Test
    void v9RejectsLegacyDispatchingOutboxBeforeAddingClaimColumns() throws Exception {
        JdbcDataSource dataSource = h2("migration_v9_legacy_dispatching");
        migrateToV8(dataSource);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO factory_dispatch_outbox (
                            outbox_id, tenant_id, operation_type, aggregate_type, aggregate_id,
                            operation_id, payload_artifact_ref, status, available_at, attempt_count,
                            last_code, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            Instant now = Instant.parse("2026-08-20T00:00:00Z");
            int index = 1;
            statement.setString(index++, "legacy-v9-outbox");
            statement.setString(index++, "tenant-v9");
            statement.setString(index++, "WORKER_DISPATCH");
            statement.setString(index++, "WORKER_RUN");
            statement.setString(index++, "worker-v9");
            statement.setString(index++, "operation-v9");
            statement.setString(index++, "artifact:v9-payload");
            statement.setString(index++, "DISPATCHING");
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setInt(index++, 1);
            statement.setString(index++, "LEGACY_DISPATCH_STARTED");
            statement.setLong(index++, 1);
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setTimestamp(index, Timestamp.from(now));
            statement.executeUpdate();
        }

        FlywayException failure = assertThrows(
                FlywayException.class, () -> FactoryDatabaseMigrations.migrate(dataSource));

        assertTrue(causeChainContains(failure, V9_LEGACY_DISPATCHING_REJECTION));
        try (Connection connection = dataSource.getConnection();
                ResultSet resultSet = connection.getMetaData().getColumns(
                        null, null, "FACTORY_DISPATCH_OUTBOX", "CLAIM_TOKEN")) {
            assertFalse(resultSet.next(), "V9 must reject before adding claim metadata");
        }
    }

    @Test
    void pinnedFlowerSchemaRoundTripsEveryExecutionIdentityField() {
        JdbcDataSource dataSource = h2("flower_checkpoint");
        FactoryDatabaseMigrations.migrate(dataSource);
        var store = JdbcFlowCheckpointStore.create(dataSource, JdbcCheckpointDialects.h2());
        var identity = ExecutionContext.builder()
                .tenantId("tenant-a")
                .userId("principal-a")
                .sessionId("build-session-a")
                .runId("flow-run-a")
                .traceId("trace-a")
                .correlationId("project-a")
                .build();
        var checkpoint = new FlowCheckpoint(
                FlowId.of("create-customer-agent", "build-session-a"),
                FlowState.RUNNING,
                "GENERATE_CANDIDATE",
                10,
                true,
                FlowPersistence.DURABLE,
                "factory",
                1_786_473_600_000L,
                "factory-pr3-v1",
                identity);

        store.save(checkpoint);

        FlowCheckpoint stored = store.find(checkpoint.flowId()).orElseThrow();
        assertTrue(stored.sameStoredPositionAs(checkpoint));
        assertEquals(identity, stored.executionContext());
        List<FlowCheckpoint> active = store.findActiveByWorker("factory");
        assertEquals(1, active.size());
        assertTrue(active.getFirst().sameStoredPositionAs(checkpoint));
        assertEquals(identity, active.getFirst().executionContext());
    }

    @Test
    void v7BackfillsLegacyTerminalVerificationEvidenceWithoutRewritingEarlierMigrations()
            throws Exception {
        JdbcDataSource dataSource = h2("migration_v7_backfill");
        Flyway.configure()
                .dataSource(dataSource)
                .locations(FactoryDatabaseMigrations.FACTORY_MIGRATION_LOCATION)
                .javaMigrations(
                        new V2__install_action_runtime_0_3_3_schema(),
                        new V5__install_flower_0_1_3_schema())
                .target(MigrationVersion.fromVersion("6"))
                .load()
                .migrate();

        var session = PersistenceFixtures.buildSession("legacy-v7");
        var workOrder = PersistenceFixtures.workOrder(session, "legacy-v7");
        var candidate = JdbcPr3LedgerRepositoriesTest.candidate(
                session, workOrder, "legacy-v7", Optional.empty());
        persistPreV10CandidateDependencies(dataSource, session, workOrder);
        new JdbcCandidateVersionRepository(dataSource).create(candidate);

        var createdAt = PersistenceFixtures.NOW.plusSeconds(1);
        var startedAt = createdAt.plusSeconds(1);
        var completedAt = startedAt.plusSeconds(1);
        String verificationRunId = "verification-legacy-v7";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO factory_verification_run (
                            verification_run_id, tenant_id, build_session_id, candidate_id,
                            candidate_hash, gate_profile, toolchain_lock_hash, fixture_set_hash,
                            status, result_manifest_ref, started_at, completed_at, version,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            int index = 1;
            statement.setString(index++, verificationRunId);
            statement.setString(index++, candidate.tenantId().value());
            statement.setString(index++, candidate.buildSessionId().value());
            statement.setString(index++, candidate.candidateId().value());
            statement.setString(index++, candidate.sourceHash().sha256());
            statement.setString(index++, "factory-pr3");
            statement.setString(index++, candidate.toolchainLockHash().sha256());
            statement.setString(index++, "d".repeat(64));
            statement.setString(index++, "PASSED");
            statement.setString(index++, "artifact:legacy-v7-result");
            statement.setTimestamp(index++, Timestamp.from(startedAt));
            statement.setTimestamp(index++, Timestamp.from(completedAt));
            statement.setLong(index++, 2);
            statement.setTimestamp(index++, Timestamp.from(createdAt));
            statement.setTimestamp(index, Timestamp.from(completedAt));
            statement.executeUpdate();
        }

        assertEquals(10, FactoryDatabaseMigrations.migrate(dataSource).migrationsExecuted);
        var stored = new JdbcVerificationRunRepository(dataSource)
                .find(candidate.tenantId(), new io.github.flowerjvm.factory.contracts.ids.VerificationRunId(
                        verificationRunId))
                .orElseThrow();
        assertEquals(
                io.github.flowerjvm.factory.application.verification.VerificationRun.LEGACY_RESULT_MANIFEST_HASH,
                stored.resultManifestHash().orElseThrow());
        assertEquals("LEGACY_VERIFICATION_PASSED", stored.terminalCode().orElseThrow());
        assertEquals(
                io.github.flowerjvm.factory.contracts.verification.VerificationDisposition.REVIEW_ELIGIBLE,
                stored.disposition().orElseThrow());
    }

    @Test
    void v7RejectsLegacyDuplicateActiveRunsBeforeAnySchemaMutationOnH2() throws Exception {
        JdbcDataSource dataSource = h2("migration_v7_duplicate_active");
        migrateToV6(dataSource);

        var session = PersistenceFixtures.buildSession("legacy-v7-duplicate-active");
        var workOrder = PersistenceFixtures.workOrder(session, "legacy-v7-duplicate-active");
        var candidate = JdbcPr3LedgerRepositoriesTest.candidate(
                session, workOrder, "legacy-v7-duplicate-active", Optional.empty());
        persistPreV10CandidateDependencies(dataSource, session, workOrder);
        new JdbcCandidateVersionRepository(dataSource).create(candidate);
        insertLegacyRequestedVerification(
                dataSource,
                JdbcPr3LedgerRepositoriesTest.requestedVerification(
                        candidate, "legacy-v7-active-a", PersistenceFixtures.NOW.plusSeconds(1)));
        insertLegacyRequestedVerification(
                dataSource,
                JdbcPr3LedgerRepositoriesTest.requestedVerification(
                        candidate, "legacy-v7-active-b", PersistenceFixtures.NOW.plusSeconds(2)));

        FlywayException failure = assertThrows(
                FlywayException.class, () -> FactoryDatabaseMigrations.migrate(dataSource));

        assertTrue(causeChainContains(failure, V7_DUPLICATE_ACTIVE_REJECTION));
        try (Connection connection = dataSource.getConnection()) {
            try (ResultSet resultSet = connection.getMetaData().getColumns(
                    null, null, "FACTORY_VERIFICATION_RUN", "RESULT_MANIFEST_HASH")) {
                assertFalse(resultSet.next(), "V7 must reject before adding any column");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*)
                    FROM factory_verification_run
                    WHERE status IN ('REQUESTED', 'RUNNING')
                    """);
                    ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt(1), "migration must not invent terminal evidence");
            }
        }
    }

    static void migrateToV6(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(FactoryDatabaseMigrations.FACTORY_MIGRATION_LOCATION)
                .javaMigrations(
                        new V2__install_action_runtime_0_3_3_schema(),
                        new V5__install_flower_0_1_3_schema())
                .target(MigrationVersion.fromVersion("6"))
                .load()
                .migrate();
    }

    static void migrateToV8(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(FactoryDatabaseMigrations.FACTORY_MIGRATION_LOCATION)
                .javaMigrations(
                        new V2__install_action_runtime_0_3_3_schema(),
                        new V5__install_flower_0_1_3_schema())
                .target(MigrationVersion.fromVersion("8"))
                .load()
                .migrate();
    }

    static void migrateToV9(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(FactoryDatabaseMigrations.FACTORY_MIGRATION_LOCATION)
                .javaMigrations(
                        new V2__install_action_runtime_0_3_3_schema(),
                        new V5__install_flower_0_1_3_schema())
                .target(MigrationVersion.fromVersion("9"))
                .load()
                .migrate();
    }

    static void migrateToV10(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(FactoryDatabaseMigrations.FACTORY_MIGRATION_LOCATION)
                .javaMigrations(
                        new V2__install_action_runtime_0_3_3_schema(),
                        new V5__install_flower_0_1_3_schema())
                .target(MigrationVersion.fromVersion("10"))
                .load()
                .migrate();
    }

    static void persistPreV10CandidateDependencies(
            DataSource dataSource, BuildSession session, WorkOrder workOrder) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO factory_build_session (
                            build_session_id, tenant_id, project_id, request_idempotency_key,
                            active_request_key, created_by, status, current_phase,
                            requirements_artifact_ref, requirements_hash,
                            selected_manager_worker_binding, selected_coding_worker_binding,
                            current_blueprint_ref, current_candidate_id, current_candidate_hash,
                            current_certification_id, repair_round, max_repair_rounds, started_at,
                            deadline_at, cancellation_requested_at, terminal_code, terminal_message,
                            version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            int index = 1;
            statement.setString(index++, session.buildSessionId().value());
            statement.setString(index++, session.tenantId().value());
            statement.setString(index++, session.projectId().value());
            statement.setString(index++, session.requestIdempotencyKey());
            statement.setString(index++, session.requestIdempotencyKey());
            statement.setString(index++, session.createdBy());
            statement.setString(index++, session.status().name());
            statement.setString(index++, session.currentPhase().id());
            statement.setString(index++, session.requirementsArtifactRef().value());
            statement.setString(index++, session.requirementsHash().sha256());
            JdbcPersistenceSupport.setOptionalText(statement, index++, session.selectedManagerWorkerBinding());
            JdbcPersistenceSupport.setOptionalText(statement, index++, session.selectedCodingWorkerBinding());
            JdbcPersistenceSupport.setOptionalText(
                    statement, index++, session.currentBlueprintRef().map(reference -> reference.value()));
            JdbcPersistenceSupport.setOptionalText(
                    statement, index++, session.currentCandidateId().map(candidateId -> candidateId.value()));
            JdbcPersistenceSupport.setOptionalText(
                    statement, index++, session.currentCandidateHash().map(hash -> hash.sha256()));
            JdbcPersistenceSupport.setOptionalText(
                    statement,
                    index++,
                    session.currentCertificationId().map(certificationId -> certificationId.value()));
            statement.setInt(index++, session.repairRound());
            statement.setInt(index++, session.maxRepairRounds());
            statement.setTimestamp(index++, Timestamp.from(session.startedAt()));
            statement.setTimestamp(index++, Timestamp.from(session.deadlineAt()));
            JdbcPersistenceSupport.setOptionalInstant(statement, index++, session.cancellationRequestedAt());
            JdbcPersistenceSupport.setOptionalText(statement, index++, session.terminalCode());
            JdbcPersistenceSupport.setOptionalText(statement, index++, session.terminalMessage());
            statement.setLong(index++, session.version());
            statement.setTimestamp(index++, Timestamp.from(session.createdAt()));
            statement.setTimestamp(index, Timestamp.from(session.updatedAt()));
            statement.executeUpdate();
        }
        new JdbcWorkOrderRepository(dataSource).create(workOrder);
    }

    static void insertLegacyRequestedVerification(DataSource dataSource, VerificationRun requested)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO factory_verification_run (
                            verification_run_id, tenant_id, build_session_id, candidate_id,
                            candidate_hash, gate_profile, toolchain_lock_hash, fixture_set_hash,
                            status, result_manifest_ref, started_at, completed_at, version,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            int index = 1;
            statement.setString(index++, requested.verificationRunId().value());
            statement.setString(index++, requested.tenantId().value());
            statement.setString(index++, requested.buildSessionId().value());
            statement.setString(index++, requested.candidateId().value());
            statement.setString(index++, requested.candidateHash().sha256());
            statement.setString(index++, requested.gateProfile());
            statement.setString(index++, requested.toolchainLockHash().sha256());
            statement.setString(index++, requested.fixtureSetHash().sha256());
            statement.setString(index++, requested.status().name());
            statement.setString(index++, null);
            statement.setTimestamp(index++, null);
            statement.setTimestamp(index++, null);
            statement.setLong(index++, requested.version());
            statement.setTimestamp(index++, Timestamp.from(requested.createdAt()));
            statement.setTimestamp(index, Timestamp.from(requested.updatedAt()));
            statement.executeUpdate();
        }
    }

    static boolean causeChainContains(Throwable failure, String fragment) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> constraintColumns(
            Connection connection, String tableName, String constraintName) throws Exception {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name
                FROM information_schema.key_column_usage
                WHERE table_name = ? AND constraint_name = ?
                ORDER BY ordinal_position
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, constraintName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1).toLowerCase());
                }
            }
        }
        return List.copyOf(columns);
    }

    private static List<String> indexColumns(
            Connection connection, String tableName, String indexName) throws Exception {
        Map<Short, String> columns = new TreeMap<>();
        try (ResultSet resultSet = connection.getMetaData()
                .getIndexInfo(null, null, tableName, false, false)) {
            while (resultSet.next()) {
                if (!indexName.equalsIgnoreCase(resultSet.getString("INDEX_NAME"))) {
                    continue;
                }
                columns.put(
                        resultSet.getShort("ORDINAL_POSITION"),
                        resultSet.getString("COLUMN_NAME").toLowerCase());
            }
        }
        return List.copyOf(columns.values());
    }

    private static void assertForeignKey(
            Connection connection,
            String foreignTable,
            String constraintName,
            String expectedPrimaryTable,
            List<String> expectedForeignColumns,
            List<String> expectedPrimaryColumns) throws Exception {
        Map<Short, String> foreignColumns = new TreeMap<>();
        Map<Short, String> primaryColumns = new TreeMap<>();
        String primaryTable = null;
        try (ResultSet resultSet = connection.getMetaData().getImportedKeys(null, null, foreignTable)) {
            while (resultSet.next()) {
                if (!constraintName.equalsIgnoreCase(resultSet.getString("FK_NAME"))) {
                    continue;
                }
                short sequence = resultSet.getShort("KEY_SEQ");
                foreignColumns.put(sequence, resultSet.getString("FKCOLUMN_NAME").toLowerCase());
                primaryColumns.put(sequence, resultSet.getString("PKCOLUMN_NAME").toLowerCase());
                primaryTable = resultSet.getString("PKTABLE_NAME");
            }
        }
        assertEquals(expectedPrimaryTable, primaryTable);
        assertEquals(expectedForeignColumns, List.copyOf(foreignColumns.values()));
        assertEquals(expectedPrimaryColumns, List.copyOf(primaryColumns.values()));
    }

    static JdbcDataSource h2(String suffix) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:factory_" + suffix + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }
}
