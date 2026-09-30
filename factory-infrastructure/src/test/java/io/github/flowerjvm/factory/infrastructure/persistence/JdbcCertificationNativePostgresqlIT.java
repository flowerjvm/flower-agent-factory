package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicy;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationAttemptTokens;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntent;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntentStatus;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchOperationIds;
import io.github.flowerjvm.factory.application.certification.CertificationIssuanceTransaction;
import io.github.flowerjvm.factory.application.certification.CertificationRequestDisposition;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcCertificationNativePostgresqlIT {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void nativeRequestRaceConvergesAndRetryPreservesDispatchLockOrder() throws Exception {
        NativeRequestFixture fixture = requestFixture("native-request-race");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var outcomes = List.of(
                    executor.submit(fixture::request),
                    executor.submit(fixture::request));
            var first = outcomes.get(0).get(15, TimeUnit.SECONDS);
            var second = outcomes.get(1).get(15, TimeUnit.SECONDS);
            assertEquals(
                    Set.of(CertificationRequestDisposition.CREATED, CertificationRequestDisposition.EXISTING_EXACT),
                    Set.of(first.disposition(), second.disposition()));
            assertEquals(first.certification(), second.certification());
            assertEquals(first.buildSession(), second.buildSession());
            assertEquals(1L, countCertification(fixture));

            Certification retryIdentity = Certification.requested(
                    first.certification().certificationId(),
                    first.certification().inputLock(),
                    first.certification().inputLockArtifact(),
                    first.certification().createdAt().plusSeconds(1));
            try (Connection dispatch = fixture.dataSource().getConnection()) {
                dispatch.setAutoCommit(false);
                try (var setting = dispatch.createStatement()) {
                    setting.execute("SET LOCAL lock_timeout = '1s'");
                }
                lockCertification(dispatch, fixture);
                var retry = executor.submit(() -> fixture.transaction().ensureRequested(
                        first.buildSession(), first.buildSession(), retryIdentity));

                assertThrows(TimeoutException.class, () -> retry.get(250, TimeUnit.MILLISECONDS));
                lockSession(dispatch, fixture);
                dispatch.commit();

                assertEquals(
                        CertificationRequestDisposition.EXISTING_EXACT,
                        retry.get(10, TimeUnit.SECONDS).disposition());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nativeDispatchPrepareLocksExactOwnersAndConvergesOnOnePendingIntent() {
        PGSimpleDataSource dataSource = dataSource();
        JdbcCertificationDispatchTransactionTest.Fixture fixture =
                JdbcCertificationDispatchTransactionTest.Fixture.create(
                        dataSource, "native-dispatch-prepare");

        CertificationDispatchIntent first = fixture.prepare();
        CertificationDispatchIntent retry = fixture.prepare();

        assertEquals(first, retry);
        assertEquals(CertificationDispatchIntentStatus.PENDING, first.status());
        assertEquals(1, fixture.intentCount());
        assertEquals(fixture.certification().requested(), fixture.certification().certifications()
                .find(fixture.certification().tenant(), fixture.certification().certificationId())
                .orElseThrow());
    }

    @Test
    void nativeDispatchCasHasOneWinnerAndTenantBoundForeignKeysRejectMutation()
            throws Exception {
        NativeRequestFixture fixture = requestFixture("native-dispatch-cas-fk");
        fixture.request();
        String actionRunId = "certification-action-native-dispatch-cas-fk";
        Instant createdAt = fixture.certification().requested().createdAt().plusSeconds(1);
        CertificationDispatchIntent pending = CertificationDispatchIntent.pending(
                "native-certification-dispatch-operation",
                fixture.certification().tenant(),
                fixture.certification().certificationId(),
                fixture.certification().requested().inputLockArtifact().hash(),
                fixture.certification().requested().version(),
                actionRunId,
                "e".repeat(64),
                createdAt.plusSeconds(120),
                createdAt);
        var repository = new JdbcCertificationDispatchIntentRepository(fixture.dataSource());
        repository.create(pending);
        var firstRepository = new JdbcCertificationDispatchIntentRepository(fixture.dataSource());
        var secondRepository = new JdbcCertificationDispatchIntentRepository(fixture.dataSource());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = List.of(
                    executor.submit(() -> firstRepository.compareAndSet(
                            pending, pending.claim("claim-a", createdAt.plusSeconds(1), Duration.ofSeconds(30)))),
                    executor.submit(() -> secondRepository.compareAndSet(
                            pending, pending.claim("claim-b", createdAt.plusSeconds(1), Duration.ofSeconds(30)))));
            int winners = 0;
            for (var result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        String crossTenantRun = "native-certification-cross-tenant-action";
        createActionRun(fixture.dataSource(), new TenantId("native-other-tenant"), crossTenantRun);
        assertThrows(SQLException.class, () -> updateIntentActionRun(
                fixture.dataSource(), pending.operationId(), crossTenantRun));
        assertThrows(SQLException.class, () -> updateIntentTenant(
                fixture.dataSource(), pending.operationId(), "native-other-tenant"));
    }

    @Test
    void nativeBuildSessionCertificationReferenceCannotCrossItsExactSession() throws Exception {
        NativeRequestFixture owner = requestFixture("native-current-cert-owner");
        owner.request();
        JdbcCertificationRepositoryTest.Fixture other =
                JdbcCertificationRepositoryTest.Fixture.create(
                        owner.dataSource(), "native-current-cert-other");
        other.certifications().create(other.requested());

        try (Connection connection = owner.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE factory_build_session SET current_certification_id = ?
                        WHERE tenant_id = ? AND build_session_id = ?
                        """)) {
            statement.setString(1, other.certificationId().value());
            statement.setString(2, owner.certification().tenant().value());
            statement.setString(3, owner.ready().buildSessionId().value());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void nativeIssuanceWaitsBehindCancellationAndExactLiveCommitRemainsIdempotent()
            throws Exception {
        NativeIssuanceFixture cancelled = issuanceFixture("native-issuance-cancel-race");
        CountDownLatch issuanceStarted = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (Connection cancellation = cancelled.request().dataSource().getConnection()) {
            cancellation.setAutoCommit(false);
            lockSession(cancellation, cancelled.request());
            var result = executor.submit(() -> {
                issuanceStarted.countDown();
                try {
                    cancelled.transaction().commit(
                            cancelled.intent(), cancelled.requested(), cancelled.proposed());
                    return null;
                } catch (Throwable denied) {
                    return denied;
                }
            });
            assertTrue(issuanceStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> result.get(250, TimeUnit.MILLISECONDS));

            cancelLockedSession(cancellation, cancelled);
            cancellation.commit();

            assertInstanceOf(IllegalStateException.class, result.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(
                CertificationStatus.REQUESTED,
                cancelled.request().certification().certifications()
                        .find(
                                cancelled.request().certification().tenant(),
                                cancelled.request().certification().certificationId())
                        .orElseThrow()
                        .status());
        assertEquals(
                cancelled.requested(),
                cancelled.request().certification().certifications()
                        .find(
                                cancelled.request().certification().tenant(),
                                cancelled.request().certification().certificationId())
                        .orElseThrow());

        NativeIssuanceFixture live = issuanceFixture("native-issuance-live-retry");
        CertificationIssuanceTransaction.CertificationIssuanceCommit first =
                live.transaction().commit(live.intent(), live.requested(), live.proposed());
        CertificationIssuanceTransaction.CertificationIssuanceCommit retry =
                live.transaction().commit(live.intent(), live.requested(), live.proposed());

        assertTrue(first.committedNow());
        assertFalse(retry.committedNow());
        assertEquals(live.proposed(), first.certification());
        assertEquals(live.proposed(), retry.certification());
        assertEquals(live.proposed(), live.request().certification().certifications()
                .find(
                        live.request().certification().tenant(),
                        live.request().certification().certificationId())
                .orElseThrow());
    }

    private static NativeRequestFixture requestFixture(String suffix) {
        PGSimpleDataSource dataSource = dataSource();
        JdbcCertificationRepositoryTest.Fixture certification =
                JdbcCertificationRepositoryTest.Fixture.create(dataSource, suffix);
        makeCandidateReady(certification);
        var sessions = new JdbcBuildSessionRepository(dataSource);
        BuildSession ready = sessions.find(
                certification.tenant(), certification.inputLock().buildSessionId()).orElseThrow();
        AgentPackCertificationPolicy policy = policy(certification.inputLock());
        return new NativeRequestFixture(
                dataSource,
                certification,
                ready,
                ready.beginAgentPackCertification(certification.requested().createdAt()),
                new JdbcCertificationRequestTransaction(dataSource, policy));
    }

    private static NativeIssuanceFixture issuanceFixture(String suffix) {
        NativeRequestFixture request = requestFixture(suffix);
        request.request();
        Certification requested = request.certification().requested();
        String actionRunId = "certification-action-" + suffix;
        Certification proposed = requested.certify(
                request.certification().manifestLock(),
                request.certification().evidenceLock(),
                actionRunId,
                requested.createdAt().plusSeconds(1),
                Optional.empty());
        CertificationIssueInput input = new CertificationIssueInput(
                requested.certificationId(),
                requested.inputLockArtifact().hash(),
                requested.version());
        CertificationDispatchIntent intent = CertificationDispatchIntent.pending(
                        CertificationDispatchOperationIds.derive(
                                request.certification().tenant(), input),
                        request.certification().tenant(),
                        requested.certificationId(),
                        input.inputLockManifestHash(),
                        input.expectedCertificationVersion(),
                        actionRunId,
                        CertificationAttemptTokens.hash("attempt-" + suffix),
                        request.certifying().deadlineAt(),
                        requested.createdAt())
                .claim(
                        "claim-" + suffix,
                        requested.createdAt(),
                        Duration.ofMinutes(5));
        return new NativeIssuanceFixture(
                request,
                requested,
                proposed,
                intent,
                new JdbcCertificationIssuanceTransaction(
                        request.dataSource(),
                        request.certification().codec(),
                        new JacksonWorkerProtocolArtifactDecoder(new ObjectMapper())));
    }

    private static AgentPackCertificationPolicy policy(CertificationInputLock input) {
        return new AgentPackCertificationPolicy(
                input.productContractBundle(),
                input.gateProfile(),
                input.verificationFixtureSetHash(),
                input.sourceLockAlgorithmId(),
                input.certificationProfile(),
                input.factoryVersion(),
                input.flowerVersion(),
                input.actionRuntimeVersion());
    }

    private static void makeCandidateReady(
            JdbcCertificationRepositoryTest.Fixture certification) {
        Instant updatedAt = certification.requested().createdAt().minusSeconds(1);
        Instant deadlineAt = certification.requested().createdAt().plusSeconds(300);
        try (Connection connection = certification.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE factory_build_session SET
                            status = 'CANDIDATE_READY_FOR_RELEASE',
                            current_phase = 'human-release-review',
                            current_candidate_id = ?, current_candidate_hash = ?,
                            deadline_at = ?, updated_at = ?, version = version + 1
                        WHERE tenant_id = ? AND build_session_id = ?
                        """)) {
            statement.setString(1, certification.inputLock().candidateId().value());
            statement.setString(2, certification.inputLock().candidateHash().sha256());
            statement.setTimestamp(3, Timestamp.from(deadlineAt));
            statement.setTimestamp(4, Timestamp.from(updatedAt));
            statement.setString(5, certification.tenant().value());
            statement.setString(6, certification.inputLock().buildSessionId().value());
            assertEquals(1, statement.executeUpdate());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void lockCertification(
            Connection connection,
            NativeRequestFixture fixture) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT certification_id FROM factory_certification
                WHERE tenant_id = ? AND certification_id = ? FOR UPDATE
                """)) {
            statement.setString(1, fixture.certification().tenant().value());
            statement.setString(2, fixture.certification().certificationId().value());
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
            }
        }
    }

    private static void lockSession(
            Connection connection,
            NativeRequestFixture fixture) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT build_session_id FROM factory_build_session
                WHERE tenant_id = ? AND build_session_id = ? FOR UPDATE
                """)) {
            statement.setString(1, fixture.certification().tenant().value());
            statement.setString(2, fixture.ready().buildSessionId().value());
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
            }
        }
    }

    private static void cancelLockedSession(
            Connection connection,
            NativeIssuanceFixture fixture) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE factory_build_session SET
                    status = 'CANCELLING', cancellation_requested_at = ?,
                    version = version + 1, updated_at = ?
                WHERE tenant_id = ? AND build_session_id = ?
                """)) {
            Timestamp cancelledAt = Timestamp.from(fixture.proposed().updatedAt());
            statement.setTimestamp(1, cancelledAt);
            statement.setTimestamp(2, cancelledAt);
            statement.setString(3, fixture.request().certification().tenant().value());
            statement.setString(4, fixture.request().ready().buildSessionId().value());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static long countCertification(NativeRequestFixture fixture) throws SQLException {
        try (Connection connection = fixture.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*) FROM factory_certification
                        WHERE tenant_id = ? AND certification_id = ?
                        """)) {
            statement.setString(1, fixture.certification().tenant().value());
            statement.setString(2, fixture.certification().certificationId().value());
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getLong(1);
            }
        }
    }

    private static void createActionRun(
            PGSimpleDataSource dataSource,
            TenantId tenantId,
            String runId) {
        ActionProposal proposal = ActionProposal.builder("factory.test.cross-tenant")
                .proposalId("proposal-" + runId)
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-test")
                .input(Map.of("run", runId))
                .idempotencyKey("key-" + runId)
                .build();
        ExecutionContext context = new ExecutionContext(
                tenantId.value(), "factory-test", runId, "trace-" + runId, Map.of());
        new JdbcRunStore(dataSource, new ObjectMapper()).create(ActionRun.requested(proposal, context));
    }

    private static void updateIntentActionRun(
            PGSimpleDataSource dataSource,
            String operationId,
            String actionRunId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE factory_certification_dispatch_intent
                        SET action_run_id = ? WHERE operation_id = ?
                        """)) {
            statement.setString(1, actionRunId);
            statement.setString(2, operationId);
            statement.executeUpdate();
        }
    }

    private static void updateIntentTenant(
            PGSimpleDataSource dataSource,
            String operationId,
            String tenantId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE factory_certification_dispatch_intent
                        SET tenant_id = ? WHERE operation_id = ?
                        """)) {
            statement.setString(1, tenantId);
            statement.setString(2, operationId);
            statement.executeUpdate();
        }
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private record NativeRequestFixture(
            PGSimpleDataSource dataSource,
            JdbcCertificationRepositoryTest.Fixture certification,
            BuildSession ready,
            BuildSession certifying,
            JdbcCertificationRequestTransaction transaction) {
        io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequestOutcome request() {
            return transaction.ensureRequested(ready, certifying, certification.requested());
        }
    }

    private record NativeIssuanceFixture(
            NativeRequestFixture request,
            Certification requested,
            Certification proposed,
            CertificationDispatchIntent intent,
            JdbcCertificationIssuanceTransaction transaction) {}
}
