package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shared H2/PostgreSQL assertions for real release-authority connection races. */
final class JdbcReferenceAssemblyReleaseConcurrencyAssertions {
    private static final Instant RELEASE_AT = Instant.parse("2026-09-01T00:00:21Z");
    private static final Instant MUTATION_AT = Instant.parse("2026-09-01T00:00:22Z");
    private static final long TIMEOUT_SECONDS = 15;

    private JdbcReferenceAssemblyReleaseConcurrencyAssertions() {}

    static void assertBuildSessionCancellationRace(
            JdbcReferenceAssemblyReleaseTransactionTest.Fixture fixture) throws Exception {
        BuildSession stale = fixture.session();
        BuildSession cancelling = stale.requestCancellation(MUTATION_AT);

        RaceOutcome outcome = race(fixture, connection ->
                fixture.sessions().compareAndSet(connection, stale, cancelling));

        assertExactlyOneReleaseOrMutation(outcome);
        ReferenceAssembly assembly = canonicalAssembly(fixture);
        BuildSession session = fixture.sessions()
                .find(fixture.tenant(), stale.buildSessionId())
                .orElseThrow();
        if (outcome.releaseCommitted()) {
            assertEquals(fixture.proposed(), assembly);
            assertEquals(BuildSessionStatus.RUNNING, session.status());
            assertEquals(stale.version() + 1, session.version());
            assertTrue(session.cancellationRequestedAt().isEmpty());
        } else {
            assertNotNull(outcome.releaseRejection());
            assertEquals(fixture.expected(), assembly);
            assertEquals(cancelling, session);
        }
    }

    static void assertActionCancellationRace(
            JdbcReferenceAssemblyReleaseTransactionTest.Fixture fixture) throws Exception {
        JdbcRunStore runs = new JdbcRunStore(fixture.dataSource(), new ObjectMapper());
        ActionRun stale = runs.find(fixture.actionRunId()).orElseThrow();

        RaceOutcome outcome = race(fixture, connection ->
                cancelWaitingAction(connection, stale));

        assertExactlyOneReleaseOrMutation(outcome);
        ReferenceAssembly assembly = canonicalAssembly(fixture);
        ActionRun action = runs.find(fixture.actionRunId()).orElseThrow();
        assertEquals(stale.version() + 1, action.version());
        if (outcome.releaseCommitted()) {
            assertEquals(fixture.proposed(), assembly);
            assertEquals(ActionRunStatus.WAITING_EXTERNAL, action.status());
        } else {
            assertNotNull(outcome.releaseRejection());
            assertEquals(fixture.expected(), assembly);
            assertEquals(ActionRunStatus.CANCELLED, action.status());
        }
    }

    static void assertCertificationRevocationRace(
            JdbcReferenceAssemblyReleaseTransactionTest.Fixture fixture) throws Exception {
        Certification certified = fixture.component();
        Certification revoked = certified.revoke(
                "COMPONENT_REVOKED_DURING_RELEASE_RACE", MUTATION_AT);

        RaceOutcome outcome = race(fixture, connection ->
                fixture.certifications().compareAndSet(connection, certified, revoked));

        assertTrue(outcome.mutationCommitted(),
                "revocation must eventually commit because release does not fence future revocation");
        Certification canonical = fixture.certifications()
                .find(fixture.tenant(), certified.certificationId())
                .orElseThrow();
        assertEquals(CertificationStatus.REVOKED, canonical.status());
        assertEquals(revoked, canonical);

        ReferenceAssembly assembly = canonicalAssembly(fixture);
        if (outcome.releaseCommitted()) {
            // Release observed CERTIFIED under its lock; the later revocation quarantines it at
            // the current-status consumer read gate.
            assertEquals(fixture.proposed(), assembly);
            assertEquals(ReferenceAssemblyStatus.RELEASED, assembly.status());
        } else {
            assertNotNull(outcome.releaseRejection());
            assertEquals(fixture.expected(), assembly);
            assertEquals(ReferenceAssemblyStatus.INSPECTED, assembly.status());
        }
    }

    private static RaceOutcome race(
            JdbcReferenceAssemblyReleaseTransactionTest.Fixture fixture,
            ConnectionMutation mutation) throws Exception {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(mutation, "mutation");
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService contenders = Executors.newFixedThreadPool(2);
        Future<ReleaseAttempt> release = contenders.submit(() -> {
            await(start);
            try {
                boolean committed = fixture.transactionAt(RELEASE_AT)
                        .commit(fixture.intent(), fixture.expected(), fixture.proposed())
                        .committedNow();
                return new ReleaseAttempt(committed, null);
            } catch (RuntimeException rejected) {
                return new ReleaseAttempt(false, rejected);
            }
        });
        Future<Boolean> competingMutation = contenders.submit(() -> {
            try (Connection connection = fixture.dataSource().getConnection()) {
                connection.setAutoCommit(false);
                await(start);
                try {
                    boolean committed = mutation.apply(connection);
                    connection.commit();
                    return committed;
                } catch (Exception failure) {
                    rollback(connection, failure);
                    throw failure;
                }
            }
        });

        try {
            await(start);
            ReleaseAttempt releaseAttempt = get(release);
            boolean mutationCommitted = get(competingMutation);
            return new RaceOutcome(
                    releaseAttempt.committed(),
                    mutationCommitted,
                    releaseAttempt.rejection());
        } finally {
            contenders.shutdownNow();
            assertTrue(
                    contenders.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "release race executor did not terminate within the explicit timeout");
        }
    }

    private static boolean cancelWaitingAction(Connection connection, ActionRun expected)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE action_run
                SET status = 'CANCELLED',
                    result_status = 'CANCELLED',
                    result_code = 'FACTORY_CANCELLATION_REQUESTED',
                    result_message = 'release Action cancellation won the JDBC race',
                    result_output_json = '{}',
                    result_retry_disposition = 'NEVER',
                    version = ?,
                    updated_at = ?
                WHERE run_id = ?
                  AND version = ?
                  AND status = 'WAITING_EXTERNAL'
                  AND attempt_token = ?
                """)) {
            statement.setLong(1, Math.addExact(expected.version(), 1));
            statement.setLong(2, MUTATION_AT.toEpochMilli());
            statement.setString(3, expected.runId());
            statement.setLong(4, expected.version());
            statement.setString(5, expected.attemptToken());
            return statement.executeUpdate() == 1;
        }
    }

    private static ReferenceAssembly canonicalAssembly(
            JdbcReferenceAssemblyReleaseTransactionTest.Fixture fixture) {
        return fixture.assemblies()
                .find(fixture.tenant(), fixture.expected().referenceAssemblyId())
                .orElseThrow();
    }

    private static void assertExactlyOneReleaseOrMutation(RaceOutcome outcome) {
        assertTrue(
                outcome.releaseCommitted() ^ outcome.mutationCommitted(),
                "exactly one mutually exclusive release/cancellation CAS may win");
        if (outcome.releaseCommitted()) {
            assertFalse(outcome.mutationCommitted());
        }
    }

    private static void await(CyclicBarrier barrier)
            throws InterruptedException, BrokenBarrierException, TimeoutException {
        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static <T> T get(Future<T> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    private interface ConnectionMutation {
        boolean apply(Connection connection) throws Exception;
    }

    private record ReleaseAttempt(boolean committed, RuntimeException rejection) {}

    private record RaceOutcome(
            boolean releaseCommitted,
            boolean mutationCommitted,
            RuntimeException releaseRejection) {}
}
