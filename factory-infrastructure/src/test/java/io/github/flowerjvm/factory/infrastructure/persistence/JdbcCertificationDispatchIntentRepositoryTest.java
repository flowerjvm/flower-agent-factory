package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntent;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntentStatus;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JdbcCertificationDispatchIntentRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:10Z");

    @Test
    void roundTripsTenantScopedIntentAndDatabaseRejectsDuplicateOwnersAndAttempts()
            throws Exception {
        Fixture fixture = fixture("repository-roundtrip");
        CertificationDispatchIntent pending = fixture.pending("operation-roundtrip", 0, fixture.actionRunId());

        fixture.repository().create(pending);

        assertEquals(pending, fixture.repository().find(pending.operationId()).orElseThrow());
        assertEquals(pending, fixture.repository()
                .findLatest(fixture.fixture().tenant(), fixture.fixture().certificationId())
                .orElseThrow());
        assertTrue(fixture.repository()
                .findLatest(new TenantId("other-tenant"), fixture.fixture().certificationId())
                .isEmpty());

        CertificationDispatchIntent duplicateActionOwner = fixture.pending(
                "operation-duplicate-action", 1, fixture.actionRunId());
        assertThrows(DuplicateLedgerRecordException.class,
                () -> fixture.repository().create(duplicateActionOwner));

        CertificationDispatchIntent duplicateLogicalAttempt = fixture.pending(
                "operation-duplicate-attempt",
                0,
                fixture.fixture().inputLock().verificationActionRunId());
        assertThrows(DuplicateLedgerRecordException.class,
                () -> fixture.repository().create(duplicateLogicalAttempt));

        try (Connection connection = fixture.fixture().dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE factory_certification_dispatch_intent
                        SET status = 'RUNNING'
                        WHERE operation_id = ?
                        """)) {
            statement.setString(1, pending.operationId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void claimPathsRespectUncertainGraceAndExpiredRunningReconciliation() {
        Fixture fixture = fixture("repository-claims");
        CertificationDispatchIntent pending = fixture.pending("operation-claims", 0, fixture.actionRunId());
        fixture.repository().create(pending);

        CertificationDispatchIntent first = fixture.repository()
                .claimNext(NOW.plusSeconds(1), Duration.ofSeconds(5), "claim-first")
                .orElseThrow();
        assertEquals(CertificationDispatchIntentStatus.RUNNING, first.status());
        CertificationDispatchIntent uncertain = first.uncertain(
                "claim-first", "CERTIFICATION_ACTION_PARK_UNCERTAIN",
                NOW.plusSeconds(2), NOW.plusSeconds(10));
        assertTrue(fixture.repository().compareAndSet(first, uncertain));

        assertTrue(fixture.repository()
                .claimNext(NOW.plusSeconds(9), Duration.ofSeconds(5), "claim-too-early")
                .isEmpty());
        CertificationDispatchIntent reclaimed = fixture.repository()
                .claimNext(NOW.plusSeconds(10), Duration.ofSeconds(5), "claim-second")
                .orElseThrow();
        assertEquals(2, reclaimed.attemptCount());
        assertTrue(fixture.repository()
                .claimExpiredRunning(NOW.plusSeconds(14), Duration.ofSeconds(5), "claim-not-expired")
                .isEmpty());
        CertificationDispatchIntent reconciled = fixture.repository()
                .claimExpiredRunning(NOW.plusSeconds(15), Duration.ofSeconds(5), "claim-recovery")
                .orElseThrow();
        assertEquals(3, reconciled.attemptCount());
        assertEquals("claim-recovery", reconciled.claimToken().orElseThrow());
    }

    @Test
    void concurrentCasHasExactlyOneWinnerAcrossRepositoryInstances() throws Exception {
        Fixture fixture = fixture("repository-cas-race");
        CertificationDispatchIntent pending = fixture.pending("operation-cas-race", 0, fixture.actionRunId());
        fixture.repository().create(pending);
        var firstRepository = new JdbcCertificationDispatchIntentRepository(fixture.fixture().dataSource());
        var secondRepository = new JdbcCertificationDispatchIntentRepository(fixture.fixture().dataSource());
        CertificationDispatchIntent first = pending.claim(
                "claim-a", NOW.plusSeconds(1), Duration.ofSeconds(30));
        CertificationDispatchIntent second = pending.claim(
                "claim-b", NOW.plusSeconds(1), Duration.ofSeconds(30));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = List.of(
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return firstRepository.compareAndSet(pending, first);
                    }),
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return secondRepository.compareAndSet(pending, second);
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
            CertificationDispatchIntent stored = fixture.repository().find(pending.operationId()).orElseThrow();
            assertEquals(1, stored.version());
            assertEquals(CertificationDispatchIntentStatus.RUNNING, stored.status());
            assertTrue(SetSupport.oneOf(stored.claimToken().orElseThrow(), "claim-a", "claim-b"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Fixture fixture(String suffix) {
        JdbcCertificationRepositoryTest.Fixture certification =
                JdbcCertificationRepositoryTest.Fixture.create(suffix);
        certification.certifications().create(certification.requested());
        return new Fixture(
                certification,
                new JdbcCertificationDispatchIntentRepository(certification.dataSource()),
                "certification-action-" + suffix);
    }

    private record Fixture(
            JdbcCertificationRepositoryTest.Fixture fixture,
            JdbcCertificationDispatchIntentRepository repository,
            String actionRunId) {
        CertificationDispatchIntent pending(
                String operationId, long expectedVersion, String ownerActionRunId) {
            return CertificationDispatchIntent.pending(
                    operationId,
                    fixture.tenant(),
                    fixture.certificationId(),
                    fixture.requested().inputLockArtifact().hash(),
                    expectedVersion,
                    ownerActionRunId,
                    "e".repeat(64),
                    NOW.plusSeconds(300),
                    NOW);
        }
    }

    private static final class SetSupport {
        private SetSupport() {}

        static boolean oneOf(String value, String first, String second) {
            return first.equals(value) || second.equals(value);
        }
    }
}
