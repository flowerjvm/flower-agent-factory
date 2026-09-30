package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcDecisionRecordingTransaction;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/** Synthetic human review fixtures verify wiring; they are not actual product approval evidence. */
class FactoryDecisionConfigurationTest {
    private static final TenantId TENANT = new TenantId("decision-test-tenant");
    private static final ProjectId PROJECT = new ProjectId("decision-test-project");

    @Test
    void standaloneRuntimeRegistersDecisionControlsAndSharesItsAgentPackSubjectAuthority() {
        try (var context = context(dataSource(), false)) {
            assertInstanceOf(JdbcDecisionRecordingTransaction.class, context.getBean(DecisionRecordingTransaction.class));
            assertInstanceOf(JdbcDuplicateActionPolicy.class, context.getBean("actionDuplicatePolicy"));
            var admission = context.getBean(DecisionRecordAdmission.class);
            assertSame(admission, ReflectionTestUtils.getField(context.getBean(DecisionRecordPolicyGate.class), "admission"));
            assertSame(admission, ReflectionTestUtils.getField(context.getBean(DecisionRecordPreExecutionGuard.class), "admission"));
            assertSame(admission, ReflectionTestUtils.getField(context.getBean(DecisionRecordVisibilityScopeResolver.class), "admission"));
            assertSame(admission, ReflectionTestUtils.getField(context.getBean(DecisionRecordActionExecutor.class), "admission"));
            assertSame(context.getBean(DecisionRecordingService.class),
                    ReflectionTestUtils.getField(context.getBean(DecisionRecordActionExecutor.class), "service"));
            assertSame(context.getBean(DecisionRecordActionValidator.class), routes(context, "actionInputValidator").get(DecisionRecordAction.ACTION_ID));
            assertSame(context.getBean(DecisionRecordPolicyGate.class), routes(context, "actionPolicyGate").get(DecisionRecordAction.ACTION_ID));
            assertSame(context.getBean(DecisionRecordPreExecutionGuard.class), routes(context, "actionPreExecutionGuard").get(DecisionRecordAction.ACTION_ID));
            assertAuthoritiesShared(context, Set.of(DecisionPoint.RELEASE_REVIEW_TYPE));
        }
    }

    @Test
    void fullHostSharesAgentPackAndReferenceAssemblyAuthorityWithoutASecondDecisionPath() {
        try (var context = context(dataSource(), true)) {
            assertAuthoritiesShared(context, Set.of(DecisionPoint.RELEASE_REVIEW_TYPE,
                    io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService.DECISION_TYPE));
            assertEquals(1, context.getBeansOfType(DecisionRecordActionExecutor.class).size());
            assertEquals(1, context.getBeansOfType(ActionBackedDecisionRecordLauncher.class).size());
        }
    }

    @Test
    void registeredDecisionUsesJdbcTransactionAndRestartReturnsTheUnchangedCanonicalResult() throws Exception {
        DataSource database = dataSource();
        ActionExecutionResult canonical;
        DecisionPoint point;
        try (var context = context(database, false)) {
            point = seedOpen(context, "a");
            canonical = record(context, point, "stable-request", authority("reviewer", true));
            assertEquals(ActionExecutionStatus.SUCCEEDED, canonical.status());
            assertEquals("DECISION_RECORDED", canonical.code());
            assertEquals(point.subjectHash().sha256(), canonical.output().get("subjectHash"));
            var decided = context.getBean(DecisionPointRepository.class).find(TENANT, point.decisionPointId()).orElseThrow();
            assertEquals(DecisionPointStatus.APPROVED, decided.status());
            assertEquals(1, decided.version());
            assertEquals(1, count(database, "factory_decision"));
            assertEquals(1, count(database, "action_run"));
            assertTrue(count(database, "action_audit") > 0);
            assertEquals(0, count(database, "factory_certification"));
        }
        try (var restarted = context(database, false)) {
            assertEquals(canonical, record(restarted, point, "stable-request", authority("reviewer", true)));
            assertEquals(1, count(database, "factory_decision"));
            assertEquals(2, count(database, "action_run"));
            assertEquals(1, restarted.getBean(DecisionPointRepository.class)
                    .find(TENANT, point.decisionPointId()).orElseThrow().version());
        }
    }

