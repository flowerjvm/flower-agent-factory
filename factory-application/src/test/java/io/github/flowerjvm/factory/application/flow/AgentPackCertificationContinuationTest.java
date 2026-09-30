package io.github.flowerjvm.factory.application.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.CertificationIssueAction;
import io.github.flowerjvm.factory.application.action.CertificationIssueIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.ActionBackedAgentPackCertificationLauncher;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequestOutcome;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequester;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.certification.CertificationAttemptTokens;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntent;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchOperationIds;
import io.github.flowerjvm.factory.application.certification.CertificationRepository;
import io.github.flowerjvm.factory.application.certification.CertificationRequestDisposition;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import io.github.flowerjvm.flower.core.flow.Flow;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.flow.FlowState;
import io.github.flowerjvm.flower.core.step.RecoveryPolicy;
import io.github.flowerjvm.flower.testkit.FlowTestHarness;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentPackCertificationContinuationTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant-certification-flow");
    private static final BuildSessionId SESSION_ID = new BuildSessionId("build-certification-flow");
    private static final CandidateId CANDIDATE_ID = new CandidateId("candidate-certification-flow");
    private static final CertificationId CERTIFICATION_ID =
            new CertificationId("certification-certification-flow");
    private static final ProjectId PROJECT_ID = new ProjectId("project-certification-flow");
    private static final ContentHash CANDIDATE_HASH = hash("candidate-source");
    private static final FlowId FLOW_ID =
            FlowId.of(AgentPackCertificationFlowFactory.FLOW_TYPE, SESSION_ID.value());

    @Test
    void pinsSecondarySingleDurableStepAndNarrowOwnership() {
        AgentPackCertificationFlowCoordinator holding = (id, context) ->
                io.github.flowerjvm.flower.core.step.StepResult.stay();
        var factory = new AgentPackCertificationFlowFactory(holding);
        BuildSession candidateReady = candidateReady();

        Flow first = factory.create(candidateReady, "certification-flow-run-1", "trace-1");
        Flow second = factory.create(candidateReady, "certification-flow-run-2", "trace-2");

        assertEquals(AgentPackCertificationFlowFactory.FLOW_TYPE, first.flowId().flowType());
        assertEquals(AgentPackCertificationFlowFactory.DEFINITION_VERSION, first.definitionVersion());
        assertEquals(1, first.steps().size());
        assertEquals(AgentPackCertificationFlowFactory.ISSUE_CERTIFICATION, first.steps().get(0).stepId());
        assertEquals(RecoveryPolicy.REENTER_IDEMPOTENT, first.steps().get(0).recoveryPolicy());
        assertNotSame(first.steps().get(0).step(), second.steps().get(0).step());
        assertEquals(TENANT.value(), first.executionContext().tenantIdOrNull());
        assertEquals("customer-initiator", first.executionContext().userIdOrNull());
        assertEquals(SESSION_ID.value(), first.executionContext().sessionIdOrNull());
        assertEquals("certification-flow-run-1", first.executionContext().runIdOrNull());
        assertEquals("trace-1", first.executionContext().traceIdOrNull());
        assertEquals(PROJECT_ID.value(), first.executionContext().correlationIdOrNull());
        assertTrue(factory.owns(candidateReady));
        assertTrue(factory.owns(certifying()));
        assertTrue(factory.owns(certifying().awaitAgentPackReleaseReview(
                CERTIFICATION_ID, NOW.plusSeconds(20))));
        assertFalse(factory.owns(candidateReady.requestCancellation(NOW.plusSeconds(1))));
        assertFalse(factory.owns(withProductLine(candidateReady, new ProductLineId("other-line"))));
        assertThrows(IllegalArgumentException.class, () -> factory.create(FlowId.of("other", "key")));
    }

    @Test
    void cancellingCertificationCrashRecoveryKeepsContinuationFlowRouting() {
        AgentPackCertificationFlowCoordinator holding = (id, context) ->
                io.github.flowerjvm.flower.core.step.StepResult.stay();
        var continuation = new AgentPackCertificationFlowFactory(holding);
        var primary = new CreateCustomerAgentFlowFactory((phase, context) ->
                io.github.flowerjvm.flower.core.step.StepResult.stay());
        var registry = new FactoryFlowRegistry(
                new FactoryProductLineRegistry(List.of(primary)), List.of(continuation));
        BuildSession cancelling = certifying().requestCancellation(NOW.plusSeconds(3));
        BuildSession cancelled = cancelling.confirmCancellation(
                "CANCELLED", "cancelled during certification", NOW.plusSeconds(4));

        assertFalse(continuation.owns(cancelling));
        assertFalse(continuation.owns(cancelled));
        assertTrue(continuation.controls(cancelling));
        assertTrue(continuation.controls(cancelled));
        assertEquals(FLOW_ID, registry.flowId(cancelling));
        assertEquals(FLOW_ID, registry.flowId(cancelled));

        var withoutContinuation = new FactoryFlowRegistry(
                new FactoryProductLineRegistry(List.of(primary)), List.of());
        assertThrows(IllegalStateException.class, () -> withoutContinuation.flowId(certifying()));
    }

    @Test
    void requestUsesAdvancingClockAndExactServicePrincipalActionContract() {
        Fixture fixture = new Fixture(candidateReady(), new SequenceClock(
                NOW.plusSeconds(1), NOW.plusSeconds(3)));
        fixture.requestedAt = NOW.plusSeconds(2);

        try (FlowTestHarness harness = fixture.submit()) {
            harness.tick();

            assertEquals(FlowState.RUNNING, harness.activeSnapshot(FLOW_ID).orElseThrow().state());
            assertEquals(BuildSessionStatus.CERTIFYING, fixture.sessions.current.status());
            assertEquals(1, fixture.runtime.calls.get());
            ActionProposal proposal = fixture.runtime.proposal.get();
            var actionContext = fixture.runtime.context.get();
            assertEquals(CertificationIssueAction.ACTION_ID, proposal.actionId());
            assertEquals(
                    new CertificationIssueInput(
                                    CERTIFICATION_ID,
                                    fixture.requested.inputLockArtifact().hash(),
                                    fixture.requested.version())
                            .toMap(),
                    proposal.input());
            assertEquals(
                    CertificationIssueIdempotencyKeys.derive(fixture.requested),
                    proposal.idempotencyKey());
            assertEquals(TENANT.value(), actionContext.tenantId());
            assertEquals(
                    ActionBackedAgentPackCertificationLauncher.REQUESTER_ID,
                    actionContext.userId());
            assertNotEquals("customer-initiator", actionContext.userId());
            assertEquals("action-run-1", actionContext.runId());
            assertEquals("trace-certification", actionContext.traceId());
            assertEquals(
                    Set.of(CertificationIssueAction.PERMISSION),
                    actionContext.metadata().get("actor.permissions"));
            assertEquals(CertificationIssueAction.RESOURCE_TYPE,
                    actionContext.metadata().get("resource.type"));
            assertEquals(CERTIFICATION_ID.value(), actionContext.metadata().get("resource.id"));
        }
    }

    @Test
    void deniedWithoutIntentTransitionsManualReviewAndNeverCreatesAnotherAuditRun() {
        Fixture fixture = new Fixture(candidateReady(), Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC));
        fixture.runtime.result = ActionExecutionResult.denied("CERTIFICATION_POLICY_DENIED", "denied");

        try (FlowTestHarness harness = fixture.submit()) {
            harness.tick();
            harness.tick();

            assertEquals(1, fixture.runtime.calls.get());
            assertEquals(BuildSessionStatus.MANUAL_REVIEW, fixture.sessions.current.status());
            assertEquals(
                    LedgerBackedAgentPackCertificationFlowCoordinator.REQUEST_FAILED,
                    fixture.sessions.current.terminalCode().orElseThrow());
            assertEquals(FlowState.FAILED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
        }
    }

    @Test
    void requestedWithoutIntentReplaysButPendingIntentOnlyObserves() {
        Fixture replay = new Fixture(certifying(), Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC));
        replay.certifications.current = Optional.of(replay.requested);
        try (FlowTestHarness harness = replay.submit()) {
            harness.tick();
            assertEquals(1, replay.runtime.calls.get());
            assertEquals(BuildSessionStatus.CERTIFYING, replay.sessions.current.status());
        }

        Fixture pending = new Fixture(certifying(), Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC));
        pending.certifications.current = Optional.of(pending.requested);
        pending.intents.current = Optional.of(pending.pendingIntent());
        pending.owner.set(CertificationActionEvidenceOwner.Assessment.pending());
        try (FlowTestHarness harness = pending.submit()) {
            harness.tick();
            assertEquals(0, pending.runtime.calls.get());
            assertEquals(BuildSessionStatus.CERTIFYING, pending.sessions.current.status());
            assertEquals(FlowState.RUNNING, harness.activeSnapshot(FLOW_ID).orElseThrow().state());
        }
    }

    @Test
    void canonicalCertificationAfterDeadlineStillBindsAndFinishes() {
        BuildSession certifying = certifying();
        Fixture fixture = new Fixture(
                certifying,
                Clock.fixed(certifying.deadlineAt().plusSeconds(30), ZoneOffset.UTC));
        Certification certified = fixture.requested.certify(
                lock("certification-manifest"),
                lock("certification-evidence"),
                "canonical-action-run",
                NOW.plusSeconds(4),
                Optional.empty());
        fixture.certifications.current = Optional.of(certified);
        fixture.owner.set(CertificationActionEvidenceOwner.Assessment.canonical());

        try (FlowTestHarness harness = fixture.submit()) {
            harness.tick();

            assertEquals(BuildSessionStatus.WAITING_RELEASE_REVIEW, fixture.sessions.current.status());
            assertEquals(BuildSessionPhase.HUMAN_RELEASE_REVIEW, fixture.sessions.current.currentPhase());
            assertEquals(Optional.of(CERTIFICATION_ID), fixture.sessions.current.currentCertificationId());
            assertEquals(1, fixture.sessions.casCalls.get());
            assertEquals(FlowState.FINISHED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
        }
    }

    @Test
    void boundCertificationCrashBeforeFlowCheckpointRecoversWithoutAnotherCas() {
        Certification certified = requestedCertification().certify(
                lock("certification-manifest"),
                lock("certification-evidence"),
                "canonical-action-run",
                NOW.plusSeconds(4),
                Optional.empty());
        BuildSession waiting = certifying().awaitAgentPackReleaseReview(
                CERTIFICATION_ID, NOW.plusSeconds(5));
        Fixture fixture = new Fixture(waiting, Clock.fixed(NOW.plusSeconds(6), ZoneOffset.UTC));
        fixture.certifications.current = Optional.of(certified);
        fixture.owner.set(CertificationActionEvidenceOwner.Assessment.canonical());

        try (FlowTestHarness harness = fixture.submit()) {
            harness.tick();
            assertEquals(FlowState.FINISHED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
            assertEquals(waiting, fixture.sessions.current);
            assertEquals(0, fixture.sessions.casCalls.get());
            assertEquals(0, fixture.runtime.calls.get());
        }
    }

    @Test
    void certifiedWithInvalidTerminalOwnerMovesToManualReview() {
        Fixture fixture = new Fixture(certifying(), Clock.fixed(NOW.plusSeconds(6), ZoneOffset.UTC));
        fixture.certifications.current = Optional.of(fixture.requested.certify(
                lock("certification-manifest"),
                lock("certification-evidence"),
                "invalid-action-run",
                NOW.plusSeconds(4),
                Optional.empty()));
        fixture.owner.set(new CertificationActionEvidenceOwner.Assessment(
                CertificationActionEvidenceOwner.Status.INVALID_TERMINAL,
                "CERTIFICATION_ACTION_TERMINAL_MISMATCH"));

        try (FlowTestHarness harness = fixture.submit()) {
            harness.tick();
            assertEquals(BuildSessionStatus.MANUAL_REVIEW, fixture.sessions.current.status());
            assertEquals(
                    LedgerBackedAgentPackCertificationFlowCoordinator.ACTION_OWNER_INVALID,
                    fixture.sessions.current.terminalCode().orElseThrow());
            assertEquals(FlowState.FAILED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
        }
    }

    @Test
    void notCertifiedBlocksAndOrphanedIntentRequiresManualReview() {
        Fixture rejected = new Fixture(certifying(), Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC));
        rejected.certifications.current = Optional.of(
                rejected.requested.reject("GATE_FAILED", NOW.plusSeconds(4)));
        try (FlowTestHarness harness = rejected.submit()) {
            harness.tick();
            assertEquals(BuildSessionStatus.BLOCKED, rejected.sessions.current.status());
            assertEquals(
                    LedgerBackedAgentPackCertificationFlowCoordinator.NOT_CERTIFIED,
                    rejected.sessions.current.terminalCode().orElseThrow());
            assertEquals(FlowState.FAILED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
        }

        Fixture orphaned = new Fixture(certifying(), Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC));
        orphaned.certifications.current = Optional.of(orphaned.requested);
        CertificationDispatchIntent running = orphaned.pendingIntent().claim(
                "claim", NOW.plusSeconds(3), Duration.ofSeconds(1));
        orphaned.intents.current = Optional.of(running.orphan(
                "claim", "ACTION_ORPHANED", NOW.plusSeconds(4)));
        orphaned.owner.set(new CertificationActionEvidenceOwner.Assessment(
                CertificationActionEvidenceOwner.Status.ORPHANED, "ACTION_ORPHANED"));
        try (FlowTestHarness harness = orphaned.submit()) {
            harness.tick();
            assertEquals(BuildSessionStatus.MANUAL_REVIEW, orphaned.sessions.current.status());
            assertEquals(
                    LedgerBackedAgentPackCertificationFlowCoordinator.ACTION_OWNER_INVALID,
                    orphaned.sessions.current.terminalCode().orElseThrow());
            assertEquals(FlowState.FAILED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
        }
    }

    @Test
    void deadlineAndCancellationFailClosedWithoutActionProposal() {
        BuildSession candidateReady = candidateReady();
        Fixture deadline = new Fixture(
                candidateReady,
                Clock.fixed(candidateReady.deadlineAt(), ZoneOffset.UTC));
        try (FlowTestHarness harness = deadline.submit()) {
            harness.tick();
            assertEquals(0, deadline.runtime.calls.get());
            assertEquals(BuildSessionStatus.BLOCKED, deadline.sessions.current.status());
            assertEquals(FlowState.FAILED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
        }

        Fixture cancelled = new Fixture(candidateReady, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        Flow flow = cancelled.factory().create(candidateReady, "flow-run", "trace-certification");
        cancelled.sessions.current = candidateReady.requestCancellation(NOW.plusSeconds(1));
        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(flow);
            harness.tick();
            assertEquals(0, cancelled.runtime.calls.get());
            assertEquals(FlowState.FAILED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
        }
    }

    @Test
    void buildSessionCertificationTransitionsRejectWrongAuthorityAndPreserveExactBinding() {
        BuildSession certifying = certifying();
        BuildSession waiting = certifying.awaitAgentPackReleaseReview(
                CERTIFICATION_ID, certifying.deadlineAt().plusSeconds(1));
        assertEquals(BuildSessionStatus.WAITING_RELEASE_REVIEW, waiting.status());
        assertEquals(Optional.of(CERTIFICATION_ID), waiting.currentCertificationId());
        assertEquals(certifying.version() + 1, waiting.version());

        BuildSession blocked = certifying.blockAgentPackCertification(
                "GATE_FAILED", "not certified", NOW.plusSeconds(3));
        assertEquals(BuildSessionStatus.BLOCKED, blocked.status());
        assertTrue(blocked.currentCertificationId().isEmpty());
        BuildSession review = certifying.manualReviewAgentPackCertification(
                "OWNER_INVALID", "ambiguous owner", NOW.plusSeconds(3));
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, review.status());
        assertThrows(IllegalStateException.class, () -> candidateReady().awaitAgentPackReleaseReview(
                CERTIFICATION_ID, NOW.plusSeconds(3)));
        assertThrows(IllegalStateException.class, () -> withProductLine(
                        certifying, new ProductLineId("other-line"))
                .blockAgentPackCertification("BLOCKED", "wrong line", NOW.plusSeconds(3)));
    }

    @Test
    void exactCandidateRepositoryQueryDefaultFailsClosed() {
        CertificationRepository repository = new CertificationRepository() {
            @Override
            public void create(Certification certification) {}

            @Override
            public Optional<Certification> find(TenantId tenantId, CertificationId certificationId) {
                return Optional.empty();
            }

            @Override
            public boolean compareAndSet(Certification expected, Certification next) {
                return false;
            }
        };
        assertThrows(UnsupportedOperationException.class, () -> repository.findLatestForCandidate(
                TENANT, SESSION_ID, CANDIDATE_ID, CANDIDATE_HASH));
    }

    private static final class Fixture {
        private final MemoryBuildSessions sessions;
        private final MemoryCertifications certifications = new MemoryCertifications();
        private final MemoryIntents intents = new MemoryIntents();
        private final AtomicReference<CertificationActionEvidenceOwner.Assessment> owner =
                new AtomicReference<>(CertificationActionEvidenceOwner.Assessment.pending());
        private final CapturingRuntime runtime = new CapturingRuntime();
        private final Clock clock;
        private Instant requestedAt = NOW.plusSeconds(2);
        private final Certification requested = requestedCertification();

        private Fixture(BuildSession initial, Clock clock) {
            this.sessions = new MemoryBuildSessions(initial);
            this.clock = clock;
        }

        private AgentPackCertificationFlowFactory factory() {
            Queue<String> ids = new ArrayDeque<>();
            ids.add("proposal-1");
            ids.add("action-run-1");
            ids.add("proposal-2");
            ids.add("action-run-2");
            var launcher = new ActionBackedAgentPackCertificationLauncher(runtime, ids::remove);
            AgentPackCertificationRequester requester = (tenantId, buildSessionId) -> {
                assertEquals(TENANT, tenantId);
                assertEquals(SESSION_ID, buildSessionId);
                BuildSession expected = sessions.current;
                BuildSession next = expected.status() == BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE
                        ? expected.beginAgentPackCertification(requestedAt)
                        : expected;
                sessions.current = next;
                certifications.current = Optional.of(requested);
                return new AgentPackCertificationRequestOutcome(
                        CertificationRequestDisposition.CREATED, requested, next);
            };
            var coordinator = new LedgerBackedAgentPackCertificationFlowCoordinator(
                    sessions,
                    certifications,
                    intents,
                    requester,
                    launcher,
                    certification -> owner.get(),
                    clock);
            return new AgentPackCertificationFlowFactory(coordinator);
        }

        private FlowTestHarness submit() {
            FlowTestHarness harness = FlowTestHarness.create();
            harness.submit(factory().create(
                    sessions.current, "flow-run", "trace-certification"));
            return harness;
        }

        private CertificationDispatchIntent pendingIntent() {
            CertificationIssueInput input = new CertificationIssueInput(
                    requested.certificationId(),
                    requested.inputLockArtifact().hash(),
                    requested.version());
            return CertificationDispatchIntent.pending(
                    CertificationDispatchOperationIds.derive(TENANT, input),
                    TENANT,
                    CERTIFICATION_ID,
                    input.inputLockManifestHash(),
                    input.expectedCertificationVersion(),
                    "action-run-owner",
                    CertificationAttemptTokens.hash("attempt-token"),
                    certifying().deadlineAt(),
                    NOW.plusSeconds(2));
        }
    }

    private static final class CapturingRuntime implements ActionRuntime {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ActionProposal> proposal = new AtomicReference<>();
        private final AtomicReference<io.github.flowerjvm.flower.action.runtime.ExecutionContext> context =
                new AtomicReference<>();
        private ActionExecutionResult result =
                ActionExecutionResult.accepted("WAITING_EXTERNAL", Map.of());

        @Override
        public ActionExecutionResult handle(
                ActionProposal proposal,
                io.github.flowerjvm.flower.action.runtime.ExecutionContext context) {
            calls.incrementAndGet();
            this.proposal.set(proposal);
            this.context.set(context);
            return result;
        }
    }

    private static final class MemoryBuildSessions implements BuildSessionRepository {
        private BuildSession current;
        private final AtomicInteger casCalls = new AtomicInteger();

        private MemoryBuildSessions(BuildSession current) {
            this.current = current;
        }

        @Override
        public void create(BuildSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BuildSession> find(TenantId tenantId, BuildSessionId buildSessionId) {
            return current.tenantId().equals(tenantId)
                            && current.buildSessionId().equals(buildSessionId)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            if (!current.equals(expected)) {
                return false;
            }
            current = next;
            casCalls.incrementAndGet();
            return true;
        }
    }

    private static final class MemoryCertifications implements CertificationRepository {
        private Optional<Certification> current = Optional.empty();

        @Override
        public void create(Certification certification) {
            current = Optional.of(certification);
        }

        @Override
        public Optional<Certification> find(TenantId tenantId, CertificationId certificationId) {
            return current.filter(value -> value.inputLock().tenantId().equals(tenantId)
                    && value.certificationId().equals(certificationId));
        }

        @Override
        public Optional<Certification> findLatestForCandidate(
                TenantId tenantId,
                BuildSessionId buildSessionId,
                CandidateId candidateId,
                ContentHash candidateHash) {
            return current.filter(value -> value.inputLock().tenantId().equals(tenantId)
                    && value.inputLock().buildSessionId().equals(buildSessionId)
                    && value.inputLock().candidateId().equals(candidateId)
                    && value.inputLock().candidateHash().equals(candidateHash));
        }

        @Override
        public boolean compareAndSet(Certification expected, Certification next) {
            if (current.filter(expected::equals).isEmpty()) {
                return false;
            }
            current = Optional.of(next);
            return true;
        }
    }

    private static final class MemoryIntents implements CertificationDispatchIntentRepository {
        private Optional<CertificationDispatchIntent> current = Optional.empty();

        @Override
        public void create(CertificationDispatchIntent intent) {
            current = Optional.of(intent);
        }

        @Override
        public Optional<CertificationDispatchIntent> find(String operationId) {
            return current.filter(value -> value.operationId().equals(operationId));
        }

        @Override
        public Optional<CertificationDispatchIntent> findLatest(
                TenantId tenantId, CertificationId certificationId) {
            return current.filter(value -> value.tenantId().equals(tenantId)
                    && value.certificationId().equals(certificationId));
        }

        @Override
        public Optional<CertificationDispatchIntent> claimNext(
                Instant now, Duration lease, String claimToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CertificationDispatchIntent> claimExpiredRunning(
                Instant now, Duration lease, String claimToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean compareAndSet(
                CertificationDispatchIntent expected, CertificationDispatchIntent next) {
            if (current.filter(expected::equals).isEmpty()) {
                return false;
            }
            current = Optional.of(next);
            return true;
        }
    }

    private static final class SequenceClock extends Clock {
        private final Queue<Instant> instants = new ArrayDeque<>();
        private Instant last;

        private SequenceClock(Instant... instants) {
            for (Instant instant : instants) {
                this.instants.add(instant);
                last = instant;
            }
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock is UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instants.isEmpty() ? last : instants.remove();
        }
    }

    private static BuildSession candidateReady() {
        return new BuildSession(
                SESSION_ID,
                TENANT,
                PROJECT_ID,
                ProductLineId.AGENT_PACK,
                "request-certification-flow",
                "customer-initiator",
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                new ArtifactReference("artifact:requirements"),
                hash("requirements"),
                Optional.of("manager"),
                Optional.of("coding"),
                Optional.of(new ArtifactReference("artifact:blueprint")),
                Optional.of(CANDIDATE_ID),
                Optional.of(CANDIDATE_HASH),
                Optional.empty(),
                0,
                3,
                NOW,
                NOW.plusSeconds(10),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                6,
                NOW,
                NOW);
    }

    private static BuildSession certifying() {
        return candidateReady().beginAgentPackCertification(NOW.plusSeconds(2));
    }

    private static BuildSession withProductLine(BuildSession session, ProductLineId productLineId) {
        return new BuildSession(
                session.buildSessionId(),
                session.tenantId(),
                session.projectId(),
                productLineId,
                session.requestIdempotencyKey(),
                session.createdBy(),
                session.status(),
                session.currentPhase(),
                session.requirementsArtifactRef(),
                session.requirementsHash(),
                session.selectedManagerWorkerBinding(),
                session.selectedCodingWorkerBinding(),
                session.currentBlueprintRef(),
                session.currentCandidateId(),
                session.currentCandidateHash(),
                session.currentCertificationId(),
                session.repairRound(),
                session.maxRepairRounds(),
                session.startedAt(),
                session.deadlineAt(),
                session.cancellationRequestedAt(),
                session.terminalCode(),
                session.terminalMessage(),
                session.version(),
                session.createdAt(),
                session.updatedAt());
    }

    private static Certification requestedCertification() {
        CertificationArtifactLock source = lock("source");
        CertificationInputLock inputLock = new CertificationInputLock(
                CertificationInputLock.SCHEMA_VERSION,
                TENANT,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                SESSION_ID,
                new WorkOrderId("work-certification-flow"),
                CANDIDATE_ID,
                CANDIDATE_HASH,
                source,
                lock("dependency"),
                lock("toolchain"),
                lock("generation-input"),
                lock("product-contract"),
                lock("api-signature"),
                "sha256-ordinal-v1",
                "internal",
                new VerificationRunId("verification-certification-flow"),
                "verification-action-run",
                lock("verification-result"),
                hash("fixture-set"),
                lock("policy-snapshot"),
                lock("compatibility"),
                "internal",
                "0.2.0",
                "0.1.3",
                "0.3.3");
        return Certification.requested(
                CERTIFICATION_ID, inputLock, lock("certification-input"), NOW.plusSeconds(2));
    }

    private static CertificationArtifactLock lock(String name) {
        return new CertificationArtifactLock(new ArtifactReference("artifact:" + name), hash(name));
    }

    private static ContentHash hash(String material) {
        try {
            return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
