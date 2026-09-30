package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.CertificationIssueAction;
import io.github.flowerjvm.factory.application.action.CertificationIssueIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.factory.application.certification.CertificationAttemptTokens;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntent;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntentStatus;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchOperationIds;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcCertificationDispatchTransactionTest {
    private static final Instant PREPARED_AT = Instant.parse("2026-08-12T00:00:06Z");

    @Test
    void prepareLocksExactOwnersCreatesOnlyPendingAndIsIdempotentForTheSameOwner() {
        Fixture fixture = Fixture.create("transaction-success");

        CertificationDispatchIntent first = fixture.prepare();
        CertificationDispatchIntent retry = fixture.prepare();

        assertEquals(first, retry);
        assertEquals(
                CertificationDispatchOperationIds.derive(fixture.certification().tenant(), fixture.input()),
                first.operationId());
        assertEquals(CertificationDispatchIntentStatus.PENDING, first.status());
        assertEquals(fixture.actionRunId(), first.actionRunId());
        assertEquals(fixture.attemptTokenHash(), first.attemptTokenHash());
        assertEquals(1, fixture.intentCount());
        assertEquals(
                fixture.certification().requested(),
                fixture.certification().certifications()
                        .find(fixture.certification().tenant(), fixture.certification().certificationId())
                        .orElseThrow(),
                "pre-park prepare must not mutate Certification or its artifact lock");
        assertEquals(first, fixture.transaction()
                .findExact(
                        fixture.certification().tenant(), fixture.input(),
                        fixture.actionRunId(), fixture.attemptTokenHash())
                .orElseThrow());
    }

    @Test
    void wrongTenantActionSessionAndHashAllFailWithoutCreatingAnIntent() {
        deniedWithoutInsert("transaction-wrong-tenant", fixture -> fixture.transaction().prepare(
                new TenantId("other-tenant"), fixture.input(), fixture.actionRunId(),
                fixture.attemptTokenHash(), PREPARED_AT));
        deniedWithoutInsert("transaction-wrong-action", fixture -> {
            fixture.updateAction("action_id", "factory.verification.run");
            fixture.prepare();
        });
        deniedWithoutInsert("transaction-wrong-session", fixture -> {
            fixture.updateSessionStatus("RUNNING");
            fixture.prepare();
        });
        deniedWithoutInsert("transaction-wrong-input-hash", fixture -> {
            CertificationIssueInput wrong = new CertificationIssueInput(
                    fixture.input().certificationId(), hash('f'),
                    fixture.input().expectedCertificationVersion());
            fixture.transaction().prepare(
                    fixture.certification().tenant(), wrong, fixture.actionRunId(),
                    fixture.attemptTokenHash(), PREPARED_AT);
        });
        deniedWithoutInsert("transaction-wrong-attempt-hash", fixture -> fixture.transaction().prepare(
                fixture.certification().tenant(), fixture.input(), fixture.actionRunId(),
                "f".repeat(64), PREPARED_AT));
    }

    @Test
    void duplicateAndTrailingActionJsonAreRejectedStrictlyBeforeInsert() {
        Fixture duplicate = Fixture.create("transaction-duplicate-json");
        String certificationId = duplicate.input().certificationId().value();
        String inputHash = duplicate.input().inputLockManifestHash().sha256();
        duplicate.updateActionJson("input_json", """
                {"certificationId":"%s","certificationId":"%s",
                 "inputLockManifestHash":"%s","expectedCertificationVersion":0}
                """.formatted(certificationId, certificationId, inputHash));
        assertThrows(FactoryPersistenceException.class, duplicate::prepare);
        assertEquals(0, duplicate.intentCount());

        Fixture trailing = Fixture.create("transaction-trailing-json");
        trailing.updateActionJson("context_metadata_json", """
                {"resource.type":"certification","resource.id":"%s"} {}
                """.formatted(trailing.input().certificationId().value()));
        assertThrows(FactoryPersistenceException.class, trailing::prepare);
        assertEquals(0, trailing.intentCount());
    }

    @Test
    void conflictingDeterministicOperationRollsBackAndPreservesTheOriginalOwner() {
        Fixture fixture = Fixture.create("transaction-owner-conflict");
        CertificationDispatchIntent conflicting = CertificationDispatchIntent.pending(
                CertificationDispatchOperationIds.derive(
                        fixture.certification().tenant(), fixture.input()),
                fixture.certification().tenant(),
                fixture.input().certificationId(),
                fixture.input().inputLockManifestHash(),
                fixture.input().expectedCertificationVersion(),
                fixture.certification().inputLock().verificationActionRunId(),
                "e".repeat(64),
                PREPARED_AT.plusSeconds(300),
                PREPARED_AT);
        fixture.intents().create(conflicting);

        assertThrows(IllegalStateException.class, fixture::prepare);

        assertEquals(conflicting, fixture.intents().find(conflicting.operationId()).orElseThrow());
        assertEquals(1, fixture.intentCount());
    }

    private static void deniedWithoutInsert(String suffix, Consumer<Fixture> attempt) {
        Fixture fixture = Fixture.create(suffix);

        assertThrows(RuntimeException.class, () -> attempt.accept(fixture));

        assertEquals(0, fixture.intentCount(), "failed prepare must roll back its PENDING insert");
    }

    record Fixture(
            JdbcCertificationRepositoryTest.Fixture certification,
            CertificationIssueInput input,
            String actionRunId,
            String attemptToken,
            String attemptTokenHash,
            JdbcCertificationDispatchIntentRepository intents,
            JdbcCertificationDispatchTransaction transaction) {

        static Fixture create(String suffix) {
            JdbcCertificationRepositoryTest.Fixture certification =
                    JdbcCertificationRepositoryTest.Fixture.create(suffix);
            return create(certification, suffix);
        }

        static Fixture create(DataSource dataSource, String suffix) {
            JdbcCertificationRepositoryTest.Fixture certification =
                    JdbcCertificationRepositoryTest.Fixture.create(dataSource, suffix);
            return create(certification, suffix);
        }

        private static Fixture create(
                JdbcCertificationRepositoryTest.Fixture certification,
                String suffix) {
            certification.certifications().create(certification.requested());
            makeSessionCertifiable(certification);
            CertificationIssueInput input = new CertificationIssueInput(
                    certification.certificationId(),
                    certification.requested().inputLockArtifact().hash(),
                    certification.requested().version());
            String actionRunId = "cert-issue-" + suffix;
            String attemptToken = "attempt-" + suffix;
            createRunningActionRun(certification, input, actionRunId, attemptToken);
            return new Fixture(
                    certification,
                    input,
                    actionRunId,
                    attemptToken,
                    CertificationAttemptTokens.hash(attemptToken),
                    new JdbcCertificationDispatchIntentRepository(certification.dataSource()),
                    new JdbcCertificationDispatchTransaction(certification.dataSource()));
        }

        CertificationDispatchIntent prepare() {
            return transaction.prepare(
                    certification.tenant(), input, actionRunId, attemptTokenHash, PREPARED_AT);
        }

        int intentCount() {
            try (Connection connection = certification.dataSource().getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT COUNT(*) FROM factory_certification_dispatch_intent");
                    ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        void updateSessionStatus(String status) {
            executeUpdate("UPDATE factory_build_session SET status = ? WHERE build_session_id = ?",
                    status, certification.inputLock().buildSessionId().value());
        }

        void updateAction(String column, String value) {
            if (!"action_id".equals(column)) {
                throw new IllegalArgumentException("unsupported test column");
            }
            executeUpdate("UPDATE action_run SET action_id = ? WHERE run_id = ?", value, actionRunId);
        }

        void updateActionJson(String column, String value) {
            if (!Set.of("input_json", "context_metadata_json").contains(column)) {
                throw new IllegalArgumentException("unsupported test JSON column");
            }
            String sql = "UPDATE action_run SET " + column + " = ? WHERE run_id = ?";
            executeUpdate(sql, value, actionRunId);
        }

        private void executeUpdate(String sql, String first, String second) {
            try (Connection connection = certification.dataSource().getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, first);
                statement.setString(2, second);
                assertEquals(1, statement.executeUpdate());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        private static void makeSessionCertifiable(
                JdbcCertificationRepositoryTest.Fixture certification) {
            try (Connection connection = certification.dataSource().getConnection();
                    PreparedStatement statement = connection.prepareStatement("""
                            UPDATE factory_build_session SET
                                status = 'CERTIFYING', current_phase = 'certify',
                                current_candidate_id = ?, current_candidate_hash = ?
                            WHERE tenant_id = ? AND build_session_id = ?
                            """)) {
                statement.setString(1, certification.inputLock().candidateId().value());
                statement.setString(2, certification.inputLock().candidateHash().sha256());
                statement.setString(3, certification.tenant().value());
                statement.setString(4, certification.inputLock().buildSessionId().value());
                assertEquals(1, statement.executeUpdate());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        private static void createRunningActionRun(
                JdbcCertificationRepositoryTest.Fixture certification,
                CertificationIssueInput input,
                String actionRunId,
                String attemptToken) {
            ActionProposal proposal = ActionProposal.builder(CertificationIssueAction.ACTION_ID)
                    .proposalId("proposal-" + actionRunId)
                    .requestChannel(ActionRequestChannel.INTERNAL)
                    .proposerType(ActionProposerType.SERVICE)
                    .requesterId("factory-certifier")
                    .input(input.toMap())
                    .idempotencyKey(CertificationIssueIdempotencyKeys.derive(
                            certification.requested(), input.expectedCertificationVersion()))
                    .build();
            ExecutionContext context = new ExecutionContext(
                    certification.tenant().value(),
                    "factory-certifier",
                    actionRunId,
                    "trace-" + actionRunId,
                    Map.of(
                            "actor.permissions", Set.of(CertificationIssueAction.PERMISSION),
                            "resource.type", CertificationIssueAction.RESOURCE_TYPE,
                            "resource.id", certification.certificationId().value()));
            JdbcRunStore actionRuns = new JdbcRunStore(certification.dataSource(), new ObjectMapper());
            ActionRun requested = ActionRun.requested(proposal, context);
            actionRuns.create(requested);
            ActionRun running = requested.toBuilder()
                    .version(requested.version() + 1)
                    .status(ActionRunStatus.RUNNING)
                    .currentStage("EXECUTE")
                    .attemptToken(attemptToken)
                    .externalOperationId("")
                    .externalOperationMetadata(Map.of())
                    .dueAt(null)
                    .result(null)
                    .failureReason("")
                    .updatedAt(requested.updatedAt().plusMillis(1))
                    .build();
            if (!actionRuns.compareAndSet(requested, running)) {
                throw new AssertionError("could not create RUNNING Certification Action owner");
            }
        }
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }
}