    @Test
    void deniedPrincipalCannotReadCachedDecisionAfterAuthorizedCompletion() throws Exception {
        DataSource database = dataSource();
        try (var context = context(database, false)) {
            var point = seedOpen(context, "a");
            var canonical = record(context, point, "same-key", authority("reviewer", true));
            assertEquals(ActionExecutionStatus.SUCCEEDED, canonical.status());
            var denied = record(context, point, "same-key", authority("denied-reviewer", false));
            assertEquals(ActionExecutionStatus.DENIED, denied.status());
            assertEquals("POLICY_DENIED", denied.code());
            assertTrue(denied.output().isEmpty());
            assertEquals(1, count(database, "factory_decision"));
        }
    }

    @Test
    void authorizedDifferentDecisionPointCannotReceiveAnotherResourcesCachedResult() throws Exception {
        DataSource database = dataSource();
        try (var context = context(database, false)) {
            var first = seedOpen(context, "a");
            var second = seedOpen(context, "b");
            var authorized = authority("reviewer", true);
            var firstResult = record(context, first, "same-key", authorized);
            var secondResult = record(context, second, "same-key", authorized);
            assertEquals(ActionExecutionStatus.SUCCEEDED, firstResult.status());
            assertEquals(ActionExecutionStatus.SUCCEEDED, secondResult.status());
            assertEquals(second.decisionPointId().value(), secondResult.output().get("decisionPointId"));
            assertNotEquals(firstResult.output().get("decisionId"), secondResult.output().get("decisionId"));
            assertEquals(2, count(database, "factory_decision"));
            assertEquals(firstResult, record(context, first, "same-key", authorized));
            assertEquals(2, count(database, "factory_decision"));
        }
    }

    @Test
    void runtimeRejectsUnknownDecisionFieldsAndWrongExactHashWithoutRecordingADecision() throws Exception {
        DataSource database = dataSource();
        try (var context = context(database, false)) {
            var point = seedOpen(context, "a");
            var input = input(point);
            var invalid = new LinkedHashMap<>(input.toMap());
            invalid.put("principal", "payload-self-grant");
            var runtime = context.getBean(DefaultActionRuntime.class);
            var proposal = ActionProposal.builder(DecisionRecordAction.ACTION_ID).proposalId("invalid-fields")
                    .requestChannel(ActionRequestChannel.CLI).proposerType(ActionProposerType.USER)
                    .requesterId("reviewer").input(invalid).idempotencyKey("invalid-key").build();
            var result = runtime.handle(proposal, new ExecutionContext(TENANT.value(), "reviewer", "invalid-run", "trace", Map.of()));
            assertEquals(ActionExecutionStatus.VALIDATION_FAILED, result.status());
            var wrong = new DecisionRecordInput(point.decisionPointId(), 0, hash("f"), DecisionOutcome.APPROVE, Optional.empty());
            var rejected = context.getBean(ActionBackedDecisionRecordLauncher.class)
                    .record(authority("reviewer", true), point.decisionPointId(), "wrong-hash", wrong);
            assertEquals(ActionExecutionStatus.DENIED, rejected.status());
            assertEquals(0, count(database, "factory_decision"));
            assertEquals(DecisionPointStatus.OPEN, context.getBean(DecisionPointRepository.class)
                    .find(TENANT, point.decisionPointId()).orElseThrow().status());
        }
    }

    private static ActionExecutionResult record(AnnotationConfigApplicationContext context, DecisionPoint point,
            String key, DecisionRecordAuthority authority) {
        return context.getBean(ActionBackedDecisionRecordLauncher.class).record(authority, point.decisionPointId(), key, input(point));
    }

