package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.PersistenceFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunRequestTransaction;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JdbcVerificationRunRequestTransactionTest {
    @Test
    void exactRetryReturnsCurrentCanonicalSnapshotAndMismatchedIdPayloadFails() {
        var fixture = fixture("request-canonical");
        var transaction = new JdbcVerificationRunRequestTransaction(fixture.dataSource());
        VerificationRun requested = fixture.requested("canonical", NOW.plusSeconds(1));

        assertEquals(requested, transaction.request(requested));
        VerificationRun running = requested.start(NOW.plusSeconds(2));
        assertTrue(fixture.repository().compareAndSet(requested, running));

        VerificationRun sameRequestWithLaterTransportTime = new VerificationRun(
                requested.verificationRunId(),
                requested.tenantId(),
                requested.buildSessionId(),
                requested.candidateId(),
                requested.candidateHash(),
                requested.gateProfile(),
                requested.toolchainLockHash(),
                requested.fixtureSetHash(),
                VerificationRunStatus.REQUESTED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW.plusSeconds(20),
                NOW.plusSeconds(20));
        assertEquals(running, transaction.request(sameRequestWithLaterTransportTime));

        VerificationRun mismatchedGateForSameDeterministicId = new VerificationRun(
                requested.verificationRunId(),
                requested.tenantId(),
                requested.buildSessionId(),
                requested.candidateId(),
                requested.candidateHash(),
                requested.gateProfile() + "-different",
                requested.toolchainLockHash(),
                requested.fixtureSetHash(),
                VerificationRunStatus.REQUESTED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW.plusSeconds(21),
                NOW.plusSeconds(21));
        assertThrows(
                FactoryPersistenceException.class,
                () -> transaction.request(mismatchedGateForSameDeterministicId));
    }

    @Test
    void concurrentExactRetriesReturnOneCanonicalRequest() throws Exception {
        var fixture = fixture("request-exact-race");
        VerificationRun requested = fixture.requested("exact-race", NOW.plusSeconds(1));

        List<VerificationRun> outcomes = race(
                () -> new JdbcVerificationRunRequestTransaction(fixture.dataSource()).request(requested),
                () -> new JdbcVerificationRunRequestTransaction(fixture.dataSource()).request(requested));

        assertEquals(List.of(requested, requested), outcomes);
        assertEquals(
                requested,
                fixture.repository()
                        .find(requested.tenantId(), requested.verificationRunId())
                        .orElseThrow());
    }

    @Test
    void concurrentDifferentIdsForSameActiveCandidateAllowOnlyOne() throws Exception {
        var fixture = fixture("request-active-race");
        VerificationRun first = fixture.requested("active-race-first", NOW.plusSeconds(1));
        VerificationRun second = withRunId(
                first, new VerificationRunId("verification-active-race-second"));

        List<Boolean> outcomes = race(
                () -> requestOutcome(fixture, first),
                () -> requestOutcome(fixture, second));

        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        long stored = List.of(first, second).stream()
                .filter(run -> fixture.repository()
                        .find(run.tenantId(), run.verificationRunId())
                        .isPresent())
                .count();
        assertEquals(1, stored);
    }

    @Test
    void runningRequestRemainsTheSameCanonicalAttemptWithoutAgeBasedRequeue() {
        var fixture = fixture("request-running-recovery");
        VerificationRun requested = fixture.requested("running-recovery", NOW.plusSeconds(1));
        var transaction = new JdbcVerificationRunRequestTransaction(fixture.dataSource());
        transaction.request(requested);
        VerificationRun running = requested.start(NOW.plusSeconds(2));
        assertTrue(fixture.repository().compareAndSet(requested, running));

        assertEquals(running, transaction.request(requested));
        assertEquals(running, fixture.repository()
                .find(running.tenantId(), running.verificationRunId())
                .orElseThrow());
        assertEquals(VerificationRunStatus.RUNNING, running.status());
        assertEquals(1, running.version());
    }

    private static boolean requestOutcome(Fixture fixture, VerificationRun requested) {
        try {
            new JdbcVerificationRunRequestTransaction(fixture.dataSource()).request(requested);
            return true;
        } catch (FactoryPersistenceException conflict) {
            return false;
        }
    }

    private static VerificationRun withRunId(
            VerificationRun source, VerificationRunId verificationRunId) {
        return new VerificationRun(
                verificationRunId,
                source.tenantId(),
                source.buildSessionId(),
                source.candidateId(),
                source.candidateHash(),
                source.gateProfile(),
                source.toolchainLockHash(),
                source.fixtureSetHash(),
                source.status(),
                source.resultManifestRef(),
                source.startedAt(),
                source.completedAt(),
                source.version(),
                source.createdAt(),
                source.updatedAt());
    }

    private static Fixture fixture(String suffix) {
        var dataSource = FactoryDatabaseMigrationsTest.h2(suffix);
        FactoryDatabaseMigrations.migrate(dataSource);
        var session = PersistenceFixtures.buildSession(suffix);
        var workOrder = PersistenceFixtures.workOrder(session, suffix);
        var candidate = JdbcPr3LedgerRepositoriesTest.candidate(
                session, workOrder, suffix, Optional.empty());
        JdbcPr3LedgerRepositoriesTest.persistCandidateDependencies(dataSource, session, workOrder);
        new JdbcCandidateVersionRepository(dataSource).create(candidate);
        return new Fixture(
                dataSource,
                candidate,
                new JdbcVerificationRunRepository(dataSource));
    }

    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(3);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<T> firstResult = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return first.call();
            });
            Future<T> secondResult = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.call();
            });
            barrier.await(5, TimeUnit.SECONDS);
            return List.of(
                    firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS));
        }
    }

    private record Fixture(
            org.h2.jdbcx.JdbcDataSource dataSource,
            io.github.flowerjvm.factory.application.candidate.CandidateVersion candidate,
            JdbcVerificationRunRepository repository) {
        VerificationRun requested(String suffix, java.time.Instant createdAt) {
            return JdbcPr3LedgerRepositoriesTest.requestedVerification(candidate, suffix, createdAt);
        }
    }
}
