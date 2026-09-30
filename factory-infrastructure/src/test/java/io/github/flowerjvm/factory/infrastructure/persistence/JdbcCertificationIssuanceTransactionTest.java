package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationAttemptTokens;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntent;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchOperationIds;
import io.github.flowerjvm.factory.application.certification.CertificationIssuanceTransaction;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JdbcCertificationIssuanceTransactionTest {
    private static final Instant REQUESTED_AT = Instant.parse("2026-09-01T00:00:05Z");
    private static final Instant CERTIFIED_AT = REQUESTED_AT.plusSeconds(1);
    private static final Instant DEADLINE = REQUESTED_AT.plusSeconds(3600);

    @Test
    void exactLiveSessionCommitsOnceAndExactRetryObservesTheCanonicalResult() {
        Fixture fixture = Fixture.create("issuance-commit");

        CertificationIssuanceTransaction.CertificationIssuanceCommit first =
                fixture.transaction.commit(fixture.intent, fixture.requested, fixture.proposed);
        CertificationIssuanceTransaction.CertificationIssuanceCommit retry =
                fixture.transaction.commit(fixture.intent, fixture.requested, fixture.proposed);

        assertTrue(first.committedNow());
        assertFalse(retry.committedNow());
        assertEquals(fixture.proposed, first.certification());
        assertEquals(fixture.proposed, retry.certification());
        assertEquals(fixture.proposed, fixture.certifications
                .find(fixture.certification.tenant(), fixture.certification.certificationId())
                .orElseThrow());
    }

    @Test
    void cancellationHoldingTheSessionLockWinsBeforeFinalRecheckAndNeverCertifies()
            throws Exception {
        Fixture fixture = Fixture.create("issuance-cancel-race");
        CountDownLatch taskStarted = new CountDownLatch(1);

        try (Connection cancellation = fixture.certification.dataSource().getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            cancellation.setAutoCommit(false);
            lockSession(cancellation, fixture);
            var result = executor.submit(() -> {
                taskStarted.countDown();
                try {
                    fixture.transaction.commit(fixture.intent, fixture.requested, fixture.proposed);
                    return null;
                } catch (Throwable denied) {
                    return denied;
                }
            });
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

            cancelLockedSession(cancellation, fixture);
            cancellation.commit();

            Throwable denied = result.get(10, TimeUnit.SECONDS);
            assertInstanceOf(IllegalStateException.class, denied);
        }

        Certification canonical = fixture.certifications
                .find(fixture.certification.tenant(), fixture.certification.certificationId())
                .orElseThrow();
        assertEquals(CertificationStatus.REQUESTED, canonical.status());
        assertEquals(fixture.requested, canonical);
    }

    private static void lockSession(Connection connection, Fixture fixture) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT build_session_id FROM factory_build_session
                WHERE tenant_id = ? AND build_session_id = ?
                FOR UPDATE
                """)) {
            statement.setString(1, fixture.certification.tenant().value());
            statement.setString(2, fixture.certification.inputLock().buildSessionId().value());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
            }
        }
    }

    private static void cancelLockedSession(Connection connection, Fixture fixture) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE factory_build_session SET
                    status = 'CANCELLING', cancellation_requested_at = ?,
                    version = version + 1, updated_at = ?
                WHERE tenant_id = ? AND build_session_id = ?
                """)) {
            Timestamp cancelledAt = Timestamp.from(CERTIFIED_AT);
            statement.setTimestamp(1, cancelledAt);
            statement.setTimestamp(2, cancelledAt);
            statement.setString(3, fixture.certification.tenant().value());
            statement.setString(4, fixture.certification.inputLock().buildSessionId().value());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private record Fixture(
            JdbcCertificationRepositoryTest.Fixture certification,
            JdbcCertificationRepository certifications,
            Certification requested,
            Certification proposed,
            CertificationDispatchIntent intent,
            JdbcCertificationIssuanceTransaction transaction) {

        static Fixture create(String suffix) {
            JdbcCertificationRepositoryTest.Fixture certification =
                    JdbcCertificationRepositoryTest.Fixture.create(suffix);
            certification.certifications().create(certification.requested());
            makeSessionCertifiable(certification);

            Certification requested = certification.requested();
            String actionRunId = "certification-action-" + suffix;
            Certification proposed = requested.certify(
                    certification.manifestLock(),
                    certification.evidenceLock(),
                    actionRunId,
                    CERTIFIED_AT,
                    Optional.empty());
            CertificationIssueInput input = new CertificationIssueInput(
                    certification.certificationId(),
                    requested.inputLockArtifact().hash(),
                    requested.version());
            CertificationDispatchIntent intent = CertificationDispatchIntent.pending(
                            CertificationDispatchOperationIds.derive(certification.tenant(), input),
                            certification.tenant(),
                            certification.certificationId(),
                            input.inputLockManifestHash(),
                            input.expectedCertificationVersion(),
                            actionRunId,
                            CertificationAttemptTokens.hash("attempt-" + suffix),
                            DEADLINE,
                            REQUESTED_AT)
                    .claim("claim-" + suffix, REQUESTED_AT, Duration.ofMinutes(5));
            var workerDecoder = new JacksonWorkerProtocolArtifactDecoder(new ObjectMapper());
            return new Fixture(
                    certification,
                    certification.certifications(),
                    requested,
                    proposed,
                    intent,
                    new JdbcCertificationIssuanceTransaction(
                            certification.dataSource(), certification.codec(), workerDecoder));
        }

        private static void makeSessionCertifiable(
                JdbcCertificationRepositoryTest.Fixture certification) {
            try (Connection connection = certification.dataSource().getConnection();
                    PreparedStatement statement = connection.prepareStatement("""
                            UPDATE factory_build_session SET
                                status = 'CERTIFYING', current_phase = 'certify',
                                current_candidate_id = ?, current_candidate_hash = ?,
                                deadline_at = ?, updated_at = ?
                            WHERE tenant_id = ? AND build_session_id = ?
                            """)) {
                statement.setString(1, certification.inputLock().candidateId().value());
                statement.setString(2, certification.inputLock().candidateHash().sha256());
                statement.setTimestamp(3, Timestamp.from(DEADLINE));
                statement.setTimestamp(4, Timestamp.from(REQUESTED_AT));
                statement.setString(5, certification.tenant().value());
                statement.setString(6, certification.inputLock().buildSessionId().value());
                assertEquals(1, statement.executeUpdate());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