    private static DecisionRecordInput input(DecisionPoint point) {
        return new DecisionRecordInput(point.decisionPointId(), point.version(), point.subjectHash(), DecisionOutcome.APPROVE, Optional.empty());
    }

    private static DecisionRecordAuthority authority(String principal, boolean permitted) {
        return new DecisionRecordAuthority(TENANT, PROJECT, principal,
                permitted ? Set.of(DecisionRecordAction.PERMISSION, AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION) : Set.of(),
                new ArtifactReference("artifact:test-only-operator-authority"));
    }

    private static DecisionPoint seedOpen(AnnotationConfigApplicationContext context, String suffix) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var sessionId = new BuildSessionId("decision-session-" + suffix);
        var candidateId = new CandidateId("decision-candidate-" + suffix);
        var session = new BuildSession(sessionId, TENANT, PROJECT, ProductLineId.AGENT_PACK, "request-" + suffix,
                "fixture-builder", BuildSessionStatus.WAITING_RELEASE_REVIEW, BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                new ArtifactReference("artifact:test-requirements"), hash("0"), Optional.empty(), Optional.of("test-coding-worker"),
                Optional.empty(), Optional.of(candidateId), Optional.of(hash(suffix)), Optional.empty(), 0, 3,
                now.minusSeconds(60), now.plusSeconds(3600), Optional.empty(), Optional.empty(), Optional.empty(),
                0, now.minusSeconds(60), now.minusSeconds(60));
        context.getBean(BuildSessionRepository.class).create(session);
        var point = new DecisionPoint(new DecisionPointId("decision-point-" + suffix), TENANT, sessionId,
                DecisionPoint.RELEASE_REVIEW_TYPE, DecisionPointStatus.OPEN, AgentPackReleaseReviewDecisionSubjectAuthority.SUBJECT_TYPE,
                candidateId.value(), 0, hash(suffix), new ArtifactReference("artifact:test-review-question"),
                AgentPackReleaseReviewPolicy.OPTIONS_SCHEMA_ID, Set.of(AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION), 1,
                new ArtifactReference("artifact:test-review-policy"), now.minusSeconds(30), now.plusSeconds(1800),
                Optional.empty(), Optional.empty(), 0);
        context.getBean(DecisionPointRepository.class).create(point);
        return point;
    }

    private static AnnotationConfigApplicationContext context(DataSource database, boolean fullHost) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> database);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.register(FactoryActionRuntimeConfiguration.class);
        if (fullHost) context.register(FactoryWorkerTransportConfiguration.class, FactoryCertificationConfiguration.class,
                FactoryReferenceAssemblyConfiguration.class, FactoryFlowerConfiguration.class);
        context.refresh();
        return context;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> routes(AnnotationConfigApplicationContext context, String bean) {
        return (Map<String, ?>) ReflectionTestUtils.getField(context.getBean(bean), "routes");
    }

    @SuppressWarnings("unchecked")
    private static void assertAuthoritiesShared(AnnotationConfigApplicationContext context, Set<String> expected) {
        var service = (Map<String, ?>) ReflectionTestUtils.getField(context.getBean(DecisionRecordingService.class), "subjectAuthorities");
        var admission = (Map<String, ?>) ReflectionTestUtils.getField(context.getBean(DecisionRecordAdmission.class), "authorities");
        assertEquals(expected, service.keySet());
        assertEquals(expected, admission.keySet());
        expected.forEach(type -> assertSame(service.get(type), admission.get(type)));
    }

    private static DataSource dataSource() {
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        database.setUser("sa"); database.setPassword("");
        return database;
    }

    private static long count(DataSource database, String table) throws Exception {
        try (Connection connection = database.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    private static ContentHash hash(String digit) { return new ContentHash(digit.repeat(64)); }
}
