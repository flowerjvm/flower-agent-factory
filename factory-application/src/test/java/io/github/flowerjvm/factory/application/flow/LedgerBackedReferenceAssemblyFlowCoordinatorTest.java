package io.github.flowerjvm.factory.application.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAttemptTokens;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntent;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchOperationIds;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchRunner;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewResult;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.flow.FlowState;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;
import io.github.flowerjvm.flower.testkit.FlowTestHarness;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LedgerBackedReferenceAssemblyFlowCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-02T06:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant-reference-flow");
    private static final BuildSessionId SESSION_ID =
            new BuildSessionId("build-reference-flow");
    private static final ProjectId PROJECT_ID = new ProjectId("project-reference-flow");
    private static final ReferenceAssemblyId ASSEMBLY_ID =
            new ReferenceAssemblyId("reference-assembly-flow");
    private static final FlowId FLOW_ID =
            FlowId.of(ReferenceAssemblyFlowFactory.FLOW_TYPE, SESSION_ID.value());
    private static final CertificationArtifactLock REQUIREMENT = lock("requirement", 'a');
    private static final CertificationArtifactLock CONSUMER_CONTRACT =
            lock("consumer-contract", 'b');
    private static final CertificationArtifactLock HOST_FIXTURE = lock("host-fixture", 'c');
    private static final CertificationArtifactLock POLICY = lock("policy", 'd');
    private static final CertificationArtifactLock COMPONENT_MANIFEST =
            lock("component-manifest", 'e');
    private static final CertificationArtifactLock ASSEMBLY_MANIFEST =
            lock("assembly-manifest", 'f');
    private static final CertificationArtifactLock INSPECTION_REPORT =
            lock("inspection-report", '1');
    private static final CertificationArtifactLock RELEASE_SUBJECT =
            lock("release-subject", '2');
    private static final CertificationArtifactLock RELEASE_MANIFEST =
            lock("release-manifest", '4');

    @Test
    void eachTickProjectsExactlyOneDurableAggregateOrSessionTransitionThroughReviewWait() {
        Fixture fixture = new Fixture(session(
                BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER));

        try (FlowTestHarness harness = fixture.submit()) {
            harness.tick();
            assertEquals(ReferenceAssemblyStatus.REQUESTED, fixture.assembly().status());
            assertSession(
                    fixture,
                    BuildSessionStatus.RUNNING,
                    BuildSessionPhase.UNDERSTAND_CUSTOMER,
                    0,
                    0);
            assertEquals(1, fixture.requestCalls);

            harness.tick();
            assertSession(
                    fixture,
                    BuildSessionStatus.RUNNING,
                    BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                    1,
                    0);

            harness.tick();
            assertEquals(ReferenceAssemblyStatus.COMPONENT_RESOLVED, fixture.assembly().status());
            assertSession(
                    fixture,
                    BuildSessionStatus.RUNNING,
                    BuildSessionPhase.RESOLVE_REUSE_STRATEGY,
                    1,
                    1);
            assertEquals(1, fixture.resolveCalls);

            harness.tick();
            assertSession(
                    fixture,
                    BuildSessionStatus.RUNNING,
                    BuildSessionPhase.ASSEMBLE_CANDIDATE,
                    2,
                    1);

            harness.tick();
            assertEquals(ReferenceAssemblyStatus.ASSEMBLED, fixture.assembly().status());
            assertEquals(Optional.of(ASSEMBLY_MANIFEST), fixture.assembly().assemblyManifest());
            assertSession(
                    fixture,
                    BuildSessionStatus.RUNNING,
                    BuildSessionPhase.ASSEMBLE_CANDIDATE,
                    2,
                    2);
            assertEquals(1, fixture.assembleCalls);

            harness.tick();
            assertSession(
                    fixture,
                    BuildSessionStatus.RUNNING,
                    BuildSessionPhase.TEST,
                    3,
                    2);

            harness.tick();
            assertEquals(ReferenceAssemblyStatus.INSPECTED, fixture.assembly().status());
            assertEquals(Optional.of(INSPECTION_REPORT), fixture.assembly().inspectionReport());
            assertSession(
                    fixture,
                    BuildSessionStatus.RUNNING,
                    BuildSessionPhase.TEST,
                    3,
                    3);
            assertEquals(1, fixture.inspectCalls);

            harness.tick();
            assertSession(
                    fixture,
                    BuildSessionStatus.WAITING_RELEASE_REVIEW,
                    BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                    4,
                    3);
            assertEquals(
                    ReferenceAssemblyFlowFactory.WAIT_REFERENCE_RELEASE_REVIEW,
                    harness.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());
        }
    }

    @Test
    void aggregateBeforeCheckpointCrashReentryAdvancesOnlyTheLaggingSession() {
        ReferenceAssembly resolved = requestedAssembly().resolveComponent(NOW);
        Fixture fixture = new Fixture(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.RESOLVE_REUSE_STRATEGY),
                resolved);

        try (FlowTestHarness harness = fixture.submit()) {
            harness.tick();
            assertEquals(BuildSessionPhase.RESOLVE_REUSE_STRATEGY, fixture.session().currentPhase());
            assertEquals(0, fixture.sessions.casCalls);
            assertEquals(0, fixture.assemblies.casCalls);

            harness.tick();
            assertEquals(BuildSessionPhase.ASSEMBLE_CANDIDATE, fixture.session().currentPhase());
            assertEquals(1, fixture.sessions.casCalls);
            assertEquals(0, fixture.assemblies.casCalls);
            assertEquals(0, fixture.resolveCalls);
        }
    }

    @Test
    void phaseAheadCheckpointCatchUpSkipsCompletedWorkBeforeInspectingOnce() {
        ReferenceAssembly assembled = requestedAssembly()
                .resolveComponent(NOW)
                .assemble(ASSEMBLY_MANIFEST, NOW);
        Fixture fixture = new Fixture(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.TEST), assembled);

        try (FlowTestHarness harness = fixture.submit()) {
            tick(harness, 3);
            assertEquals(0, fixture.requestCalls);
            assertEquals(0, fixture.resolveCalls);
            assertEquals(0, fixture.assembleCalls);
            assertEquals(0, fixture.assemblies.casCalls);
            assertEquals(BuildSessionPhase.TEST, fixture.session().currentPhase());

            harness.tick();
            assertEquals(ReferenceAssemblyStatus.INSPECTED, fixture.assembly().status());
            assertEquals(1, fixture.inspectCalls);
            assertEquals(1, fixture.assemblies.casCalls);
            assertEquals(0, fixture.sessions.casCalls);
        }
    }

    @Test
    void rejectedInspectionPersistsEvidenceThenFailsTheSessionOnTheNextTick() {
        Fixture fixture = new Fixture(session(
                BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER));
        fixture.inspectionPassed = false;

        try (FlowTestHarness harness = fixture.submit()) {
            tick(harness, 6);
            harness.tick();

            assertEquals(ReferenceAssemblyStatus.REJECTED, fixture.assembly().status());
            assertEquals(Optional.of(INSPECTION_REPORT), fixture.assembly().inspectionReport());
            assertEquals(BuildSessionStatus.RUNNING, fixture.session().status());

            harness.tick();
            assertEquals(BuildSessionStatus.FAILED, fixture.session().status());
            assertEquals(
                    LedgerBackedReferenceAssemblyFlowCoordinator.INSPECTION_REJECTED,
                    fixture.session().terminalCode().orElseThrow());
            assertEquals(FlowState.FAILED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
        }
    }

    @Test
    void tenantAndPrincipalIdentityMismatchFailsClosedBeforeAnyLedgerMutation() {
        Fixture fixture = new Fixture(session(
                BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER));

        StepResult result = fixture.coordinator().advance(
                ReferenceAssemblyBuildPhase.ACCEPT_REFERENCE_REQUIREMENTS,
                SESSION_ID,
                stepContext(TENANT.value(), "different-principal"));

        assertEquals(StepResult.Type.FAIL, result.type());
        assertTrue(result.cause()
                .getMessage()
                .contains(LedgerBackedReferenceAssemblyFlowCoordinator.FLOW_IDENTITY_MISMATCH));
        assertEquals(0, fixture.requestCalls);
        assertEquals(0, fixture.sessions.casCalls);
        assertEquals(0, fixture.assemblies.casCalls);

        StepResult wrongTenant = fixture.coordinator().advance(
                ReferenceAssemblyBuildPhase.ACCEPT_REFERENCE_REQUIREMENTS,
                SESSION_ID,
                stepContext("tenant-not-authoritative", "reference-requester"));
        assertEquals(StepResult.Type.FAIL, wrongTenant.type());
        assertTrue(wrongTenant.cause()
                .getMessage()
                .contains(LedgerBackedReferenceAssemblyFlowCoordinator.SESSION_NOT_FOUND));
        assertEquals(0, fixture.requestCalls);
    }

    @Test
    void approvedReviewAdvancesThenOneProposalCreatesAnObservedActiveIntent() {
        Fixture fixture = new Fixture(session(
                BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER));

        try (FlowTestHarness harness = fixture.submit()) {
            tick(harness, 8);

            harness.tick();
            assertTrue(fixture.assembly().releaseDecisionPointId().isPresent());
            assertEquals(BuildSessionStatus.WAITING_RELEASE_REVIEW, fixture.session().status());
            assertEquals(1, fixture.reviewCalls);

            fixture.approveReview();
            harness.tick();
            assertSession(
                    fixture,
                    BuildSessionStatus.RUNNING,
                    BuildSessionPhase.PACKAGE_RELEASE,
                    5,
                    4);

            harness.tick();
            assertEquals(1, fixture.releaseProposalCalls);
            assertTrue(fixture.assembly().releaseActionRunId().isPresent());
            assertEquals(
                    ReferenceAssemblyReleaseDispatchIntentStatus.PENDING,
                    fixture.intents.current.orElseThrow().status());

            harness.tick();
            assertEquals(1, fixture.releaseProposalCalls);
            assertEquals(BuildSessionStatus.RUNNING, fixture.session().status());
            assertEquals(BuildSessionPhase.PACKAGE_RELEASE, fixture.session().currentPhase());
            assertEquals(FlowState.RUNNING, harness.activeSnapshot(FLOW_ID).orElseThrow().state());
        }
    }

    @Test
    void lostProposalResultReReadsTheExactCommittedOwnerAndIntent() {
        Fixture fixture = new Fixture(session(
                BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER));
        fixture.releaseThrowsAfterCommit = true;

        try (FlowTestHarness harness = fixture.submit()) {
            tick(harness, 9);
            fixture.approveReview();
            harness.tick();

            harness.tick();

            assertEquals(1, fixture.releaseProposalCalls);
            assertTrue(fixture.assembly().releaseActionRunId().isPresent());
            assertEquals(
                    ReferenceAssemblyReleaseDispatchIntentStatus.PENDING,
                    fixture.intents.current.orElseThrow().status());
            assertEquals(BuildSessionStatus.RUNNING, fixture.session().status());
            assertEquals(BuildSessionPhase.PACKAGE_RELEASE, fixture.session().currentPhase());
            assertEquals(FlowState.RUNNING, harness.activeSnapshot(FLOW_ID).orElseThrow().state());
        }
    }

    @Test
    void releasedLedgerWithoutV14IntentEntersManualReview() {
        ReleaseLedger release = releaseLedger();
        Fixture fixture = new Fixture(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.PACKAGE_RELEASE),
                release.released());

        StepResult result = fixture.coordinator().advance(
                ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY,
                SESSION_ID,
                stepContext(TENANT.value(), "reference-requester"));

        assertEquals(StepResult.Type.FAIL, result.type());
        assertTrue(result.cause().getMessage().contains(
                LedgerBackedReferenceAssemblyFlowCoordinator.RELEASE_OWNER_INVALID));
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, fixture.session().status());
        assertEquals(
                Optional.of(LedgerBackedReferenceAssemblyFlowCoordinator.RELEASE_OWNER_INVALID),
                fixture.session().terminalCode());
        assertEquals(1, fixture.sessions.casCalls);
    }

    @Test
    void releasedLedgerWithMismatchedV14IntentEntersManualReview() {
        ReleaseLedger release = releaseLedger();
        ReferenceAssemblyReleaseDispatchIntent exact = release.pendingIntent();
        ReferenceAssemblyReleaseInput input = new ReferenceAssemblyReleaseInput(
                exact.referenceAssemblyId(),
                exact.assemblyManifestHash(),
                exact.inspectionReportHash(),
                exact.releaseDecisionPointId(),
                exact.releaseSubjectHash(),
                exact.expectedReferenceAssemblyVersion());
        ReferenceAssemblyReleaseDispatchIntent mismatched =
                ReferenceAssemblyReleaseDispatchIntent.pending(
                        exact.operationId(),
                        exact.tenantId(),
                        input,
                        "mismatched-reference-release-action",
                        exact.attemptTokenHash(),
                        exact.deadlineAt(),
                        exact.createdAt());
        Fixture fixture = new Fixture(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.PACKAGE_RELEASE),
                release.released());
        fixture.intents.current = Optional.of(mismatched);

        StepResult result = fixture.coordinator().advance(
                ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY,
                SESSION_ID,
                stepContext(TENANT.value(), "reference-requester"));

        assertEquals(StepResult.Type.FAIL, result.type());
        assertTrue(result.cause().getMessage().contains(
                LedgerBackedReferenceAssemblyFlowCoordinator.RELEASE_OWNER_INVALID));
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, fixture.session().status());
        assertEquals(
                Optional.of(LedgerBackedReferenceAssemblyFlowCoordinator.RELEASE_OWNER_INVALID),
                fixture.session().terminalCode());
        assertEquals(1, fixture.sessions.casCalls);
    }

    @Test
    void releasedLedgerWithExactTerminalNonCompletedIntentEntersManualReview() {
        ReleaseLedger release = releaseLedger();
        ReferenceAssemblyReleaseDispatchIntent orphaned = release.pendingIntent()
                .claim("release-orphan-claim", NOW.minusSeconds(13), Duration.ofMinutes(1))
                .orphan(
                        "release-orphan-claim",
                        "REFERENCE_ASSEMBLY_RELEASE_ACTION_TERMINAL_CONFLICT",
                        NOW.minusSeconds(12));
        Fixture fixture = new Fixture(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.PACKAGE_RELEASE),
                release.released());
        fixture.intents.current = Optional.of(orphaned);

        StepResult result = fixture.coordinator().advance(
                ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY,
                SESSION_ID,
                stepContext(TENANT.value(), "reference-requester"));

        assertEquals(StepResult.Type.FAIL, result.type());
        assertTrue(result.cause().getMessage().contains(
                LedgerBackedReferenceAssemblyFlowCoordinator.RELEASE_OWNER_INVALID));
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, fixture.session().status());
        assertEquals(
                Optional.of(LedgerBackedReferenceAssemblyFlowCoordinator.RELEASE_OWNER_INVALID),
                fixture.session().terminalCode());
        assertEquals(1, fixture.sessions.casCalls);
    }

    @Test
    void releasedLedgerWithCompletedIntentButWrongSuccessCodeEntersManualReview() {
        ReleaseLedger release = releaseLedger();
        ReferenceAssemblyReleaseDispatchIntent conflicting = release.pendingIntent()
                .claim("release-code-claim", NOW.minusSeconds(13), Duration.ofMinutes(1))
                .complete(
                        "release-code-claim",
                        ReferenceAssemblyReleaseDispatchRunner.DEADLINE_EXCEEDED,
                        NOW.minusSeconds(12));
        Fixture fixture = new Fixture(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.PACKAGE_RELEASE),
                release.released());
        fixture.intents.current = Optional.of(conflicting);

        StepResult result = fixture.coordinator().advance(
                ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY,
                SESSION_ID,
                stepContext(TENANT.value(), "reference-requester"));

        assertEquals(StepResult.Type.FAIL, result.type());
        assertTrue(result.cause().getMessage().contains(
                LedgerBackedReferenceAssemblyFlowCoordinator.RELEASE_OWNER_INVALID));
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, fixture.session().status());
        assertEquals(
                Optional.of(LedgerBackedReferenceAssemblyFlowCoordinator.RELEASE_OWNER_INVALID),
                fixture.session().terminalCode());
        assertEquals(1, fixture.sessions.casCalls);
    }

    @Test
    void releasedLedgerWithExactNonTerminalIntentKeepsWaitingForReconciliation() {
        ReleaseLedger release = releaseLedger();
        Fixture fixture = new Fixture(
                session(BuildSessionStatus.RUNNING, BuildSessionPhase.PACKAGE_RELEASE),
                release.released());
        fixture.intents.current = Optional.of(release.pendingIntent());

        StepResult result = fixture.coordinator().advance(
                ReferenceAssemblyBuildPhase.RELEASE_REFERENCE_ASSEMBLY,
                SESSION_ID,
                stepContext(TENANT.value(), "reference-requester"));

        assertEquals(StepResult.Type.STAY, result.type());
        assertEquals(BuildSessionStatus.RUNNING, fixture.session().status());
        assertEquals(BuildSessionPhase.PACKAGE_RELEASE, fixture.session().currentPhase());
        assertEquals(0, fixture.sessions.casCalls);
    }

    private static void assertSession(
            Fixture fixture,
            BuildSessionStatus status,
            BuildSessionPhase phase,
            int sessionCasCalls,
            int assemblyCasCalls) {
        assertEquals(status, fixture.session().status());
        assertEquals(phase, fixture.session().currentPhase());
        assertEquals(sessionCasCalls, fixture.sessions.casCalls);
        assertEquals(assemblyCasCalls, fixture.assemblies.casCalls);
    }

    private static void tick(FlowTestHarness harness, int count) {
        for (int index = 0; index < count; index++) {
            harness.tick();
        }
    }

    private static final class Fixture {
        private final MemoryBuildSessions sessions;
        private final MemoryAssemblies assemblies;
        private final MemoryDecisionPoints decisions = new MemoryDecisionPoints();
        private final MemoryReleaseIntents intents = new MemoryReleaseIntents();
        private boolean inspectionPassed = true;
        private int requestCalls;
        private int resolveCalls;
        private int assembleCalls;
        private int inspectCalls;
        private int reviewCalls;
        private int releaseProposalCalls;
        private boolean releaseThrowsAfterCommit;
        private ReferenceAssemblyReleaseSubject releaseSubject;

        private Fixture(BuildSession initial) {
            this(initial, null);
        }

        private Fixture(BuildSession initial, ReferenceAssembly assembly) {
            this.sessions = new MemoryBuildSessions(initial);
            this.assemblies = new MemoryAssemblies(assembly);
        }

        private LedgerBackedReferenceAssemblyFlowCoordinator coordinator() {
            return new LedgerBackedReferenceAssemblyFlowCoordinator(
                    sessions,
                    assemblies,
                    decisions,
                    this::ensureRequested,
                    this::resolve,
                    this::assemble,
                    this::inspect,
                    this::review,
                    this::launchRelease,
                    intents,
                    ignored -> Optional.empty(),
                    RunStore.noop(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private FlowTestHarness submit() {
            FlowTestHarness harness = FlowTestHarness.create();
            harness.submit(new ReferenceAssemblyFlowFactory(coordinator())
                    .create(session(), "reference-flow-run", "reference-flow-trace"));
            return harness;
        }

        private BuildSession session() {
            return sessions.current;
        }

        private ReferenceAssembly assembly() {
            return assemblies.current.orElseThrow();
        }

        private ReferenceAssembly ensureRequested(
                TenantId tenantId, BuildSessionId buildSessionId) {
            requestCalls++;
            assertEquals(TENANT, tenantId);
            assertEquals(SESSION_ID, buildSessionId);
            if (assemblies.current.isEmpty()) {
                assemblies.create(requestedAssembly());
            }
            return assembly();
        }

        private void resolve(
                TenantId tenantId, CertificationArtifactLock requirement) {
            resolveCalls++;
            assertEquals(TENANT, tenantId);
            assertEquals(REQUIREMENT, requirement);
        }

        private CertificationArtifactLock assemble(
                TenantId tenantId, CertificationArtifactLock requirement) {
            assembleCalls++;
            assertEquals(TENANT, tenantId);
            assertEquals(REQUIREMENT, requirement);
            return ASSEMBLY_MANIFEST;
        }

        private LedgerBackedReferenceAssemblyFlowCoordinator.InspectionProjection inspect(
                TenantId tenantId, CertificationArtifactLock manifest) {
            inspectCalls++;
            assertEquals(TENANT, tenantId);
            assertEquals(ASSEMBLY_MANIFEST, manifest);
            return new LedgerBackedReferenceAssemblyFlowCoordinator.InspectionProjection(
                    inspectionPassed, INSPECTION_REPORT);
        }

        private ReferenceAssemblyReleaseReviewResult review(
                TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
            reviewCalls++;
            assertEquals(TENANT, tenantId);
            assertEquals(ASSEMBLY_ID, referenceAssemblyId);
            ReferenceAssembly current = assembly();
            if (current.releaseDecisionPointId().isEmpty()) {
                long inspectedVersion = current.version();
                DecisionPointId pointId = new DecisionPointId("reference-release-point");
                releaseSubject = new ReferenceAssemblyReleaseSubject(
                        ReferenceAssemblyReleaseSubject.SCHEMA_VERSION,
                        ProductLineId.REFERENCE_ASSEMBLY,
                        ASSEMBLY_ID,
                        inspectedVersion,
                        ASSEMBLY_MANIFEST,
                        INSPECTION_REPORT,
                        current.componentCertificationId(),
                        current.componentCandidateHash(),
                        current.componentCertificationManifest(),
                        current.policySnapshot());
                ReferenceAssembly bound = current.bindReleaseReview(
                        pointId, RELEASE_SUBJECT.hash(), NOW);
                assertTrue(assemblies.compareAndSet(current, bound));
                DecisionPoint open = decisionPoint(pointId, DecisionPointStatus.OPEN, inspectedVersion);
                decisions.current = Optional.of(open);
                return new ReferenceAssemblyReleaseReviewResult(
                        ReferenceAssemblyReleaseReviewResult.Disposition.CREATED_AND_BOUND,
                        bound,
                        open,
                        releaseSubject,
                        RELEASE_SUBJECT);
            }
            return new ReferenceAssemblyReleaseReviewResult(
                    ReferenceAssemblyReleaseReviewResult.Disposition.EXISTING_EXACT,
                    current,
                    decisions.current.orElseThrow(),
                    releaseSubject,
                    RELEASE_SUBJECT);
        }

        private void approveReview() {
            DecisionPoint open = decisions.current.orElseThrow();
            decisions.current = Optional.of(new DecisionPoint(
                    open.decisionPointId(),
                    open.tenantId(),
                    open.buildSessionId(),
                    open.type(),
                    DecisionPointStatus.APPROVED,
                    open.subjectType(),
                    open.subjectId(),
                    open.subjectVersion(),
                    open.subjectHash(),
                    open.questionArtifactRef(),
                    open.optionsSchemaId(),
                    open.requiredPermissions(),
                    open.minimumApprovers(),
                    open.policySnapshotRef(),
                    open.openedAt(),
                    open.dueAt(),
                    Optional.of(NOW),
                    Optional.of(new DecisionId("reference-release-approval")),
                    open.version() + 1));
        }

        private ActionExecutionResult launchRelease(
                BuildSession buildSession,
                ReferenceAssembly referenceAssembly,
                ExecutionContext flowIdentity,
                Instant proposedAt) {
            releaseProposalCalls++;
            assertEquals(session(), buildSession);
            assertEquals(assembly(), referenceAssembly);
            assertEquals(TENANT.value(), flowIdentity.tenantIdOrNull());
            assertEquals(NOW, proposedAt);
            String actionRunId = "reference-release-action-run";
            ReferenceAssemblyReleaseInput input = new ReferenceAssemblyReleaseInput(
                    referenceAssembly.referenceAssemblyId(),
                    referenceAssembly.assemblyManifest().orElseThrow().hash(),
                    referenceAssembly.inspectionReport().orElseThrow().hash(),
                    referenceAssembly.releaseDecisionPointId().orElseThrow(),
                    referenceAssembly.releaseSubjectHash().orElseThrow(),
                    referenceAssembly.version());
            ReferenceAssembly actionBound = referenceAssembly.bindReleaseAction(actionRunId, NOW);
            assertTrue(assemblies.compareAndSet(referenceAssembly, actionBound));
            intents.create(ReferenceAssemblyReleaseDispatchIntent.pending(
                    ReferenceAssemblyReleaseDispatchOperationIds.derive(TENANT, input),
                    TENANT,
                    input,
                    actionRunId,
                    ReferenceAssemblyReleaseAttemptTokens.hash("release-attempt-token"),
                    buildSession.deadlineAt(),
                    NOW));
            if (releaseThrowsAfterCommit) {
                throw new IllegalStateException("proposal result lost after commit");
            }
            return ActionExecutionResult.accepted("REFERENCE_RELEASE_WAITING", Map.of());
        }

        private DecisionPoint decisionPoint(
                DecisionPointId pointId, DecisionPointStatus status, long subjectVersion) {
            return new DecisionPoint(
                    pointId,
                    TENANT,
                    SESSION_ID,
                    ReferenceAssemblyReleaseReviewService.DECISION_TYPE,
                    status,
                    ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE,
                    ASSEMBLY_ID.value(),
                    subjectVersion,
                    RELEASE_SUBJECT.hash(),
                    RELEASE_SUBJECT.reference(),
                    ReferenceAssemblyReleaseReviewService.OPTIONS_SCHEMA_ID,
                    Set.of(ReferenceAssemblyReleaseReviewService.REQUIRED_PERMISSION),
                    1,
                    POLICY.reference(),
                    NOW,
                    session().deadlineAt(),
                    Optional.empty(),
                    Optional.empty(),
                    0);
        }
    }

    private static final class MemoryBuildSessions implements BuildSessionRepository {
        private BuildSession current;
        private int casCalls;

        private MemoryBuildSessions(BuildSession current) {
            this.current = current;
        }

        @Override
        public void create(BuildSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BuildSession> find(
                TenantId tenantId, BuildSessionId buildSessionId) {
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
            casCalls++;
            return true;
        }
    }

    private static final class MemoryAssemblies implements ReferenceAssemblyRepository {
        private Optional<ReferenceAssembly> current;
        private int casCalls;

        private MemoryAssemblies(ReferenceAssembly current) {
            this.current = Optional.ofNullable(current);
        }

        @Override
        public void create(ReferenceAssembly assembly) {
            if (current.isPresent()) {
                throw new IllegalStateException("duplicate Reference Assembly");
            }
            current = Optional.of(assembly);
        }

        @Override
        public Optional<ReferenceAssembly> find(
                TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
            return current.filter(value -> value.tenantId().equals(tenantId)
                    && value.referenceAssemblyId().equals(referenceAssemblyId));
        }

        @Override
        public Optional<ReferenceAssembly> findByBuildSession(
                TenantId tenantId, BuildSessionId buildSessionId) {
            return current.filter(value -> value.tenantId().equals(tenantId)
                    && value.buildSessionId().equals(buildSessionId));
        }

        @Override
        public List<ReferenceAssembly> findReleasedByComponentCertification(
                TenantId tenantId, CertificationId componentCertificationId) {
            return List.of();
        }

        @Override
        public boolean compareAndSet(ReferenceAssembly expected, ReferenceAssembly next) {
            if (current.filter(expected::equals).isEmpty()) {
                return false;
            }
            current = Optional.of(next);
            casCalls++;
            return true;
        }
    }

    private static final class MemoryDecisionPoints implements DecisionPointRepository {
        private Optional<DecisionPoint> current = Optional.empty();

        @Override
        public void create(DecisionPoint decisionPoint) {
            current = Optional.of(decisionPoint);
        }

        @Override
        public Optional<DecisionPoint> find(
                TenantId tenantId, DecisionPointId decisionPointId) {
            return current.filter(value -> value.tenantId().equals(tenantId)
                    && value.decisionPointId().equals(decisionPointId));
        }

        @Override
        public boolean compareAndSet(DecisionPoint expected, DecisionPoint next) {
            if (current.filter(expected::equals).isEmpty()) {
                return false;
            }
            current = Optional.of(next);
            return true;
        }
    }

    private static final class MemoryReleaseIntents
            implements ReferenceAssemblyReleaseDispatchIntentRepository {
        private Optional<ReferenceAssemblyReleaseDispatchIntent> current = Optional.empty();

        @Override
        public void create(ReferenceAssemblyReleaseDispatchIntent intent) {
            if (current.isPresent() && !current.filter(intent::equals).isPresent()) {
                throw new IllegalStateException("conflicting release intent");
            }
            current = Optional.of(intent);
        }

        @Override
        public Optional<ReferenceAssemblyReleaseDispatchIntent> find(String operationId) {
            return current.filter(value -> value.operationId().equals(operationId));
        }

        @Override
        public Optional<ReferenceAssemblyReleaseDispatchIntent> findLatest(
                TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
            return current.filter(value -> value.tenantId().equals(tenantId)
                    && value.referenceAssemblyId().equals(referenceAssemblyId));
        }

        @Override
        public Optional<ReferenceAssemblyReleaseDispatchIntent> claimNext(
                Instant now, Duration lease, String claimToken) {
            return Optional.empty();
        }

        @Override
        public Optional<ReferenceAssemblyReleaseDispatchIntent> claimExpiredRunning(
                Instant now, Duration lease, String claimToken) {
            return Optional.empty();
        }

        @Override
        public boolean compareAndSet(
                ReferenceAssemblyReleaseDispatchIntent expected,
                ReferenceAssemblyReleaseDispatchIntent next) {
            if (current.filter(expected::equals).isEmpty()) {
                return false;
            }
            current = Optional.of(next);
            return true;
        }
    }

    private static BuildSession session(
            BuildSessionStatus status, BuildSessionPhase phase) {
        boolean terminal = status.isTerminal();
        return new BuildSession(
                SESSION_ID,
                TENANT,
                PROJECT_ID,
                ProductLineId.REFERENCE_ASSEMBLY,
                "request-reference-flow",
                "reference-requester",
                status,
                phase,
                REQUIREMENT.reference(),
                REQUIREMENT.hash(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                0,
                NOW.minusSeconds(60),
                NOW.plusSeconds(600),
                Optional.empty(),
                terminal ? Optional.of("TERMINAL") : Optional.empty(),
                terminal ? Optional.of("terminal") : Optional.empty(),
                0,
                NOW.minusSeconds(60),
                NOW.minusSeconds(1));
    }

    private static ReferenceAssembly requestedAssembly() {
        return requestedAssembly(NOW);
    }

    private static ReferenceAssembly requestedAssembly(Instant requestedAt) {
        return ReferenceAssembly.requested(
                ASSEMBLY_ID,
                TENANT,
                SESSION_ID,
                REQUIREMENT,
                CONSUMER_CONTRACT,
                HOST_FIXTURE,
                POLICY,
                new CertificationId("component-certification-flow"),
                new ContentHash("3".repeat(64)),
                COMPONENT_MANIFEST,
                requestedAt);
    }

    private static ReleaseLedger releaseLedger() {
        ReferenceAssembly inspected = requestedAssembly(NOW.minusSeconds(20))
                .resolveComponent(NOW.minusSeconds(19))
                .assemble(ASSEMBLY_MANIFEST, NOW.minusSeconds(18))
                .inspect(INSPECTION_REPORT, NOW.minusSeconds(17));
        DecisionPointId decisionPointId = new DecisionPointId("reference-release-point-terminal");
        ReferenceAssembly reviewBound = inspected.bindReleaseReview(
                decisionPointId, RELEASE_SUBJECT.hash(), NOW.minusSeconds(16));
        String actionRunId = "reference-release-terminal-action";
        ReferenceAssemblyReleaseInput input = new ReferenceAssemblyReleaseInput(
                ASSEMBLY_ID,
                ASSEMBLY_MANIFEST.hash(),
                INSPECTION_REPORT.hash(),
                decisionPointId,
                RELEASE_SUBJECT.hash(),
                reviewBound.version());
        ReferenceAssembly released = reviewBound
                .bindReleaseAction(actionRunId, NOW.minusSeconds(15))
                .release(
                        RELEASE_MANIFEST,
                        decisionPointId,
                        RELEASE_SUBJECT.hash(),
                        actionRunId,
                        NOW.minusSeconds(14));
        ReferenceAssemblyReleaseDispatchIntent pending =
                ReferenceAssemblyReleaseDispatchIntent.pending(
                        ReferenceAssemblyReleaseDispatchOperationIds.derive(TENANT, input),
                        TENANT,
                        input,
                        actionRunId,
                        ReferenceAssemblyReleaseAttemptTokens.hash("release-terminal-attempt"),
                        NOW.plusSeconds(600),
                        NOW.minusSeconds(15));
        return new ReleaseLedger(released, pending);
    }

    private record ReleaseLedger(
            ReferenceAssembly released,
            ReferenceAssemblyReleaseDispatchIntent pendingIntent) {}

    private static StepContext stepContext(String tenantId, String userId) {
        ExecutionContext identity = ExecutionContext.builder()
                .tenantId(tenantId)
                .userId(userId)
                .sessionId(SESSION_ID.value())
                .runId("reference-flow-run")
                .traceId("reference-flow-trace")
                .correlationId(PROJECT_ID.value())
                .build();
        return (StepContext) Proxy.newProxyInstance(
                StepContext.class.getClassLoader(),
                new Class<?>[] {StepContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "executionContext" -> identity;
                    case "flowId" -> FLOW_ID;
                    case "currentStepId" -> ReferenceAssemblyFlowFactory.ACCEPT_REFERENCE_REQUIREMENTS;
                    case "stepNo", "elapsedMillis" -> 0;
                    case "timedOut", "hasSignal" -> false;
                    default -> null;
                });
    }

    private static CertificationArtifactLock lock(String name, char hashDigit) {
        return new CertificationArtifactLock(
                new ArtifactReference("artifact:" + name),
                new ContentHash(String.valueOf(hashDigit).repeat(64)));
    }
}
