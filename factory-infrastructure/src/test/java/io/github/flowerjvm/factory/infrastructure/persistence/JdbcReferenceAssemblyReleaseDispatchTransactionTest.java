package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseAction;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.decision.Decision;
import io.github.flowerjvm.factory.application.decision.DecisionOutcome;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ActionBackedReferenceAssemblyReleaseLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAttemptTokens;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntent;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchOperationIds;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcReferenceAssemblyReleaseDispatchTransactionTest {
    private static final Instant ASSEMBLY_CREATED_AT =
            Instant.parse("2026-09-01T00:00:10Z");
    private static final Instant PREPARED_AT =
            Instant.parse("2026-09-01T00:00:20Z");
    private static final String SUBJECT_REFERENCE_PREFIX =
            "factory-reference-assembly/release-subject/sha256/";
    private static final String DECISION_ID_PREFIX =
            "reference-assembly-release-review-";

    @Test
    void prepareBindsActionAndPendingIntentAtomicallyAndExactRetryConverges() {
        Fixture fixture = Fixture.create("success");

        ReferenceAssemblyReleaseDispatchIntent first = fixture.prepare();
        ReferenceAssemblyReleaseDispatchIntent retry = fixture.prepare();

        assertEquals(first, retry);
        assertEquals(ReferenceAssemblyReleaseDispatchIntentStatus.PENDING, first.status());
        assertEquals(fixture.actionRunId(), first.actionRunId());
        assertEquals(fixture.attemptTokenHash(), first.attemptTokenHash());
        assertEquals(fixture.sessionDeadline(), first.deadlineAt());
        assertEquals(1, fixture.intentCount());
        ReferenceAssembly bound = fixture.storedAssembly();
        assertEquals(fixture.input().expectedReferenceAssemblyVersion() + 1, bound.version());
        assertEquals(Optional.of(fixture.actionRunId()), bound.releaseActionRunId());
        assertEquals(
                first,
                fixture.transaction()
                        .findExact(
                                fixture.tenant(),
                                fixture.input(),
                                fixture.actionRunId(),
                                fixture.attemptTokenHash())
                        .orElseThrow());
    }

    @Test
    void simultaneousExactPrepareHasOneBindingAndOneIntent() throws Exception {
        Fixture fixture = Fixture.create("race");
        var first = new JdbcReferenceAssemblyReleaseDispatchTransaction(fixture.dataSource());
        var second = new JdbcReferenceAssemblyReleaseDispatchTransaction(fixture.dataSource());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = List.of(
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return first.prepare(
                                fixture.tenant(),
                                fixture.input(),
                                fixture.actionRunId(),
                                fixture.attemptTokenHash(),
                                PREPARED_AT);
                    }),
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return second.prepare(
                                fixture.tenant(),
                                fixture.input(),
                                fixture.actionRunId(),
                                fixture.attemptTokenHash(),
                                PREPARED_AT);
                    }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            ReferenceAssemblyReleaseDispatchIntent expected =
                    results.get(0).get(5, TimeUnit.SECONDS);
            assertEquals(expected, results.get(1).get(5, TimeUnit.SECONDS));
            assertEquals(1, fixture.intentCount());
            assertEquals(
                    fixture.input().expectedReferenceAssemblyVersion() + 1,
                    fixture.storedAssembly().version());
            assertEquals(
                    Optional.of(fixture.actionRunId()),
                    fixture.storedAssembly().releaseActionRunId());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationThatOwnsSessionLockWinsBeforeDispatchAndLeavesNoResidue()
            throws Exception {
        Fixture fixture = Fixture.create("cancel-race");
        var executor = Executors.newSingleThreadExecutor();
        try (Connection cancellation = fixture.dataSource().getConnection()) {
            cancellation.setAutoCommit(false);
            lockSession(cancellation, fixture);
            cancelSession(cancellation, fixture, PREPARED_AT.minusSeconds(1));

            CountDownLatch started = new CountDownLatch(1);
            var prepare = executor.submit(() -> {
                started.countDown();
                return fixture.prepare();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> prepare.get(200, TimeUnit.MILLISECONDS));

            cancellation.commit();
            ExecutionException denied = assertThrows(
                    ExecutionException.class,
                    () -> prepare.get(5, TimeUnit.SECONDS));
            assertTrue(denied.getCause() instanceof IllegalStateException);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(Optional.empty(), fixture.storedAssembly().releaseActionRunId());
        assertEquals(fixture.input().expectedReferenceAssemblyVersion(),
                fixture.storedAssembly().version());
        assertEquals(0, fixture.intentCount());
    }

    @Test
    void exactDeadlineAndRevokedComponentBothFailBeforeBinding() {
        Fixture deadline = Fixture.create("deadline");
        deadline.moveDeadlineTo(PREPARED_AT);
        assertThrows(IllegalStateException.class, deadline::prepare);
        deadline.assertNoDispatchResidue();

        Fixture revoked = Fixture.create("revoked");
        Certification certified = revoked.assemblyFixture().componentCertification();
        Certification revokedComponent = certified.revoke(
                "COMPONENT_CERTIFICATION_REVOKED", PREPARED_AT.minusSeconds(1));
        assertTrue(revoked.assemblyFixture()
                .certificationFixture()
                .certifications()
                .compareAndSet(certified, revokedComponent));
        assertThrows(IllegalStateException.class, revoked::prepare);
        revoked.assertNoDispatchResidue();
    }

    @Test
    void intentInsertConflictRollsBackTheEarlierActionBinding() {
        Fixture fixture = Fixture.create("insert-rollback");
        ReferenceAssemblyReleaseInput otherAttempt = new ReferenceAssemblyReleaseInput(
                fixture.input().referenceAssemblyId(),
                fixture.input().assemblyManifestHash(),
                fixture.input().inspectionReportHash(),
                fixture.input().releaseDecisionPointId(),
                fixture.input().releaseSubjectHash(),
                fixture.input().expectedReferenceAssemblyVersion() + 10);
        ReferenceAssemblyReleaseDispatchIntent occupiesActionOwner =
                ReferenceAssemblyReleaseDispatchIntent.pending(
                        ReferenceAssemblyReleaseDispatchOperationIds.derive(
                                fixture.tenant(), otherAttempt),
                        fixture.tenant(),
                        otherAttempt,
                        fixture.actionRunId(),
                        fixture.attemptTokenHash(),
                        fixture.sessionDeadline(),
                        PREPARED_AT.minusSeconds(1));
        fixture.intents().create(occupiesActionOwner);

        assertThrows(RuntimeException.class, fixture::prepare);

        assertEquals(Optional.empty(), fixture.storedAssembly().releaseActionRunId());
        assertEquals(
                fixture.input().expectedReferenceAssemblyVersion(),
                fixture.storedAssembly().version());
        assertEquals(1, fixture.intentCount());
        assertEquals(
                occupiesActionOwner,
                fixture.intents().find(occupiesActionOwner.operationId()).orElseThrow());
        assertFalse(fixture.transaction()
                .findExact(
                        fixture.tenant(),
                        fixture.input(),
                        fixture.actionRunId(),
                        fixture.attemptTokenHash())
                .isPresent());
    }

    @Test
    void wrongTenantAndAttemptHashCannotClaimTheActionOwner() {
        Fixture fixture = Fixture.create("wrong-owner");

        assertThrows(
                IllegalStateException.class,
                () -> fixture.transaction().prepare(
                        new io.github.flowerjvm.factory.contracts.ids.TenantId("tenant-other"),
                        fixture.input(),
                        fixture.actionRunId(),
                        fixture.attemptTokenHash(),
                        PREPARED_AT));
        assertThrows(
                IllegalStateException.class,
                () -> fixture.transaction().prepare(
                        fixture.tenant(),
                        fixture.input(),
                        fixture.actionRunId(),
                        "f".repeat(64),
                        PREPARED_AT));
        fixture.assertNoDispatchResidue();
    }

    @Test
    void nonCanonicalActionPrincipalAndRequesterFailBeforeBinding() {
        Fixture wrongPrincipal = Fixture.create("wrong-principal");
        wrongPrincipal.replaceActionCaller(
                "noncanonical-release-principal",
                ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID,
                wrongPrincipal.canonicalTrace());
        assertThrows(IllegalStateException.class, wrongPrincipal::prepare);
        wrongPrincipal.assertNoDispatchResidue();

        Fixture wrongRequester = Fixture.create("wrong-requester");
        wrongRequester.replaceActionCaller(
                ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID,
                "noncanonical-release-requester",
                wrongRequester.canonicalTrace());
        assertThrows(IllegalStateException.class, wrongRequester::prepare);
        wrongRequester.assertNoDispatchResidue();
    }

    @Test
    void blankActionTraceFailsBeforeBinding() {
        Fixture fixture = Fixture.create("blank-trace");
        fixture.replaceActionCaller(
                ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID,
                ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID,
                " ");

        assertThrows(IllegalStateException.class, fixture::prepare);
        fixture.assertNoDispatchResidue();
    }

    @Test
    void extraPermissionOrContextKeyFailsBeforeBinding() {
        Fixture extraPermission = Fixture.create("extra-permission");
        extraPermission.replaceActionContext(Map.of(
                "actor.permissions",
                Set.of(ReferenceAssemblyReleaseAction.PERMISSION, "unrelated.permission"),
                "resource.type",
                ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                "resource.id",
                extraPermission.input().referenceAssemblyId().value()));
        assertThrows(IllegalStateException.class, extraPermission::prepare);
        extraPermission.assertNoDispatchResidue();

        Fixture extraContext = Fixture.create("extra-context");
        extraContext.replaceActionContext(Map.of(
                "actor.permissions",
                Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                "resource.type",
                ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                "resource.id",
                extraContext.input().referenceAssemblyId().value(),
                "untrusted.extra",
                "value"));
        assertThrows(IllegalStateException.class, extraContext::prepare);
        extraContext.assertNoDispatchResidue();
    }

    record Fixture(
            JdbcReferenceAssemblyRepositoryTest.Fixture assemblyFixture,
            ReferenceAssembly approvedAssembly,
            DecisionPoint approvedPoint,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            String attemptToken,
            String attemptTokenHash,
            JdbcReferenceAssemblyReleaseDispatchIntentRepository intents,
            JdbcReferenceAssemblyReleaseDispatchTransaction transaction) {

        static Fixture create(String suffix) {
            JdbcReferenceAssemblyRepositoryTest.Fixture base =
                    JdbcReferenceAssemblyRepositoryTest.Fixture.create("release-tx-" + suffix);
            return create(base, suffix);
        }

        static Fixture create(DataSource dataSource, String suffix) {
            JdbcReferenceAssemblyRepositoryTest.Fixture base =
                    JdbcReferenceAssemblyRepositoryTest.Fixture.create(
                            dataSource, "release-tx-" + suffix);
            return create(base, suffix);
        }

        private static Fixture create(
                JdbcReferenceAssemblyRepositoryTest.Fixture base,
                String suffix) {
            base.repository().create(base.requested());
            ReferenceAssembly resolved =
                    base.requested().resolveComponent(ASSEMBLY_CREATED_AT.plusSeconds(1));
            ReferenceAssembly assembled =
                    resolved.assemble(base.assemblyManifest(), ASSEMBLY_CREATED_AT.plusSeconds(2));
            ReferenceAssembly inspected =
                    assembled.inspect(base.inspectionReport(), ASSEMBLY_CREATED_AT.plusSeconds(3));
            assertTrue(base.repository().compareAndSet(base.requested(), resolved));
            assertTrue(base.repository().compareAndSet(resolved, assembled));
            assertTrue(base.repository().compareAndSet(assembled, inspected));

            ContentHash subjectHash = hash('9');
            DecisionPoint approvedPoint = createApprovedPoint(
                    base, inspected, subjectHash, suffix);
            ReferenceAssembly reviewBound = inspected.bindReleaseReview(
                    approvedPoint.decisionPointId(),
                    approvedPoint.subjectHash(),
                    ASSEMBLY_CREATED_AT.plusSeconds(6));
            assertTrue(base.repository().compareAndSet(inspected, reviewBound));
            moveSessionToPackageRelease(
                    base, ASSEMBLY_CREATED_AT.plusSeconds(7));

            ReferenceAssemblyReleaseInput input = new ReferenceAssemblyReleaseInput(
                    reviewBound.referenceAssemblyId(),
                    reviewBound.assemblyManifest().orElseThrow().hash(),
                    reviewBound.inspectionReport().orElseThrow().hash(),
                    approvedPoint.decisionPointId(),
                    approvedPoint.subjectHash(),
                    reviewBound.version());
            String actionRunId = "reference-release-action-" + suffix;
            String attemptToken = "reference-release-attempt-" + suffix;
            createRunningAction(base, reviewBound, input, actionRunId, attemptToken);
            return new Fixture(
                    base,
                    reviewBound,
                    approvedPoint,
                    input,
                    actionRunId,
                    attemptToken,
                    ReferenceAssemblyReleaseAttemptTokens.hash(attemptToken),
                    new JdbcReferenceAssemblyReleaseDispatchIntentRepository(
                            base.certificationFixture().dataSource()),
                    new JdbcReferenceAssemblyReleaseDispatchTransaction(
                            base.certificationFixture().dataSource()));
        }

        javax.sql.DataSource dataSource() {
            return assemblyFixture.certificationFixture().dataSource();
        }

        io.github.flowerjvm.factory.contracts.ids.TenantId tenant() {
            return assemblyFixture.tenant();
        }

        Instant sessionDeadline() {
            return assemblyFixture.session().deadlineAt();
        }

        ReferenceAssemblyReleaseDispatchIntent prepare() {
            return transaction.prepare(
                    tenant(), input, actionRunId, attemptTokenHash, PREPARED_AT);
        }

        ReferenceAssembly storedAssembly() {
            return assemblyFixture.repository()
                    .find(tenant(), approvedAssembly.referenceAssemblyId())
                    .orElseThrow();
        }

        int intentCount() {
            try (Connection connection = dataSource().getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT COUNT(*) FROM factory_reference_assembly_release_intent");
                    ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        void assertNoDispatchResidue() {
            assertEquals(Optional.empty(), storedAssembly().releaseActionRunId());
            assertEquals(input.expectedReferenceAssemblyVersion(), storedAssembly().version());
            assertEquals(0, intentCount());
        }

        String canonicalTrace() {
            return "trace-" + actionRunId;
        }

        void replaceActionCaller(String userId, String requesterId, String traceId) {
            try (Connection connection = dataSource().getConnection();
                    PreparedStatement statement = connection.prepareStatement("""
                            UPDATE action_run
                            SET user_id = ?, requester_id = ?, trace_id = ?
                            WHERE run_id = ?
                            """)) {
                statement.setString(1, userId);
                statement.setString(2, requesterId);
                statement.setString(3, traceId);
                statement.setString(4, actionRunId);
                assertEquals(1, statement.executeUpdate());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        void replaceActionContext(Map<String, Object> metadata) {
            try (Connection connection = dataSource().getConnection();
                    PreparedStatement statement = connection.prepareStatement("""
                            UPDATE action_run SET context_metadata_json = ?
                            WHERE run_id = ?
                            """)) {
                statement.setString(1, new ObjectMapper().writeValueAsString(metadata));
                statement.setString(2, actionRunId);
                assertEquals(1, statement.executeUpdate());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        void moveDeadlineTo(Instant deadline) {
            try (Connection connection = dataSource().getConnection()) {
                try (PreparedStatement session = connection.prepareStatement("""
                        UPDATE factory_build_session SET deadline_at = ?
                        WHERE tenant_id = ? AND build_session_id = ?
                        """)) {
                    JdbcPersistenceSupport.setInstant(session, 1, deadline);
                    session.setString(2, tenant().value());
                    session.setString(3, assemblyFixture.session().buildSessionId().value());
                    assertEquals(1, session.executeUpdate());
                }
                try (PreparedStatement point = connection.prepareStatement("""
                        UPDATE factory_decision_point SET due_at = ?
                        WHERE tenant_id = ? AND decision_point_id = ?
                        """)) {
                    JdbcPersistenceSupport.setInstant(point, 1, deadline);
                    point.setString(2, tenant().value());
                    point.setString(3, approvedPoint.decisionPointId().value());
                    assertEquals(1, point.executeUpdate());
                }
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static DecisionPoint createApprovedPoint(
            JdbcReferenceAssemblyRepositoryTest.Fixture base,
            ReferenceAssembly inspected,
            ContentHash subjectHash,
            String suffix) {
        DecisionPointId pointId = deriveDecisionPointId(
                base.tenant(), inspected.referenceAssemblyId(), subjectHash);
        Instant openedAt = ASSEMBLY_CREATED_AT.plusSeconds(4);
        DecisionPoint open = new DecisionPoint(
                pointId,
                base.tenant(),
                base.session().buildSessionId(),
                ReferenceAssemblyReleaseReviewService.DECISION_TYPE,
                DecisionPointStatus.OPEN,
                ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE,
                inspected.referenceAssemblyId().value(),
                inspected.version(),
                subjectHash,
                new ArtifactReference(SUBJECT_REFERENCE_PREFIX + subjectHash.sha256()),
                ReferenceAssemblyReleaseReviewService.OPTIONS_SCHEMA_ID,
                Set.of(ReferenceAssemblyReleaseReviewService.REQUIRED_PERMISSION),
                1,
                inspected.policySnapshot().reference(),
                openedAt,
                base.session().deadlineAt(),
                Optional.empty(),
                Optional.empty(),
                0);
        JdbcDecisionPointRepository points =
                new JdbcDecisionPointRepository(base.certificationFixture().dataSource());
        points.create(open);
        Decision decision = new Decision(
                new DecisionId("reference-release-decision-" + suffix),
                base.tenant(),
                pointId,
                "approve-reference-release-" + suffix,
                DecisionOutcome.APPROVE,
                Optional.of("release"),
                Optional.empty(),
                "reference-release-reviewer",
                new ArtifactReference("artifact:reference-release-authority:" + suffix),
                subjectHash,
                openedAt.plusSeconds(1));
        new JdbcDecisionRepository(base.certificationFixture().dataSource()).create(decision);
        DecisionPoint approved = open.decide(decision, openedAt.plusSeconds(1));
        assertTrue(points.compareAndSet(open, approved));
        return approved;
    }

    private static void moveSessionToPackageRelease(
            JdbcReferenceAssemblyRepositoryTest.Fixture base, Instant updatedAt) {
        try (Connection connection = base.certificationFixture().dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE factory_build_session SET
                            status = 'RUNNING', current_phase = 'package-release',
                            current_certification_id = NULL,
                            cancellation_requested_at = NULL,
                            version = version + 1, updated_at = ?
                        WHERE tenant_id = ? AND build_session_id = ?
                        """)) {
            JdbcPersistenceSupport.setInstant(statement, 1, updatedAt);
            statement.setString(2, base.tenant().value());
            statement.setString(3, base.session().buildSessionId().value());
            assertEquals(1, statement.executeUpdate());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void createRunningAction(
            JdbcReferenceAssemblyRepositoryTest.Fixture base,
            ReferenceAssembly assembly,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            String attemptToken) {
        ActionProposal proposal = ActionProposal.builder(ReferenceAssemblyReleaseAction.ACTION_ID)
                .proposalId("proposal-" + actionRunId)
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId(ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID)
                .input(input.toMap())
                .idempotencyKey(ReferenceAssemblyReleaseIdempotencyKeys.derive(
                        assembly, input.expectedReferenceAssemblyVersion()))
                .build();
        ExecutionContext context = new ExecutionContext(
                base.tenant().value(),
                ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID,
                actionRunId,
                "trace-" + actionRunId,
                Map.of(
                        "actor.permissions", Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                        "resource.type", ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                        "resource.id", assembly.referenceAssemblyId().value()));
        JdbcRunStore runs = new JdbcRunStore(
                base.certificationFixture().dataSource(), new ObjectMapper());
        ActionRun requested = ActionRun.requested(proposal, context);
        runs.create(requested);
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
        assertTrue(runs.compareAndSet(requested, running));
    }

    private static void lockSession(Connection connection, Fixture fixture) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT build_session_id FROM factory_build_session
                WHERE tenant_id = ? AND build_session_id = ?
                FOR UPDATE
                """)) {
            statement.setString(1, fixture.tenant().value());
            statement.setString(2, fixture.assemblyFixture().session().buildSessionId().value());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
            }
        }
    }

    private static void cancelSession(
            Connection connection, Fixture fixture, Instant cancelledAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE factory_build_session SET
                    status = 'CANCELLING', cancellation_requested_at = ?,
                    version = version + 1, updated_at = ?
                WHERE tenant_id = ? AND build_session_id = ?
                """)) {
            JdbcPersistenceSupport.setInstant(statement, 1, cancelledAt);
            JdbcPersistenceSupport.setInstant(statement, 2, cancelledAt);
            statement.setString(3, fixture.tenant().value());
            statement.setString(4, fixture.assemblyFixture().session().buildSessionId().value());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static DecisionPointId deriveDecisionPointId(
            io.github.flowerjvm.factory.contracts.ids.TenantId tenantId,
            io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId assemblyId,
            ContentHash subjectHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, tenantId.value());
            updateLengthPrefixed(digest, assemblyId.value());
            updateLengthPrefixed(digest, subjectHash.sha256());
            return new DecisionPointId(
                    DECISION_ID_PREFIX + HexFormat.of().formatHex(digest.digest()));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }
}
