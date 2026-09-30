package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.PersistenceFixtures.HASH_A;
import static io.github.flowerjvm.factory.infrastructure.persistence.PersistenceFixtures.HASH_B;
import static io.github.flowerjvm.factory.infrastructure.persistence.PersistenceFixtures.NOW;
import static io.github.flowerjvm.factory.infrastructure.persistence.PersistenceFixtures.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcPr3LedgerRepositoriesTest {
    private static final ContentHash DEPENDENCY_HASH = new ContentHash("c".repeat(64));
    private static final ContentHash FIXTURE_HASH = new ContentHash("d".repeat(64));

    @Test
    void roundTripsCandidateAndVerificationQueriesWithTenantIsolation() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("pr3_roundtrip");
        FactoryDatabaseMigrations.migrate(dataSource);
        BuildSession session = PersistenceFixtures.buildSession("pr3-roundtrip");
        WorkOrder workOrder = PersistenceFixtures.workOrder(session, "pr3-roundtrip");
        CandidateVersion candidate = candidate(session, workOrder, "pr3-roundtrip", Optional.empty());
        VerificationRun olderRequested = requestedVerification(candidate, "older", NOW.plusSeconds(1));
        VerificationRun olderRunning = olderRequested.start(NOW.plusSeconds(2));
        VerificationRun older = olderRunning.complete(
                VerificationRunStatus.PASSED,
                new ArtifactReference("artifact:verification-result:older"),
                HASH_B,
                "VERIFICATION_PASSED",
                VerificationDisposition.REVIEW_ELIGIBLE,
                NOW.plusSeconds(3));
        VerificationRun latest = requestedVerification(candidate, "latest", NOW.plusSeconds(4));

        persistCandidateDependencies(dataSource, session, workOrder);
        var candidates = new JdbcCandidateVersionRepository(dataSource);
        var verifications = new JdbcVerificationRunRepository(dataSource);
        candidates.create(candidate);
        verifications.create(olderRequested);
        assertTrue(verifications.compareAndSet(olderRequested, olderRunning));
        assertTrue(verifications.compareAndSet(olderRunning, older));
        verifications.create(latest);

        assertEquals(candidate, candidates.find(TENANT, candidate.candidateId()).orElseThrow());
        assertEquals(
                candidate,
                candidates
                        .findByBuildSessionAndWorkOrder(
                                TENANT, session.buildSessionId(), workOrder.workOrderId())
                        .orElseThrow());
        assertEquals(older, verifications.find(TENANT, older.verificationRunId()).orElseThrow());
        assertEquals(
                latest,
                verifications
                        .findLatestForCandidate(
                                TENANT,
                                session.buildSessionId(),
                                candidate.candidateId(),
                                candidate.sourceHash(),
                                latest.gateProfile())
                        .orElseThrow());

        TenantId otherTenant = new TenantId("tenant-b");
        assertTrue(candidates.find(otherTenant, candidate.candidateId()).isEmpty());
        assertTrue(candidates
                .findByBuildSessionAndWorkOrder(
                        otherTenant, session.buildSessionId(), workOrder.workOrderId())
                .isEmpty());
        assertTrue(verifications.find(otherTenant, older.verificationRunId()).isEmpty());
        assertTrue(verifications
                .findLatestForCandidate(
                        otherTenant,
                        session.buildSessionId(),
                        candidate.candidateId(),
                        candidate.sourceHash(),
                        older.gateProfile())
                .isEmpty());
        assertThrows(DuplicateLedgerRecordException.class, () -> candidates.create(candidate));
        CandidateVersion duplicateWorkOrder = candidate(
                session, workOrder, "pr3-roundtrip-other-candidate", Optional.empty());
        assertThrows(DuplicateLedgerRecordException.class, () -> candidates.create(duplicateWorkOrder));
        assertThrows(DuplicateLedgerRecordException.class, () -> verifications.create(older));
    }

    @Test
    void verificationLifecycleUsesExactVersionCas() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("verification_cas");
        FactoryDatabaseMigrations.migrate(dataSource);
        BuildSession session = PersistenceFixtures.buildSession("verification-cas");
        WorkOrder workOrder = PersistenceFixtures.workOrder(session, "verification-cas");
        CandidateVersion candidate = candidate(session, workOrder, "verification-cas", Optional.empty());
        VerificationRun requested = requestedVerification(candidate, "cas", NOW.plusSeconds(1));
        var candidates = new JdbcCandidateVersionRepository(dataSource);
        var verifications = new JdbcVerificationRunRepository(dataSource);
        persistCandidateDependencies(dataSource, session, workOrder);
        candidates.create(candidate);
        verifications.create(requested);

        VerificationRun started = requested.start(NOW.plusSeconds(2));
        VerificationRun invalidVersion = new VerificationRun(
                started.verificationRunId(),
                started.tenantId(),
                started.buildSessionId(),
                started.candidateId(),
                started.candidateHash(),
                started.gateProfile(),
                started.toolchainLockHash(),
                started.fixtureSetHash(),
                started.status(),
                started.resultManifestRef(),
                started.startedAt(),
                started.completedAt(),
                started.version() + 1,
                started.createdAt(),
                started.updatedAt());
        assertThrows(IllegalArgumentException.class, () -> verifications.compareAndSet(requested, invalidVersion));
        assertTrue(verifications.compareAndSet(requested, started));
        assertFalse(verifications.compareAndSet(requested, started));

        VerificationRun passed = started.complete(
                VerificationRunStatus.PASSED,
                new ArtifactReference("artifact:verification-result:passed"),
                HASH_B,
                "VERIFICATION_PASSED",
                VerificationDisposition.REVIEW_ELIGIBLE,
                NOW.plusSeconds(3));
        VerificationRun failed = started.complete(
                VerificationRunStatus.FAILED,
                new ArtifactReference("artifact:verification-result:failed"),
                HASH_B,
                "MAVEN_VERIFICATION_FAILED",
                VerificationDisposition.REPAIR_REQUIRED,
                NOW.plusSeconds(3));
        assertTrue(verifications.compareAndSet(started, passed));
        assertFalse(verifications.compareAndSet(started, failed));
        assertEquals(passed, verifications.find(TENANT, passed.verificationRunId()).orElseThrow());
        VerificationRun terminalOverwrite = new VerificationRun(
                passed.verificationRunId(),
                passed.tenantId(),
                passed.buildSessionId(),
                passed.candidateId(),
                passed.candidateHash(),
                passed.gateProfile(),
                passed.toolchainLockHash(),
                passed.fixtureSetHash(),
                VerificationRunStatus.FAILED,
                Optional.of(new ArtifactReference("artifact:verification-result:overwrite")),
                passed.startedAt(),
                Optional.of(NOW.plusSeconds(4)),
                passed.version() + 1,
                passed.createdAt(),
                NOW.plusSeconds(4));
        IllegalArgumentException terminalError = assertThrows(
                IllegalArgumentException.class,
                () -> verifications.compareAndSet(passed, terminalOverwrite));
        assertEquals("terminal VerificationRun must not be overwritten", terminalError.getMessage());
    }

    @Test
    void verificationCompletionRaceHasOneCasWinner() throws Exception {
        var dataSource = FactoryDatabaseMigrationsTest.h2("verification_race");
        FactoryDatabaseMigrations.migrate(dataSource);
        for (int iteration = 0; iteration < 8; iteration++) {
            assertVerificationCompletionRace(
                    dataSource, "h2-verification-race-" + iteration, iteration * 10L);
        }
    }

    @Test
    void verificationAllowsOnlyOneActiveRunPerImmutableCandidateAndGate() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("verification_active_key");
        FactoryDatabaseMigrations.migrate(dataSource);
        BuildSession session = PersistenceFixtures.buildSession("verification-active-key");
        WorkOrder workOrder = PersistenceFixtures.workOrder(session, "verification-active-key");
        CandidateVersion candidate = candidate(
                session, workOrder, "verification-active-key", Optional.empty());
        VerificationRun first = requestedVerification(candidate, "active-first", NOW.plusSeconds(1));
        VerificationRun contender = requestedVerification(
                candidate, "active-contender", NOW.plusSeconds(2));
        persistCandidateDependencies(dataSource, session, workOrder);
        new JdbcCandidateVersionRepository(dataSource).create(candidate);
        var verifications = new JdbcVerificationRunRepository(dataSource);

        verifications.create(first);
        assertThrows(DuplicateLedgerRecordException.class, () -> verifications.create(contender));

        VerificationRun running = first.start(NOW.plusSeconds(3));
        assertTrue(verifications.compareAndSet(first, running));
        assertThrows(DuplicateLedgerRecordException.class, () -> verifications.create(contender));

        VerificationRun failed = running.complete(
                VerificationRunStatus.FAILED,
                new ArtifactReference("artifact:verification-result:active-first"),
                HASH_B,
                "MAVEN_VERIFICATION_FAILED",
                VerificationDisposition.REPAIR_REQUIRED,
                NOW.plusSeconds(4));
        assertTrue(verifications.compareAndSet(running, failed));
        verifications.create(contender);
        assertEquals(
                contender,
                verifications.find(TENANT, contender.verificationRunId()).orElseThrow());
    }

    @Test
    void candidateAndVerificationForeignKeysBindTenantSessionAndLockedHashes() {
        var dataSource = FactoryDatabaseMigrationsTest.h2("pr3_tenant_fk");
        FactoryDatabaseMigrations.migrate(dataSource);
        BuildSession sessionA = PersistenceFixtures.buildSession("pr3-owner-a");
        WorkOrder workOrderA = PersistenceFixtures.workOrder(sessionA, "pr3-owner-a");
        CandidateVersion candidateA = candidate(sessionA, workOrderA, "pr3-owner-a", Optional.empty());
        persistCandidateDependencies(dataSource, sessionA, workOrderA);
        new JdbcCandidateVersionRepository(dataSource).create(candidateA);

        TenantId tenantB = new TenantId("tenant-b");
        BuildSession sessionB = sessionForTenant(tenantB, "pr3-owner-b");
        WorkOrder workOrderB = workOrderForSession(sessionB, "pr3-owner-b");
        persistCandidateDependencies(dataSource, sessionB, workOrderB);
        CandidateVersion crossTenantParent = candidate(
                sessionB, workOrderB, "pr3-owner-b", Optional.of(candidateA.candidateId()));
        assertThrows(
                FactoryPersistenceException.class,
                () -> new JdbcCandidateVersionRepository(dataSource).create(crossTenantParent));

        VerificationRun crossTenantVerification = requestedVerification(
                tenantB,
                sessionB.buildSessionId(),
                candidateA.candidateId(),
                candidateA.sourceHash(),
                candidateA.toolchainLockHash(),
                "cross-tenant",
                NOW.plusSeconds(4));
        assertThrows(
                FactoryPersistenceException.class,
                () -> new JdbcVerificationRunRepository(dataSource).create(crossTenantVerification));

        VerificationRun wrongHash = requestedVerification(
                TENANT,
                sessionA.buildSessionId(),
                candidateA.candidateId(),
                HASH_B,
                candidateA.toolchainLockHash(),
                "wrong-hash",
                NOW.plusSeconds(5));
        assertThrows(
                FactoryPersistenceException.class,
                () -> new JdbcVerificationRunRepository(dataSource).create(wrongHash));

        VerificationRun wrongToolchain = requestedVerification(
                TENANT,
                sessionA.buildSessionId(),
                candidateA.candidateId(),
                candidateA.sourceHash(),
                DEPENDENCY_HASH,
                "wrong-toolchain",
                NOW.plusSeconds(6));
        assertThrows(
                FactoryPersistenceException.class,
                () -> new JdbcVerificationRunRepository(dataSource).create(wrongToolchain));

        BuildSession otherSession = PersistenceFixtures.buildSession("pr3-owner-other-session");
        new JdbcBuildSessionRepository(dataSource).create(otherSession);
        VerificationRun wrongSession = requestedVerification(
                TENANT,
                otherSession.buildSessionId(),
                candidateA.candidateId(),
                candidateA.sourceHash(),
                candidateA.toolchainLockHash(),
                "wrong-session",
                NOW.plusSeconds(7));
        assertThrows(
                FactoryPersistenceException.class,
                () -> new JdbcVerificationRunRepository(dataSource).create(wrongSession));
    }

    static CandidateVersion candidate(
            BuildSession session,
            WorkOrder workOrder,
            String suffix,
            Optional<CandidateId> parentCandidateId) {
        return new CandidateVersion(
                new CandidateId("candidate-" + suffix),
                session.tenantId(),
                session.buildSessionId(),
                parentCandidateId,
                new ArtifactReference("artifact:candidate-source:" + suffix),
                HASH_A,
                new ArtifactReference("artifact:dependency-lock:" + suffix),
                DEPENDENCY_HASH,
                new ArtifactReference("artifact:toolchain-lock:" + suffix),
                HASH_B,
                CandidateVersionStatus.GENERATED,
                workOrder.workOrderId(),
                workOrder.createdAt().plusMillis(1));
    }

    static VerificationRun requestedVerification(
            CandidateVersion candidate, String suffix, java.time.Instant createdAt) {
        return requestedVerification(
                candidate.tenantId(),
                candidate.buildSessionId(),
                candidate.candidateId(),
                candidate.sourceHash(),
                candidate.toolchainLockHash(),
                suffix,
                createdAt);
    }

    static VerificationRun requestedVerification(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            CandidateId candidateId,
            ContentHash candidateHash,
            ContentHash toolchainLockHash,
            String suffix,
            java.time.Instant createdAt) {
        return new VerificationRun(
                new VerificationRunId("verification-" + suffix),
                tenantId,
                buildSessionId,
                candidateId,
                candidateHash,
                "factory-pr3",
                toolchainLockHash,
                FIXTURE_HASH,
                VerificationRunStatus.REQUESTED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                createdAt,
                createdAt);
    }

    static void persistCandidateDependencies(
            DataSource dataSource, BuildSession session, WorkOrder workOrder) {
        new JdbcBuildSessionRepository(dataSource).create(session);
        new JdbcWorkOrderRepository(dataSource).create(workOrder);
    }

    static void assertVerificationCompletionRace(
            DataSource dataSource, String suffix, long secondsOffset) throws Exception {
        BuildSession session = PersistenceFixtures.buildSession(suffix);
        WorkOrder workOrder = PersistenceFixtures.workOrder(session, suffix);
        CandidateVersion candidate = candidate(session, workOrder, suffix, Optional.empty());
        VerificationRun requested = requestedVerification(
                candidate, suffix, NOW.plusSeconds(secondsOffset + 1));
        VerificationRun running = requested.start(NOW.plusSeconds(secondsOffset + 2));
        persistCandidateDependencies(dataSource, session, workOrder);
        new JdbcCandidateVersionRepository(dataSource).create(candidate);
        var repository = new JdbcVerificationRunRepository(dataSource);
        repository.create(requested);
        assertTrue(repository.compareAndSet(requested, running));

        VerificationRun passed = running.complete(
                VerificationRunStatus.PASSED,
                new ArtifactReference("artifact:" + suffix + ":passed"),
                NOW.plusSeconds(secondsOffset + 3));
        VerificationRun failed = running.complete(
                VerificationRunStatus.FAILED,
                new ArtifactReference("artifact:" + suffix + ":failed"),
                NOW.plusSeconds(secondsOffset + 3));
        List<Boolean> outcomes = race(
                () -> new JdbcVerificationRunRepository(dataSource).compareAndSet(running, passed),
                () -> new JdbcVerificationRunRepository(dataSource).compareAndSet(running, failed));

        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        VerificationRun stored = repository.find(TENANT, running.verificationRunId()).orElseThrow();
        assertTrue(stored.status().isTerminal());
        assertEquals(2, stored.version());
        assertTrue(stored.equals(passed) || stored.equals(failed));
    }

    private static BuildSession sessionForTenant(TenantId tenantId, String suffix) {
        BuildSession template = PersistenceFixtures.buildSession(suffix);
        return new BuildSession(
                template.buildSessionId(),
                tenantId,
                new ProjectId("project-" + suffix),
                template.productLineId(),
                template.requestIdempotencyKey(),
                template.createdBy(),
                template.status(),
                template.currentPhase(),
                template.requirementsArtifactRef(),
                template.requirementsHash(),
                template.selectedManagerWorkerBinding(),
                template.selectedCodingWorkerBinding(),
                template.currentBlueprintRef(),
                template.currentCandidateId(),
                template.currentCandidateHash(),
                template.currentCertificationId(),
                template.repairRound(),
                template.maxRepairRounds(),
                template.startedAt(),
                template.deadlineAt(),
                template.cancellationRequestedAt(),
                template.terminalCode(),
                template.terminalMessage(),
                template.version(),
                template.createdAt(),
                template.updatedAt());
    }

    private static WorkOrder workOrderForSession(BuildSession session, String suffix) {
        WorkOrder template = PersistenceFixtures.workOrder(session, suffix);
        return template;
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
