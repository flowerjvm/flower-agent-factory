package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAttemptTokens;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntent;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchOperationIds;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JdbcReferenceAssemblyReleaseDispatchIntentRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:01:00.123456Z");

    @Test
    void createRoundTripsEveryOwnerLockAndExactDuplicateConverges() {
        Fixture fixture = Fixture.create("roundtrip");

        fixture.repository().create(fixture.pending());
        fixture.repository().create(fixture.pending());

        ReferenceAssemblyReleaseDispatchIntent stored = fixture.repository()
                .find(fixture.pending().operationId())
                .orElseThrow();
        assertEquals(fixture.pending(), stored);
        assertEquals(fixture.tenant(), stored.tenantId());
        assertEquals(fixture.input().referenceAssemblyId(), stored.referenceAssemblyId());
        assertEquals(fixture.input().assemblyManifestHash(), stored.assemblyManifestHash());
        assertEquals(fixture.input().inspectionReportHash(), stored.inspectionReportHash());
        assertEquals(fixture.input().releaseDecisionPointId(), stored.releaseDecisionPointId());
        assertEquals(fixture.input().releaseSubjectHash(), stored.releaseSubjectHash());
        assertEquals(
                fixture.input().expectedReferenceAssemblyVersion(),
                stored.expectedReferenceAssemblyVersion());
        assertEquals(fixture.actionRunId(), stored.actionRunId());
        assertEquals(fixture.pending().attemptTokenHash(), stored.attemptTokenHash());
        assertEquals(fixture.pending().deadlineAt(), stored.deadlineAt());
    }

    @Test
    void createRejectsSameOperationWithDifferentOwnerAndSameVersionWithDifferentLocks() {
        Fixture fixture = Fixture.create("duplicate-conflict");
        fixture.repository().create(fixture.pending());
        String secondActionRunId = fixture.createSecondActionOwner();

        ReferenceAssemblyReleaseDispatchIntent differentOwner = fixture.pending(
                fixture.input(), secondActionRunId, NOW);
        assertThrows(
                DuplicateLedgerRecordException.class,
                () -> fixture.repository().create(differentOwner));

        ReferenceAssemblyReleaseInput differentInspection = new ReferenceAssemblyReleaseInput(
                fixture.input().referenceAssemblyId(),
                fixture.input().assemblyManifestHash(),
                hash('8'),
                fixture.input().releaseDecisionPointId(),
                fixture.input().releaseSubjectHash(),
                fixture.input().expectedReferenceAssemblyVersion());
        ReferenceAssemblyReleaseDispatchIntent conflictingAttempt = fixture.pending(
                differentInspection, secondActionRunId, NOW);
        assertThrows(
                DuplicateLedgerRecordException.class,
                () -> fixture.repository().create(conflictingAttempt));
        assertEquals(
                fixture.pending(),
                fixture.repository().find(fixture.pending().operationId()).orElseThrow());
    }

    @Test
    void findLatestUsesExactTenantAssemblyAndExpectedVersionOrdering() {
        Fixture fixture = Fixture.create("newest-order");
        fixture.repository().create(fixture.pending());
        String secondActionRunId = fixture.createSecondActionOwner();
        ReferenceAssemblyReleaseInput nextInput = new ReferenceAssemblyReleaseInput(
                fixture.input().referenceAssemblyId(),
                fixture.input().assemblyManifestHash(),
                fixture.input().inspectionReportHash(),
                fixture.input().releaseDecisionPointId(),
                fixture.input().releaseSubjectHash(),
                fixture.input().expectedReferenceAssemblyVersion() + 1);
        ReferenceAssemblyReleaseDispatchIntent next = fixture.pending(
                nextInput, secondActionRunId, NOW.plusSeconds(1));
        fixture.repository().create(next);

        assertEquals(
                next,
                fixture.repository()
                        .findLatest(fixture.tenant(), fixture.input().referenceAssemblyId())
                        .orElseThrow());
        assertTrue(fixture.repository()
                .findLatest(
                        new io.github.flowerjvm.factory.contracts.ids.TenantId("tenant-other"),
                        fixture.input().referenceAssemblyId())
                .isEmpty());
    }

    @Test
    void claimNextRespectsUncertainRetryAndPersistsCanonicalCasSnapshots() {
        Fixture fixture = Fixture.create("claim-next");
        fixture.repository().create(fixture.pending());

        ReferenceAssemblyReleaseDispatchIntent running = fixture.repository()
                .claimNext(NOW, Duration.ofSeconds(5), "claim-one")
                .orElseThrow();
        assertEquals(ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING, running.status());
        assertEquals(1, running.attemptCount());
        assertEquals(1, running.version());

        ReferenceAssemblyReleaseDispatchIntent uncertain = running.uncertain(
                "claim-one",
                "ACTION_NOT_YET_WAITING",
                NOW.plusSeconds(1),
                NOW.plusSeconds(2));
        assertTrue(fixture.repository().compareAndSet(running, uncertain));
        assertTrue(fixture.repository()
                .claimNext(NOW.plusSeconds(1), Duration.ofSeconds(5), "claim-two")
                .isEmpty());

        ReferenceAssemblyReleaseDispatchIntent reclaimed = fixture.repository()
                .claimNext(NOW.plusSeconds(2), Duration.ofSeconds(5), "claim-two")
                .orElseThrow();
        assertEquals(2, reclaimed.attemptCount());
        assertEquals(3, reclaimed.version());
        assertEquals(Optional.of("claim-two"), reclaimed.claimToken());
        assertEquals(reclaimed, fixture.repository()
                .find(reclaimed.operationId()).orElseThrow());
    }

    @Test
    void claimApisRejectNonCanonicalMicrosecondTimesAndLeases() {
        Fixture fixture = Fixture.create("claim-microseconds");
        fixture.repository().create(fixture.pending());

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.repository()
                        .claimNext(
                                NOW.plusNanos(1),
                                Duration.ofSeconds(5),
                                "non-canonical-now"));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.repository()
                        .claimNext(NOW, Duration.ofNanos(1_001), "non-canonical-lease"));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.repository()
                        .claimExpiredRunning(
                                NOW.plusNanos(1),
                                Duration.ofSeconds(5),
                                "non-canonical-recovery"));
        assertEquals(
                fixture.pending(),
                fixture.repository().find(fixture.pending().operationId()).orElseThrow());
    }

    @Test
    void claimExpiredRunningWaitsForLeaseAndRejectsAStaleExpectedSnapshot() {
        Fixture fixture = Fixture.create("expired-running");
        fixture.repository().create(fixture.pending());
        ReferenceAssemblyReleaseDispatchIntent running = fixture.repository()
                .claimNext(NOW, Duration.ofSeconds(5), "claim-one")
                .orElseThrow();

        assertTrue(fixture.repository()
                .claimExpiredRunning(
                        NOW.plusSeconds(4), Duration.ofSeconds(5), "claim-too-early")
                .isEmpty());
        ReferenceAssemblyReleaseDispatchIntent recovered = fixture.repository()
                .claimExpiredRunning(
                        NOW.plusSeconds(5), Duration.ofSeconds(5), "claim-recovered")
                .orElseThrow();

        assertEquals(2, recovered.attemptCount());
        assertEquals(2, recovered.version());
        assertEquals(Optional.of("claim-recovered"), recovered.claimToken());
        assertFalse(fixture.repository().compareAndSet(
                running,
                running.claim(
                        "stale-claim", NOW.plusSeconds(5), Duration.ofSeconds(5))));
    }

    @Test
    void fabricatedExpectedSnapshotCannotDriveCasEvenWithSameOperationAndVersion() {
        Fixture fixture = Fixture.create("fabricated-expected");
        fixture.repository().create(fixture.pending());
        ReferenceAssemblyReleaseDispatchIntent fabricated = fixture.pending(
                fixture.input(), fixture.actionRunId(), NOW.plusSeconds(1));
        ReferenceAssemblyReleaseDispatchIntent forgedNext = fabricated.claim(
                "forged-claim", NOW.plusSeconds(2), Duration.ofSeconds(5));

        assertFalse(fixture.repository().compareAndSet(fabricated, forgedNext));
        assertEquals(
                fixture.pending(),
                fixture.repository().find(fixture.pending().operationId()).orElseThrow());
    }

    @Test
    void concurrentCasHasExactlyOneWinnerAcrossRepositoryInstances() throws Exception {
        Fixture fixture = Fixture.create("cas-race");
        fixture.repository().create(fixture.pending());
        var firstRepository = new JdbcReferenceAssemblyReleaseDispatchIntentRepository(
                fixture.assemblyFixture().certificationFixture().dataSource());
        var secondRepository = new JdbcReferenceAssemblyReleaseDispatchIntentRepository(
                fixture.assemblyFixture().certificationFixture().dataSource());
        ReferenceAssemblyReleaseDispatchIntent first = fixture.pending().claim(
                "claim-a", NOW.plusSeconds(1), Duration.ofSeconds(30));
        ReferenceAssemblyReleaseDispatchIntent second = fixture.pending().claim(
                "claim-b", NOW.plusSeconds(1), Duration.ofSeconds(30));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = List.of(
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return firstRepository.compareAndSet(fixture.pending(), first);
                    }),
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return secondRepository.compareAndSet(fixture.pending(), second);
                    }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int winners = 0;
            for (var result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    winners++;
                }
            }

            assertEquals(1, winners);
            ReferenceAssemblyReleaseDispatchIntent stored = fixture.repository()
                    .find(fixture.pending().operationId())
                    .orElseThrow();
            assertEquals(1, stored.version());
            assertEquals(ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING, stored.status());
            assertTrue(List.of("claim-a", "claim-b")
                    .contains(stored.claimToken().orElseThrow()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void terminalCasPreservesEveryImmutableOwnerLockAndLeavesNoClaimCandidate() {
        Fixture fixture = Fixture.create("terminal");
        fixture.repository().create(fixture.pending());
        ReferenceAssemblyReleaseDispatchIntent running = fixture.repository()
                .claimNext(NOW, Duration.ofSeconds(5), "terminal-owner")
                .orElseThrow();
        ReferenceAssemblyReleaseDispatchIntent completed = running.complete(
                "terminal-owner", "RELEASE_DISPATCH_COMPLETED", NOW.plusSeconds(1));

        assertTrue(fixture.repository().compareAndSet(running, completed));

        ReferenceAssemblyReleaseDispatchIntent stored = fixture.repository()
                .find(completed.operationId())
                .orElseThrow();
        assertEquals(completed, stored);
        assertTrue(fixture.pending().sameImmutableIdentity(stored));
        assertTrue(stored.status().isTerminal());
        assertTrue(fixture.repository()
                .claimNext(NOW.plusSeconds(2), Duration.ofSeconds(5), "late-claim")
                .isEmpty());
        assertTrue(fixture.repository()
                .claimExpiredRunning(
                        NOW.plusSeconds(10), Duration.ofSeconds(5), "late-recovery")
                .isEmpty());
    }

    private record Fixture(
            String suffix,
            JdbcReferenceAssemblyRepositoryTest.Fixture assemblyFixture,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            ReferenceAssemblyReleaseDispatchIntent pending,
            JdbcReferenceAssemblyReleaseDispatchIntentRepository repository) {

        static Fixture create(String suffix) {
            JdbcReferenceAssemblyRepositoryTest.Fixture assemblyFixture =
                    JdbcReferenceAssemblyRepositoryTest.Fixture.create(
                            "ri-" + suffix);
            assemblyFixture.repository().create(assemblyFixture.requested());
            ReferenceAssemblyReleaseInput input = new ReferenceAssemblyReleaseInput(
                    assemblyFixture.requested().referenceAssemblyId(),
                    assemblyFixture.assemblyManifest().hash(),
                    assemblyFixture.inspectionReport().hash(),
                    new DecisionPointId("release-intent-decision-" + suffix),
                    hash('9'),
                    7);
            String actionRunId = assemblyFixture.componentCertification()
                    .actionRunId()
                    .orElseThrow();
            ReferenceAssemblyReleaseDispatchIntent pending = pending(
                    assemblyFixture.tenant(), input, actionRunId, NOW);
            return new Fixture(
                    suffix,
                    assemblyFixture,
                    input,
                    actionRunId,
                    pending,
                    new JdbcReferenceAssemblyReleaseDispatchIntentRepository(
                            assemblyFixture.certificationFixture().dataSource()));
        }

        io.github.flowerjvm.factory.contracts.ids.TenantId tenant() {
            return assemblyFixture.tenant();
        }

        ReferenceAssemblyReleaseDispatchIntent pending(
                ReferenceAssemblyReleaseInput exactInput,
                String exactActionRunId,
                Instant createdAt) {
            return pending(tenant(), exactInput, exactActionRunId, createdAt);
        }

        String createSecondActionOwner() {
            JdbcCertificationRepositoryTest.Fixture second =
                    JdbcCertificationRepositoryTest.Fixture.create(
                            assemblyFixture.certificationFixture().dataSource(),
                            "rra-" + suffix);
            return second.certified().actionRunId().orElseThrow();
        }

        private static ReferenceAssemblyReleaseDispatchIntent pending(
                io.github.flowerjvm.factory.contracts.ids.TenantId tenant,
                ReferenceAssemblyReleaseInput input,
                String actionRunId,
                Instant createdAt) {
            String operationId = ReferenceAssemblyReleaseDispatchOperationIds.derive(
                    tenant, input);
            return ReferenceAssemblyReleaseDispatchIntent.pending(
                    operationId,
                    tenant,
                    input,
                    actionRunId,
                    ReferenceAssemblyReleaseAttemptTokens.hash(
                            "attempt-" + actionRunId),
                    createdAt.plusSeconds(300),
                    createdAt);
        }
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }
}
