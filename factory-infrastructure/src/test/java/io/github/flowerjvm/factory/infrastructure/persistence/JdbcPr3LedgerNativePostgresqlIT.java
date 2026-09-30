package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.PersistenceFixtures.HASH_B;
import static io.github.flowerjvm.factory.infrastructure.persistence.PersistenceFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.flow.FlowPersistence;
import io.github.flowerjvm.flower.core.flow.FlowState;
import io.github.flowerjvm.flower.core.persistence.FlowCheckpoint;
import io.github.flowerjvm.flower.persistence.jdbc.JdbcCheckpointDialects;
import io.github.flowerjvm.flower.persistence.jdbc.JdbcFlowCheckpointStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcPr3LedgerNativePostgresqlIT {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void nativePostgresqlMigratesRoundTripsLocksIdentitiesAndAllowsOneCasWinner() throws Exception {
        PGSimpleDataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);

        var artifacts = new JdbcArtifactStore(dataSource);
        var emptyArtifact = new Artifact(
                new TenantId("tenant-native-pr4"),
                new ArtifactReference("artifact:native-pr4:empty"),
                new ContentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                "application/octet-stream",
                new byte[0]);
        assertEquals(emptyArtifact.reference(), artifacts.store(emptyArtifact));
        assertEquals(
                emptyArtifact.contentHash(),
                artifacts.find(emptyArtifact.tenantId(), emptyArtifact.reference())
                        .orElseThrow()
                        .contentHash());
        assertTrue(artifacts.find(new TenantId("tenant-native-other"), emptyArtifact.reference()).isEmpty());

        var identity = ExecutionContext.builder()
                .tenantId("tenant-native-pr3")
                .userId("principal-native-pr3")
                .sessionId("build-native-pr3")
                .runId("flow-native-pr3")
                .traceId("trace-native-pr3")
                .correlationId("project-native-pr3")
                .build();
        var checkpoint = new FlowCheckpoint(
                FlowId.of("create-customer-agent", "build-native-pr3"),
                FlowState.RUNNING,
                "VERIFY_CANDIDATE",
                10,
                true,
                FlowPersistence.DURABLE,
                "factory",
                1_786_473_600_001L,
                "factory-pr3-v1",
                identity);
        var checkpointStore = JdbcFlowCheckpointStore.create(
                dataSource, JdbcCheckpointDialects.postgresql());
        checkpointStore.save(checkpoint);
        FlowCheckpoint storedCheckpoint = checkpointStore.find(checkpoint.flowId()).orElseThrow();
        assertTrue(storedCheckpoint.sameStoredPositionAs(checkpoint));
        assertEquals(identity, storedCheckpoint.executionContext());

        var session = PersistenceFixtures.buildSession("native-pr3-roundtrip");
        var workOrder = PersistenceFixtures.workOrder(session, "native-pr3-roundtrip");
        var candidate = JdbcPr3LedgerRepositoriesTest.candidate(
                session, workOrder, "native-pr3-roundtrip", Optional.empty());
        JdbcPr3LedgerRepositoriesTest.persistCandidateDependencies(dataSource, session, workOrder);
        var candidates = new JdbcCandidateVersionRepository(dataSource);
        var verifications = new JdbcVerificationRunRepository(dataSource);
        candidates.create(candidate);
        var requested = JdbcPr3LedgerRepositoriesTest.requestedVerification(
                candidate, "native-pr3-roundtrip", NOW.plusSeconds(10));
        verifications.create(requested);

        assertEquals(candidate, candidates.find(candidate.tenantId(), candidate.candidateId()).orElseThrow());
        assertEquals(
                candidate,
                candidates
                        .findByBuildSessionAndWorkOrder(
                                candidate.tenantId(),
                                candidate.buildSessionId(),
                                candidate.createdByWorkOrderId())
                        .orElseThrow());
        assertTrue(candidates.find(new TenantId("tenant-native-other"), candidate.candidateId()).isEmpty());
        assertThrows(
                DuplicateLedgerRecordException.class,
                () -> candidates.create(JdbcPr3LedgerRepositoriesTest.candidate(
                        session, workOrder, "native-pr3-duplicate-work-order", Optional.empty())));

        VerificationRun wrongCandidateHash = JdbcPr3LedgerRepositoriesTest.requestedVerification(
                candidate.tenantId(),
                candidate.buildSessionId(),
                candidate.candidateId(),
                HASH_B,
                candidate.toolchainLockHash(),
                "native-pr3-wrong-hash",
                NOW.plusSeconds(11));
        assertThrows(FactoryPersistenceException.class, () -> verifications.create(wrongCandidateHash));

        VerificationRun wrongToolchain = JdbcPr3LedgerRepositoriesTest.requestedVerification(
                candidate.tenantId(),
                candidate.buildSessionId(),
                candidate.candidateId(),
                candidate.sourceHash(),
                new ContentHash("e".repeat(64)),
                "native-pr3-wrong-toolchain",
                NOW.plusSeconds(11));
        assertThrows(FactoryPersistenceException.class, () -> verifications.create(wrongToolchain));

        var otherSession = PersistenceFixtures.buildSession("native-pr3-other-session");
        new JdbcBuildSessionRepository(dataSource).create(otherSession);
        VerificationRun wrongSession = JdbcPr3LedgerRepositoriesTest.requestedVerification(
                candidate.tenantId(),
                otherSession.buildSessionId(),
                candidate.candidateId(),
                candidate.sourceHash(),
                candidate.toolchainLockHash(),
                "native-pr3-wrong-session",
                NOW.plusSeconds(11));
        assertThrows(FactoryPersistenceException.class, () -> verifications.create(wrongSession));

        VerificationRun running = requested.start(NOW.plusSeconds(12));
        assertTrue(verifications.compareAndSet(requested, running));
        VerificationRun passed = running.complete(
                VerificationRunStatus.PASSED,
                new ArtifactReference("artifact:native-pr3:passed"),
                new ContentHash("f".repeat(64)),
                "VERIFICATION_PASSED",
                VerificationDisposition.REVIEW_ELIGIBLE,
                NOW.plusSeconds(13));
        VerificationRun failed = running.complete(
                VerificationRunStatus.FAILED,
                new ArtifactReference("artifact:native-pr3:failed"),
                new ContentHash("f".repeat(64)),
                "MAVEN_VERIFICATION_FAILED",
                VerificationDisposition.REPAIR_REQUIRED,
                NOW.plusSeconds(13));
        List<Boolean> outcomes = race(
                () -> new JdbcVerificationRunRepository(dataSource).compareAndSet(running, passed),
                () -> new JdbcVerificationRunRepository(dataSource).compareAndSet(running, failed));

        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        VerificationRun stored = verifications
                .find(candidate.tenantId(), requested.verificationRunId())
                .orElseThrow();
        assertTrue(stored.status().isTerminal());
        assertEquals(2, stored.version());
        assertTrue(stored.equals(passed) || stored.equals(failed));

        for (int iteration = 0; iteration < 8; iteration++) {
            JdbcPr3LedgerRepositoriesTest.assertVerificationCompletionRace(
                    dataSource, "native-pr3-race-" + iteration, 100L + iteration * 10L);
        }
    }

    @Test
    void v7RejectsLegacyDuplicateActiveRunsBeforeAnySchemaMutationOnNativePostgresql()
            throws Exception {
        PGSimpleDataSource dataSource = isolatedDataSource("factory_v7_duplicate_active");
        FactoryDatabaseMigrationsTest.migrateToV6(dataSource);

        var session = PersistenceFixtures.buildSession("native-v7-duplicate-active");
        var workOrder = PersistenceFixtures.workOrder(session, "native-v7-duplicate-active");
        var candidate = JdbcPr3LedgerRepositoriesTest.candidate(
                session, workOrder, "native-v7-duplicate-active", Optional.empty());
        FactoryDatabaseMigrationsTest.persistPreV10CandidateDependencies(dataSource, session, workOrder);
        new JdbcCandidateVersionRepository(dataSource).create(candidate);
        FactoryDatabaseMigrationsTest.insertLegacyRequestedVerification(
                dataSource,
                JdbcPr3LedgerRepositoriesTest.requestedVerification(
                        candidate, "native-v7-active-a", NOW.plusSeconds(1)));
        FactoryDatabaseMigrationsTest.insertLegacyRequestedVerification(
                dataSource,
                JdbcPr3LedgerRepositoriesTest.requestedVerification(
                        candidate, "native-v7-active-b", NOW.plusSeconds(2)));

        FlywayException failure = assertThrows(
                FlywayException.class, () -> FactoryDatabaseMigrations.migrate(dataSource));

        assertTrue(FactoryDatabaseMigrationsTest.causeChainContains(
                failure, FactoryDatabaseMigrationsTest.V7_DUPLICATE_ACTIVE_REJECTION));
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT
                            (SELECT COUNT(*)
                             FROM information_schema.columns
                             WHERE table_schema = current_schema()
                               AND table_name = 'factory_verification_run'
                               AND column_name = 'result_manifest_hash'),
                            (SELECT COUNT(*)
                             FROM factory_verification_run
                             WHERE status IN ('REQUESTED', 'RUNNING'))
                        """);
                var resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            assertEquals(0, resultSet.getInt(1), "V7 must reject before adding any column");
            assertEquals(2, resultSet.getInt(2), "migration must not invent terminal evidence");
        }
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static PGSimpleDataSource isolatedDataSource(String schema) throws Exception {
        PGSimpleDataSource administrator = dataSource();
        try (var connection = administrator.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        PGSimpleDataSource isolated = dataSource();
        isolated.setCurrentSchema(schema);
        return isolated;
    }

    private static List<Boolean> race(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(3);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> firstResult = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return first.call();
            });
            Future<Boolean> secondResult = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.call();
            });
            barrier.await(5, TimeUnit.SECONDS);
            return List.of(
                    firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS));
        }
    }
}
