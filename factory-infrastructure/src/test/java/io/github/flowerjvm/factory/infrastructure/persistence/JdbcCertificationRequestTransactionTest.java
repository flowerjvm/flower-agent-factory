package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicy;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicyCatalog;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequestException;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequestOutcome;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationRequestDisposition;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcCertificationRequestTransactionTest {

    @Test
    void maintenanceCatalogAdmitsExactGenerationUsingOnlyTheTransactionConnection() {
        Fixture fixture = Fixture.createMaintenance("maintenance-request", false);
        AgentPackCertificationRequestOutcome outcome = fixture.request();
        assertEquals(CertificationRequestDisposition.CREATED, outcome.disposition());
        assertEquals(MaintenanceInvestigationProductContract.lock(), outcome.certification().inputLock().productContractBundle());
        assertEquals(MaintenanceInvestigationProductContract.GATE_PROFILE, outcome.certification().inputLock().gateProfile());
        assertEquals(1, fixture.certificationCount());
        assertEquals(CertificationRequestDisposition.EXISTING_EXACT,
                fixture.transaction().ensureRequested(outcome.buildSession(), outcome.buildSession(), fixture.requested()).disposition());
    }

    @Test
    void maintenanceMatrixFromAnotherContractIsRejectedBeforeRowsAreCommitted() {
        Fixture fixture = Fixture.createMaintenance("maintenance-wrong-matrix", true);
        assertThrows(FactoryPersistenceException.class, fixture::request);
        assertEquals(0, fixture.certificationCount());
        assertEquals(fixture.ready(), fixture.sessions().find(
                fixture.ready().tenantId(), fixture.ready().buildSessionId()).orElseThrow());
    }

    @Test
    void maintenanceMatrixArtifactIsRehashedInsideTheTransaction() {
        Fixture fixture = Fixture.createMaintenance("maintenance-corrupt-matrix", false);
        try (Connection connection = fixture.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE factory_artifact SET content_base64 = 'Y29ycnVwdA==', content_size = 7
                        WHERE tenant_id = ? AND artifact_ref = ?
                        """)) {
            statement.setString(1, fixture.certification().tenant().value());
            statement.setString(2, MaintenanceInvestigationProductContract.requirementTestMatrixLock().reference().value());
            assertEquals(1, statement.executeUpdate());
        } catch (Exception failure) { throw new AssertionError(failure); }
        assertThrows(FactoryPersistenceException.class, fixture::request);
        assertEquals(0, fixture.certificationCount());
    }

    @Test
    void maintenanceGenerationPhaseIsRecheckedOnExactRequestRetry() {
        Fixture fixture = Fixture.createMaintenance("maintenance-phase-drift", false);
        AgentPackCertificationRequestOutcome first = fixture.request();
        try (Connection connection = fixture.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE factory_work_order SET phase = 'certify'
                        WHERE tenant_id = ? AND work_order_id = ?
                        """)) {
            statement.setString(1, fixture.certification().tenant().value());
            statement.setString(2, fixture.requested().inputLock().generationWorkOrderId().value());
            assertEquals(1, statement.executeUpdate());
        } catch (Exception failure) { throw new AssertionError(failure); }
        assertThrows(FactoryPersistenceException.class, () -> fixture.transaction().ensureRequested(
                first.buildSession(), first.buildSession(), fixture.requested()));
        assertEquals(1, fixture.certificationCount());
        assertEquals(first.buildSession(), fixture.sessions().find(
                fixture.ready().tenantId(), fixture.ready().buildSessionId()).orElseThrow());
    }

    @Test
    void createsExactPairAndRetryReturnsCanonicalWithoutAdvancingSessionAgain() {
        Fixture fixture = Fixture.create("request-roundtrip");

        AgentPackCertificationRequestOutcome created = fixture.request();

        assertEquals(CertificationRequestDisposition.CREATED, created.disposition());
        assertEquals(fixture.requested(), created.certification());
        assertEquals(fixture.certifying(), created.buildSession());
        assertEquals(
                fixture.requested(),
                fixture.certifications()
                        .findLatestForCandidate(
                                fixture.certification().tenant(),
                                fixture.requested().inputLock().buildSessionId(),
                                fixture.requested().inputLock().candidateId(),
                                fixture.requested().inputLock().candidateHash())
                        .orElseThrow());
        assertTrue(fixture.certifications()
                .findLatestForCandidate(
                        fixture.certification().tenant(),
                        fixture.requested().inputLock().buildSessionId(),
                        fixture.requested().inputLock().candidateId(),
                        hash('f'))
                .isEmpty());

        Certification earlierConcurrentIdentity = Certification.requested(
                fixture.requested().certificationId(),
                fixture.requested().inputLock(),
                fixture.requested().inputLockArtifact(),
                fixture.requested().createdAt().minusMillis(500));
        AgentPackCertificationRequestOutcome earlierConcurrentRetry =
                fixture.transaction().ensureRequested(
                        fixture.ready(),
                        fixture.ready().beginAgentPackCertification(
                                earlierConcurrentIdentity.createdAt()),
                        earlierConcurrentIdentity);
        assertEquals(
                CertificationRequestDisposition.EXISTING_EXACT,
                earlierConcurrentRetry.disposition());
        assertEquals(created.buildSession(), earlierConcurrentRetry.buildSession());

        Certification retryIdentity = Certification.requested(
                fixture.requested().certificationId(),
                fixture.requested().inputLock(),
                fixture.requested().inputLockArtifact(),
                fixture.requested().createdAt().plusSeconds(1));
        AgentPackCertificationRequestOutcome retry = fixture.transaction().ensureRequested(
                created.buildSession(), created.buildSession(), retryIdentity);

        assertEquals(CertificationRequestDisposition.EXISTING_EXACT, retry.disposition());
        assertEquals(created.certification(), retry.certification());
        assertEquals(created.buildSession(), retry.buildSession());
        assertEquals(1, fixture.certificationCount());
    }

    @Test
    void concurrentIdenticalCreateHasOneWinnerAndOneExactObservation() throws Exception {
        Fixture fixture = Fixture.create("request-concurrent");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(
                    executor.submit(fixture::request),
                    executor.submit(fixture::request));
            AgentPackCertificationRequestOutcome first = futures.get(0).get(10, TimeUnit.SECONDS);
            AgentPackCertificationRequestOutcome second = futures.get(1).get(10, TimeUnit.SECONDS);

            assertEquals(
                    Set.of(CertificationRequestDisposition.CREATED, CertificationRequestDisposition.EXISTING_EXACT),
                    Set.of(first.disposition(), second.disposition()));
            assertEquals(first.certification(), second.certification());
            assertEquals(first.buildSession(), second.buildSession());
            assertEquals(fixture.ready().version() + 1, first.buildSession().version());
            assertEquals(1, fixture.certificationCount());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void retryWaitsCertificationFirstSoDispatchLockOrderCannotDeadlock() throws Exception {
        Fixture fixture = Fixture.create("request-dispatch-lock-order");
        AgentPackCertificationRequestOutcome created = fixture.request();
        Certification retryIdentity = Certification.requested(
                created.certification().certificationId(),
                created.certification().inputLock(),
                created.certification().inputLockArtifact(),
                created.certification().createdAt().plusSeconds(1));
        var executor = Executors.newSingleThreadExecutor();

        try (Connection dispatch = fixture.dataSource().getConnection()) {
            dispatch.setAutoCommit(false);
            try (var setting = dispatch.createStatement()) {
                setting.execute("SET LOCK_TIMEOUT 1000");
            }
            lockCertification(dispatch, fixture);
            var retry = executor.submit(() -> fixture.transaction().ensureRequested(
                    created.buildSession(), created.buildSession(), retryIdentity));

            assertThrows(TimeoutException.class, () -> retry.get(200, TimeUnit.MILLISECONDS));
            lockSession(dispatch, fixture);
            dispatch.commit();

            assertEquals(
                    CertificationRequestDisposition.EXISTING_EXACT,
                    retry.get(5, TimeUnit.SECONDS).disposition());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void staleCancelledAndExpiredSessionObservationsFailWithoutCertificationInsert() {
        assertSessionMutationFailsClosed("request-stale", fixture -> fixture.executeSessionUpdate(
                "version = version + 1", new Object[0]));
        assertSessionMutationFailsClosed("request-cancelled", fixture -> fixture.executeSessionUpdate(
                "status = 'CANCELLING', cancellation_requested_at = ?, version = version + 1, updated_at = ?",
                fixture.requested().createdAt(), fixture.requested().createdAt()));
        assertSessionMutationFailsClosed("request-expired", fixture -> fixture.executeSessionUpdate(
                "deadline_at = ?, version = version + 1",
                fixture.requested().createdAt()));
    }

    @Test
    void trustedPolicyMismatchFailsBeforeAnyDatabaseMutation() {
        Fixture fixture = Fixture.create("request-policy-mismatch");
        CertificationInputLock mismatched = withFactoryVersion(
                fixture.requested().inputLock(), "0.2.1");
        CertificationArtifactLock inputArtifact = fixture.certification().store(
                "artifact:request-policy-mismatch-input",
                io.github.flowerjvm.factory.infrastructure.certification.JacksonCertificationArtifactCodec.MEDIA_TYPE,
                fixture.certification().codec().writeInputLock(mismatched));
        Certification untrusted = Certification.requested(
                new CertificationId("certification-request-policy-mismatch-untrusted"),
                mismatched,
                inputArtifact,
                fixture.requested().createdAt());

        AgentPackCertificationRequestException failure = assertThrows(
                AgentPackCertificationRequestException.class,
                () -> fixture.transaction().ensureRequested(
                        fixture.ready(), fixture.certifying(), untrusted));

        assertEquals(JdbcCertificationRequestTransaction.POLICY_CONFLICT, failure.code());
        assertEquals(0, fixture.certificationCount());
        assertEquals(fixture.ready(), fixture.sessions().find(
                fixture.ready().tenantId(), fixture.ready().buildSessionId()).orElseThrow());
    }

    @Test
    void differentActiveIdentityReturnsConflictWithoutAdvancingTheSession() {
        Fixture fixture = Fixture.create("request-identity-conflict");
        Certification conflicting = Certification.requested(
                new CertificationId("certification-request-identity-conflict-existing"),
                fixture.requested().inputLock(),
                fixture.requested().inputLockArtifact(),
                fixture.requested().createdAt());
        fixture.certifications().create(conflicting);

        AgentPackCertificationRequestOutcome outcome = fixture.request();

        assertEquals(CertificationRequestDisposition.CONFLICT, outcome.disposition());
        assertEquals(conflicting, outcome.certification());
        assertEquals(fixture.ready(), outcome.buildSession());
        assertEquals(1, fixture.certificationCount());
        assertEquals(fixture.ready(), fixture.sessions().find(
                fixture.ready().tenantId(), fixture.ready().buildSessionId()).orElseThrow());
    }

    @Test
    void latestCandidateLookupUsesAllExactScopeFieldsAndDeterministicOrdering() {
        Fixture fixture = Fixture.create("request-order-query");
        Certification first = fixture.requested();
        fixture.certifications().create(first);
        assertTrue(fixture.certifications().compareAndSet(
                first,
                first.reject("NOT_CERTIFIED", first.createdAt().plusSeconds(1))));
        Certification latest = Certification.requested(
                new CertificationId("certification-request-order-query-second"),
                first.inputLock(),
                first.inputLockArtifact(),
                first.createdAt().plusSeconds(2));
        fixture.certifications().create(latest);

        assertEquals(latest, fixture.certifications()
                .findLatestForCandidate(
                        first.inputLock().tenantId(),
                        first.inputLock().buildSessionId(),
                        first.inputLock().candidateId(),
                        first.inputLock().candidateHash())
                .orElseThrow());
        assertTrue(fixture.certifications()
                .findLatestForCandidate(
                        first.inputLock().tenantId(),
                        new BuildSessionId("build-other"),
                        first.inputLock().candidateId(),
                        first.inputLock().candidateHash())
                .isEmpty());
    }

    @Test
    void failedSessionCasConstraintRollsBackTheCertificationInsert() {
        Fixture fixture = Fixture.create("request-rollback");
        fixture.execute("""
                ALTER TABLE factory_build_session
                ADD CONSTRAINT ck_test_request_no_certifying CHECK (status <> 'CERTIFYING')
                """);

        assertThrows(RuntimeException.class, fixture::request);

        assertEquals(0, fixture.certificationCount());
        assertEquals(fixture.ready(), fixture.sessions().find(
                fixture.ready().tenantId(), fixture.ready().buildSessionId()).orElseThrow());
    }

    private static void assertSessionMutationFailsClosed(
            String suffix,
            java.util.function.Consumer<Fixture> mutation) {
        Fixture fixture = Fixture.create(suffix);
        mutation.accept(fixture);

        AgentPackCertificationRequestException failure = assertThrows(
                AgentPackCertificationRequestException.class,
                fixture::request);

        assertEquals(JdbcCertificationRequestTransaction.SESSION_CONFLICT, failure.code());
        assertEquals(0, fixture.certificationCount());
    }

    private static void lockCertification(Connection connection, Fixture fixture) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT certification_id FROM factory_certification
                WHERE tenant_id = ? AND certification_id = ? FOR UPDATE
                """)) {
            statement.setString(1, fixture.certification().tenant().value());
            statement.setString(2, fixture.requested().certificationId().value());
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
            }
        }
    }

    private static void lockSession(Connection connection, Fixture fixture) throws Exception {
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

    private static CertificationInputLock withFactoryVersion(
            CertificationInputLock input,
            String factoryVersion) {
        return new CertificationInputLock(
                input.schemaVersion(), input.tenantId(), input.productLineId(), input.artifactType(),
                input.buildSessionId(), input.generationWorkOrderId(), input.candidateId(), input.candidateHash(),
                input.sourceManifest(), input.dependencyLock(), input.toolchainLock(),
                input.generationInputManifest(), input.productContractBundle(), input.apiSignatureIndex(),
                input.sourceLockAlgorithmId(), input.gateProfile(), input.verificationRunId(),
                input.verificationActionRunId(), input.verificationResultManifest(),
                input.verificationFixtureSetHash(), input.policySnapshot(), input.compatibilityDescriptor(),
                input.certificationProfile(), factoryVersion, input.flowerVersion(), input.actionRuntimeVersion());
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }

    private record Fixture(
            JdbcCertificationRepositoryTest.Fixture certification,
            BuildSession ready,
            BuildSession certifying,
            AgentPackCertificationPolicy policy,
            JdbcBuildSessionRepository sessions,
            JdbcCertificationRequestTransaction transaction) {

        static Fixture create(String suffix) {
            JdbcCertificationRepositoryTest.Fixture certification =
                    JdbcCertificationRepositoryTest.Fixture.create(suffix);
            makeCandidateReady(certification);
            JdbcBuildSessionRepository sessions =
                    new JdbcBuildSessionRepository(certification.dataSource());
            BuildSession ready = sessions.find(
                    certification.tenant(), certification.inputLock().buildSessionId()).orElseThrow();
            AgentPackCertificationPolicy policy = policy(certification.inputLock());
            return new Fixture(
                    certification,
                    ready,
                    ready.beginAgentPackCertification(certification.requested().createdAt()),
                    policy,
                    sessions,
                    new JdbcCertificationRequestTransaction(certification.dataSource(), policy));
        }

        static Fixture createMaintenance(String suffix, boolean wrongMatrix) {
            DataSource dataSource = FactoryDatabaseMigrationsTest.h2(suffix);
            FactoryDatabaseMigrations.migrate(dataSource);
            JdbcArtifactStore artifacts = new JdbcArtifactStore(dataSource, Clock.systemUTC());
            MaintenanceInvestigationProductContract.artifacts(PersistenceFixtures.TENANT).forEach(artifacts::store);
            var certification = JdbcCertificationRepositoryTest.Fixture.createForVersionedComponentConsumer(
                    dataSource, suffix, MaintenanceInvestigationProductContract.GATE_PROFILE,
                    MaintenanceInvestigationProductContract.lock(), MaintenanceInvestigationProductContract.apiSignatureIndexLock(),
                    wrongMatrix ? MaintenanceInvestigationProductContract.requirementsLock()
                            : MaintenanceInvestigationProductContract.requirementTestMatrixLock());
            makeCandidateReady(certification);
            var sessions = new JdbcBuildSessionRepository(dataSource);
            var ready = sessions.find(certification.tenant(), certification.inputLock().buildSessionId()).orElseThrow();
            var input = certification.inputLock();
            var policy = policy(input);
            // The transaction must validate its own connection's strict canonical provenance,
            // not open a second repository connection while holding domain locks.
            WorkOrderRepository forbiddenExternalRead = new WorkOrderRepository() {
                @Override public void create(WorkOrder value) { throw new AssertionError("unexpected external write"); }
                @Override public Optional<WorkOrder> find(TenantId tenant, WorkOrderId id) {
                    throw new AssertionError("unexpected out-of-transaction generation read");
                }
            };
            var catalog = AgentPackCertificationPolicyCatalog.production(input.verificationFixtureSetHash(),
                    input.factoryVersion(), input.flowerVersion(), input.actionRuntimeVersion(), forbiddenExternalRead,
                    new WorkerProtocolArtifacts(artifacts, new JacksonWorkerProtocolArtifactDecoder(new ObjectMapper())));
            return new Fixture(certification, ready, ready.beginAgentPackCertification(certification.requested().createdAt()),
                    policy, sessions, new JdbcCertificationRequestTransaction(dataSource, catalog));
        }

        DataSource dataSource() {
            return certification.dataSource();
        }

        Certification requested() {
            return certification.requested();
        }

        JdbcCertificationRepository certifications() {
            return certification.certifications();
        }

        AgentPackCertificationRequestOutcome request() {
            return transaction.ensureRequested(ready, certifying, requested());
        }

        int certificationCount() {
            try (Connection connection = dataSource().getConnection();
                    var statement = connection.createStatement();
                    var row = statement.executeQuery("SELECT COUNT(*) FROM factory_certification")) {
                assertTrue(row.next());
                return row.getInt(1);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        void executeSessionUpdate(String assignments, Object... values) {
            String sql = "UPDATE factory_build_session SET " + assignments
                    + " WHERE tenant_id = ? AND build_session_id = ?";
            try (Connection connection = dataSource().getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (Object value : values) {
                    if (value instanceof Instant instant) {
                        statement.setTimestamp(index++, Timestamp.from(instant));
                    } else {
                        statement.setObject(index++, value);
                    }
                }
                statement.setString(index++, certification.tenant().value());
                statement.setString(index, ready.buildSessionId().value());
                assertEquals(1, statement.executeUpdate());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        void execute(String sql) {
            try (Connection connection = dataSource().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
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
    }
}
