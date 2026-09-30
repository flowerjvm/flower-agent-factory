package io.github.flowerjvm.factory.application.referenceassembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseAction;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionExecutor;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.flow.FactoryCancellationRequestDisposition;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReferenceAssemblyCancellationRequesterTest {
    private static final Instant NOW = Instant.parse("2026-09-02T08:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant-reference-cancel");
    private static final BuildSessionId SESSION =
            new BuildSessionId("build-reference-cancel");
    private static final ReferenceAssemblyId ASSEMBLY =
            new ReferenceAssemblyId("reference-cancel");
    private static final DecisionPointId DECISION =
            new DecisionPointId("reference-cancel-decision");
    private static final String ACTION_RUN = "reference-cancel-action";
    private static final String ATTEMPT_TOKEN = "reference-cancel-attempt";

    @Test
    void releasedAssemblyFailsReadOnlyCancellationPreflight() {
        Fixture fixture = new Fixture(ATTEMPT_TOKEN);
        fixture.assemblies.set(fixture.released());

        assertFalse(fixture.requester().canRequest(runningSession()));
        assertEquals(0, fixture.runtime.cancelCalls.get());
    }

    @Test
    void missingV14IntentFailsPreflightBeforeTheSessionCancellationCas() {
        Fixture fixture = new Fixture(ATTEMPT_TOKEN);
        fixture.intents.clear();

        assertFalse(fixture.requester().canRequest(runningSession()));
        assertEquals(
                FactoryCancellationRequestDisposition.CONFLICT,
                fixture.requester().request(cancellingSession()));
        assertEquals(0, fixture.runtime.cancelCalls.get());
    }

    @Test
    void missingActionRunFailsPreflightBeforeTheSessionCancellationCas() {
        Fixture fixture = new Fixture(
                ATTEMPT_TOKEN, ActionRunStatus.WAITING_EXTERNAL, false);

        assertFalse(fixture.requester().canRequest(runningSession()));
        assertEquals(0, fixture.runtime.cancelCalls.get());
    }

    @Test
    void waitingReleaseActionIsCancelledButSessionEffectRemainsPending() {
        Fixture fixture = new Fixture(ATTEMPT_TOKEN);

        FactoryCancellationRequestDisposition disposition =
                fixture.requester().request(cancellingSession());

        assertEquals(
                FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING,
                disposition);
        assertEquals(ActionRunStatus.CANCELLED, fixture.actions.find(ACTION_RUN)
                .orElseThrow()
                .status());
        assertEquals(1, fixture.runtime.cancelCalls.get());
        assertEquals(
                ReferenceAssemblyReleaseDispatchIntentStatus.PENDING,
                fixture.intents.current().status());
    }

    @Test
    void fractionalIntentDeadlineRecognizesTheExactParkedAndCancelledTransportOwner() {
        Instant deadline = NOW.plusSeconds(600).plusNanos(123_456_000);
        Fixture fixture = new Fixture(ATTEMPT_TOKEN, ActionRunStatus.WAITING_EXTERNAL, true, deadline);
        ActionRun waiting = fixture.actions.find(ACTION_RUN).orElseThrow();
        assertEquals(deadline, fixture.intents.current().deadlineAt());
        assertEquals(NOW.plusSeconds(600).plusMillis(123), waiting.dueAt());
        assertTrue(ReferenceAssemblyReleaseDispatchRunner.hasWaitingOwnerBinding(fixture.intents.current(), waiting));
        assertFalse(ReferenceAssemblyReleaseDispatchRunner.hasWaitingOwnerBinding(
                fixture.intents.current(), waiting.toBuilder().dueAt(deadline).build()));
        assertFalse(ReferenceAssemblyReleaseDispatchRunner.hasWaitingOwnerBinding(
                fixture.intents.current(), waiting.toBuilder().dueAt(waiting.dueAt().plusNanos(1_000)).build()));
        assertTrue(fixture.requester().canRequest(runningSession()));
        assertEquals(FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING,
                fixture.requester().request(cancellingSession()));
        assertEquals(1, fixture.runtime.cancelCalls.get());
        assertEquals(FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING,
                fixture.requester().request(cancellingSession()));
        assertEquals(1, fixture.runtime.cancelCalls.get(), "canonical cancelled owner must not redispatch cancellation");
    }

    @Test
    void runningDispatchCancellationDenialRemainsPendingForRecovery() {
        Fixture fixture = new Fixture(ATTEMPT_TOKEN, ActionRunStatus.RUNNING);

        FactoryCancellationRequestDisposition disposition =
                fixture.requester().request(cancellingSession());

        assertEquals(
                FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING,
                disposition);
        assertEquals(ActionRunStatus.RUNNING, fixture.actions.find(ACTION_RUN)
                .orElseThrow()
                .status());
        assertEquals(1, fixture.runtime.cancelCalls.get());
    }

    @Test
    void runningCancellationCanBeRetriedAfterTheActionParks() {
        Fixture fixture = new Fixture(ATTEMPT_TOKEN, ActionRunStatus.RUNNING);

        assertEquals(
                FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING,
                fixture.requester().request(cancellingSession()));
        assertEquals(ActionRunStatus.RUNNING, fixture.actions.find(ACTION_RUN)
                .orElseThrow()
                .status());

        fixture.parkAction();

        assertEquals(
                FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING,
                fixture.requester().request(cancellingSession()));
        assertEquals(ActionRunStatus.CANCELLED, fixture.actions.find(ACTION_RUN)
                .orElseThrow()
                .status());
        assertEquals(2, fixture.runtime.cancelCalls.get());
    }

    @Test
    void cancelledActionAndOrphanedIntentProveNoActiveReleaseEffect() {
        Fixture fixture = new Fixture(ATTEMPT_TOKEN);
        fixture.orphanIntentAndCancelAction();

        FactoryCancellationRequestDisposition disposition =
                fixture.requester().request(cancellingSession());

        assertEquals(FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT, disposition);
        assertEquals(0, fixture.runtime.cancelCalls.get());
    }

    @Test
    void deadlineTerminalFailureRacingCancellationProvesNoActiveShipmentEffect() {
        Fixture fixture = new Fixture(ATTEMPT_TOKEN);
        assertTrue(fixture.requester().canRequest(runningSession()));
        fixture.runtime.beforeCancel(fixture::terminalizeDeadlineFailure);

        FactoryCancellationRequestDisposition disposition =
                fixture.requester().request(cancellingSession());

        assertEquals(FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT, disposition);
        assertEquals(1, fixture.runtime.cancelCalls.get());
        assertEquals(ActionRunStatus.FAILED, fixture.actions.find(ACTION_RUN)
                .orElseThrow()
                .status());
        assertEquals(
                ReferenceAssemblyReleaseDispatchIntentStatus.COMPLETED,
                fixture.intents.current().status());
        assertEquals(ReferenceAssemblyStatus.INSPECTED, fixture.assemblies.current().status());
    }

    @Test
    void preParkOrphanWithRunningActionProvesNoActiveShipmentEffect() {
        Fixture fixture = new Fixture(ATTEMPT_TOKEN, ActionRunStatus.RUNNING);
        fixture.orphanBeforeWaiting();

        assertTrue(fixture.requester().canRequest(runningSession()));
        assertEquals(
                FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT,
                fixture.requester().request(cancellingSession()));
        assertEquals(ActionRunStatus.RUNNING, fixture.actions.find(ACTION_RUN)
                .orElseThrow()
                .status());
        assertEquals(0, fixture.runtime.cancelCalls.get());
    }

    @Test
    void successfulReleaseCanNeverBeClassifiedAsNoActiveEffect() {
        Fixture fixture = new Fixture(ATTEMPT_TOKEN);
        fixture.completeSuccessfulRelease();

        assertFalse(fixture.requester().canRequest(runningSession()));
        assertEquals(
                FactoryCancellationRequestDisposition.CONFLICT,
                fixture.requester().request(cancellingSession()));
        assertEquals(ActionRunStatus.SUCCEEDED, fixture.actions.find(ACTION_RUN)
                .orElseThrow()
                .status());
        assertEquals(0, fixture.runtime.cancelCalls.get());
    }

    @Test
    void mismatchedActionOwnerFailsClosedBeforeRuntimeCancellation() {
        Fixture fixture = new Fixture("forged-reference-cancel-attempt");

        assertFalse(fixture.requester().canRequest(runningSession()));

        FactoryCancellationRequestDisposition disposition =
                fixture.requester().request(cancellingSession());

        assertEquals(FactoryCancellationRequestDisposition.CONFLICT, disposition);
        assertEquals(0, fixture.runtime.cancelCalls.get());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, fixture.actions.find(ACTION_RUN)
                .orElseThrow()
                .status());
    }

    @Test
    void mismatchedWaitingExternalOperationFailsClosedBeforeRuntimeCancellation() {
        Fixture fixture = new Fixture(ATTEMPT_TOKEN);
        fixture.corruptWaitingOperation();

        FactoryCancellationRequestDisposition disposition =
                fixture.requester().request(cancellingSession());

        assertEquals(FactoryCancellationRequestDisposition.CONFLICT, disposition);
        assertEquals(0, fixture.runtime.cancelCalls.get());
    }

    private static final class Fixture {
        private final CertificationArtifactLock requirement = lock("requirement", 'a');
        private final CertificationArtifactLock consumer = lock("consumer", 'b');
        private final CertificationArtifactLock host = lock("host", 'c');
        private final CertificationArtifactLock policy = lock("policy", 'd');
        private final CertificationArtifactLock component = lock("component", 'e');
        private final CertificationArtifactLock assemblyManifest = lock("assembly", 'f');
        private final CertificationArtifactLock inspection = lock("inspection", '1');
        private final ContentHash releaseSubjectHash = hash('2');
        private final CertificationArtifactLock releaseManifest = lock("release", '3');
        private final ReferenceAssembly reviewed;
        private final ReferenceAssembly bound;
        private final ReferenceAssemblyReleaseInput input;
        private final MemoryAssemblies assemblies;
        private final MemoryIntents intents;
        private final InMemoryRunStore actions = new InMemoryRunStore();
        private final RecordingRuntime runtime;

        private Fixture(String actionAttemptToken) {
            this(actionAttemptToken, ActionRunStatus.WAITING_EXTERNAL);
        }

        private Fixture(String actionAttemptToken, ActionRunStatus actionStatus) {
            this(actionAttemptToken, actionStatus, true);
        }

        private Fixture(
                String actionAttemptToken,
                ActionRunStatus actionStatus,
                boolean persistAction) {
            this(actionAttemptToken, actionStatus, persistAction, NOW.plusSeconds(600));
        }

        private Fixture(
                String actionAttemptToken, ActionRunStatus actionStatus,
                boolean persistAction, Instant businessDeadline) {
            ReferenceAssembly inspected = ReferenceAssembly.requested(
                            ASSEMBLY,
                            TENANT,
                            SESSION,
                            requirement,
                            consumer,
                            host,
                            policy,
                            new CertificationId("component-reference-cancel"),
                            hash('4'),
                            component,
                            NOW.minusSeconds(20))
                    .resolveComponent(NOW.minusSeconds(19))
                    .assemble(assemblyManifest, NOW.minusSeconds(18))
                    .inspect(inspection, NOW.minusSeconds(17));
            reviewed = inspected.bindReleaseReview(
                    DECISION, releaseSubjectHash, NOW.minusSeconds(16));
            input = new ReferenceAssemblyReleaseInput(
                    ASSEMBLY,
                    assemblyManifest.hash(),
                    inspection.hash(),
                    DECISION,
                    releaseSubjectHash,
                    reviewed.version());
            bound = reviewed.bindReleaseAction(ACTION_RUN, NOW.minusSeconds(15));
            assemblies = new MemoryAssemblies(bound);
            ReferenceAssemblyReleaseDispatchIntent pending =
                    ReferenceAssemblyReleaseDispatchIntent.pending(
                            ReferenceAssemblyReleaseDispatchOperationIds.derive(TENANT, input),
                            TENANT,
                            input,
                            ACTION_RUN,
                            ReferenceAssemblyReleaseAttemptTokens.hash(ATTEMPT_TOKEN),
                            businessDeadline,
                            NOW.minusSeconds(14));
            intents = new MemoryIntents(pending);
            if (persistAction) {
                actions.create(action(pending, actionAttemptToken, actionStatus));
            }
            runtime = new RecordingRuntime(actions);
        }

        private ReferenceAssemblyCancellationRequester requester() {
            return new ReferenceAssemblyCancellationRequester(
                    assemblies, intents, actions, runtime);
        }

        private ReferenceAssembly released() {
            return bound.release(
                    releaseManifest,
                    DECISION,
                    releaseSubjectHash,
                    ACTION_RUN,
                    NOW.minusSeconds(10));
        }

        private void orphanIntentAndCancelAction() {
            ReferenceAssemblyReleaseDispatchIntent orphaned = intents.current()
                    .claim(
                            "reference-cancel-claim",
                            NOW.minusSeconds(13),
                            Duration.ofMinutes(1))
                    .orphan(
                            "reference-cancel-claim",
                            ReferenceAssemblyReleaseDispatchRunner.ACTION_TERMINAL_CONFLICT,
                            NOW.minusSeconds(12));
            intents.set(orphaned);
            ActionRun current = actions.find(ACTION_RUN).orElseThrow();
            actions.compareAndSet(current, cancelled(current));
        }

        private void corruptWaitingOperation() {
            ActionRun current = actions.find(ACTION_RUN).orElseThrow();
            ActionRun corrupted = current.toBuilder()
                    .version(current.version() + 1)
                    .externalOperationId("reference-assembly-release:" + "f".repeat(64))
                    .updatedAt(NOW.minusSeconds(13))
                    .build();
            actions.compareAndSet(current, corrupted);
        }

        private void parkAction() {
            ActionRun current = actions.find(ACTION_RUN).orElseThrow();
            ActionRun parked = current.toBuilder()
                    .version(current.version() + 1)
                    .status(ActionRunStatus.WAITING_EXTERNAL)
                    .currentStage("execute-action")
                    .externalOperationId(intents.current().operationId())
                    .externalOperationMetadata(Map.of(
                            "dispatchMode",
                            ReferenceAssemblyReleaseActionExecutor.DISPATCH_MODE,
                            ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                            ASSEMBLY.value()))
                    .dueAt(ReferenceAssemblyReleaseDeadlines.actionDueAt(intents.current().deadlineAt()))
                    .updatedAt(NOW.minusSeconds(13))
                    .build();
            if (!actions.compareAndSet(current, parked)) {
                throw new IllegalStateException("simulated Action park CAS lost");
            }
        }

        private void terminalizeDeadlineFailure() {
            ReferenceAssemblyReleaseDispatchIntent completed = intents.current()
                    .claim(
                            "reference-deadline-claim",
                            NOW.minusSeconds(13),
                            Duration.ofMinutes(1))
                    .complete(
                            "reference-deadline-claim",
                            ReferenceAssemblyReleaseDispatchRunner.DEADLINE_EXCEEDED,
                            NOW.minusSeconds(12));
            intents.set(completed);
            ActionRun current = actions.find(ACTION_RUN).orElseThrow();
            ActionRun failed = current.toBuilder()
                    .version(current.version() + 1)
                    .status(ActionRunStatus.FAILED)
                    .currentStage("TERMINAL")
                    .result(ActionExecutionResult.manualReviewFailure(
                            ReferenceAssemblyReleaseDispatchRunner.DEADLINE_EXCEEDED,
                            "release deadline elapsed before a shipment was committed"))
                    .updatedAt(NOW.minusSeconds(11))
                    .build();
            if (!actions.compareAndSet(current, failed)) {
                throw new IllegalStateException("deadline terminalization CAS lost");
            }
        }

        private void orphanBeforeWaiting() {
            intents.set(intents.current()
                    .claim(
                            "reference-pre-park-claim",
                            NOW.minusSeconds(13),
                            Duration.ofMinutes(1))
                    .orphanBeforeWaiting(
                            "reference-pre-park-claim",
                            ReferenceAssemblyReleaseDispatchRunner.ORPHANED_BEFORE_WAITING,
                            NOW.minusSeconds(12)));
        }

        private void completeSuccessfulRelease() {
            ReferenceAssemblyReleaseDispatchIntent completed = intents.current()
                    .claim(
                            "reference-success-claim",
                            NOW.minusSeconds(13),
                            Duration.ofMinutes(1))
                    .complete(
                            "reference-success-claim",
                            ReferenceAssemblyReleaseDispatchRunner.DISPATCH_COMPLETED,
                            NOW.minusSeconds(12));
            intents.set(completed);
            assemblies.set(released());
            ActionRun current = actions.find(ACTION_RUN).orElseThrow();
            ActionRun succeeded = current.toBuilder()
                    .version(current.version() + 1)
                    .status(ActionRunStatus.SUCCEEDED)
                    .currentStage("TERMINAL")
                    .result(ActionExecutionResult.succeeded(Map.of(
                            ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                            ASSEMBLY.value(),
                            ReferenceAssemblyReleaseDispatchRunner.RELEASE_STATUS,
                            ReferenceAssemblyStatus.RELEASED.name())))
                    .updatedAt(NOW.minusSeconds(9))
                    .build();
            if (!actions.compareAndSet(current, succeeded)) {
                throw new IllegalStateException("successful release Action CAS lost");
            }
        }

        private ActionRun action(
                ReferenceAssemblyReleaseDispatchIntent intent,
                String attemptToken,
                ActionRunStatus status) {
            ActionExecutionResult result = status == ActionRunStatus.CANCELLED
                    ? ActionExecutionResult.cancelled(
                            "ACTION_CANCELLED",
                            ReferenceAssemblyCancellationRequester.REASON_CODE)
                    : null;
            var builder = ActionRun.builder()
                    .runId(ACTION_RUN)
                    .tenantId(TENANT.value())
                    .userId("reference-cancel-principal")
                    .traceId("trace-reference-cancel")
                    .contextMetadata(Map.of(
                            "actor.permissions", Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                            "resource.type", ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                            "resource.id", ASSEMBLY.value()))
                    .actionId(ReferenceAssemblyReleaseAction.ACTION_ID)
                    .proposalId("proposal-reference-cancel")
                    .requesterId("factory-reference-cancel")
                    .requestChannel(ActionRequestChannel.INTERNAL)
                    .proposerType(ActionProposerType.SERVICE)
                    .input(input.toMap())
                    .duplicateKey(ReferenceAssemblyReleaseIdempotencyKeys.derive(
                            bound, input.expectedReferenceAssemblyVersion()))
                    .status(status)
                    .currentStage(status.isTerminal() ? "TERMINAL" : "EXECUTION")
                    .attemptToken(attemptToken)
                    .result(result)
                    .createdAt(NOW.minusSeconds(15))
                    .updatedAt(NOW.minusSeconds(14));
            if (status != ActionRunStatus.RUNNING) {
                builder.externalOperationId(intent.operationId())
                        .externalOperationMetadata(Map.of(
                                "dispatchMode",
                                ReferenceAssemblyReleaseActionExecutor.DISPATCH_MODE,
                                ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                                ASSEMBLY.value()))
                        .dueAt(ReferenceAssemblyReleaseDeadlines.actionDueAt(intent.deadlineAt()));
            }
            return builder.build();
        }
    }

    private static final class RecordingRuntime implements CompletableActionRuntime {
        private final InMemoryRunStore actions;
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicReference<Runnable> beforeCancel = new AtomicReference<>();

        private RecordingRuntime(InMemoryRunStore actions) {
            this.actions = actions;
        }

        private void beforeCancel(Runnable hook) {
            beforeCancel.set(hook);
        }

        @Override
        public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionExecutionResult complete(
                String runId, String attemptToken, ActionExecutionResult result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionExecutionResult cancel(String runId, String reason) {
            cancelCalls.incrementAndGet();
            Runnable hook = beforeCancel.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            ActionRun current = actions.find(runId).orElseThrow();
            if (current.status().isTerminal()) {
                return current.result();
            }
            if (current.status() == ActionRunStatus.RUNNING) {
                return new ActionExecutionResult(
                        ActionExecutionStatus.DENIED,
                        "ACTION_RUN_NOT_CANCELLABLE_WHILE_RUNNING",
                        "An action that is dispatching or already running cannot be safely cancelled: "
                                + runId,
                        Map.of(),
                        RetryDisposition.MANUAL_REVIEW);
            }
            ActionRun cancelled = cancelled(current);
            if (!actions.compareAndSet(current, cancelled)) {
                throw new IllegalStateException("simulated cancellation CAS lost");
            }
            return cancelled.result();
        }
    }

    private static final class MemoryAssemblies implements ReferenceAssemblyRepository {
        private final AtomicReference<ReferenceAssembly> current;

        private MemoryAssemblies(ReferenceAssembly initial) {
            current = new AtomicReference<>(initial);
        }

        private void set(ReferenceAssembly assembly) {
            current.set(assembly);
        }

        private ReferenceAssembly current() {
            return current.get();
        }

        @Override
        public void create(ReferenceAssembly assembly) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ReferenceAssembly> find(
                TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
            return Optional.of(current.get()).filter(assembly ->
                    assembly.tenantId().equals(tenantId)
                            && assembly.referenceAssemblyId().equals(referenceAssemblyId));
        }

        @Override
        public Optional<ReferenceAssembly> findByBuildSession(
                TenantId tenantId, BuildSessionId buildSessionId) {
            return Optional.of(current.get()).filter(assembly ->
                    assembly.tenantId().equals(tenantId)
                            && assembly.buildSessionId().equals(buildSessionId));
        }

        @Override
        public List<ReferenceAssembly> findReleasedByComponentCertification(
                TenantId tenantId, CertificationId componentCertificationId) {
            return List.of();
        }

        @Override
        public boolean compareAndSet(ReferenceAssembly expected, ReferenceAssembly next) {
            return current.compareAndSet(expected, next);
        }
    }

    private static final class MemoryIntents
            implements ReferenceAssemblyReleaseDispatchIntentRepository {
        private final AtomicReference<ReferenceAssemblyReleaseDispatchIntent> current;

        private MemoryIntents(ReferenceAssemblyReleaseDispatchIntent initial) {
            current = new AtomicReference<>(initial);
        }

        private ReferenceAssemblyReleaseDispatchIntent current() {
            return current.get();
        }

        private void set(ReferenceAssemblyReleaseDispatchIntent intent) {
            current.set(intent);
        }

        private void clear() {
            current.set(null);
        }

        @Override
        public void create(ReferenceAssemblyReleaseDispatchIntent intent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ReferenceAssemblyReleaseDispatchIntent> find(String operationId) {
            return Optional.ofNullable(current.get())
                    .filter(intent -> intent.operationId().equals(operationId));
        }

        @Override
        public Optional<ReferenceAssemblyReleaseDispatchIntent> findLatest(
                TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
            return Optional.ofNullable(current.get()).filter(intent ->
                    intent.tenantId().equals(tenantId)
                            && intent.referenceAssemblyId().equals(referenceAssemblyId));
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
            return current.compareAndSet(expected, next);
        }
    }

    private static ActionRun cancelled(ActionRun current) {
        return current.toBuilder()
                .version(current.version() + 1)
                .status(ActionRunStatus.CANCELLED)
                .currentStage("TERMINAL")
                .result(ActionExecutionResult.cancelled(
                        "ACTION_CANCELLED",
                        ReferenceAssemblyCancellationRequester.REASON_CODE))
                .updatedAt(NOW.minusSeconds(5))
                .build();
    }

    private static BuildSession cancellingSession() {
        return runningSession().requestCancellation(NOW.minusSeconds(1));
    }

    private static BuildSession runningSession() {
        return new BuildSession(
                SESSION,
                TENANT,
                new ProjectId("project-reference-cancel"),
                ProductLineId.REFERENCE_ASSEMBLY,
                "request-reference-cancel",
                "reference-cancel-principal",
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.PACKAGE_RELEASE,
                new ArtifactReference("artifact:reference-cancel-requirement"),
                hash('a'),
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
                Optional.empty(),
                Optional.empty(),
                5,
                NOW.minusSeconds(60),
                NOW.minusSeconds(10));
    }

    private static CertificationArtifactLock lock(String name, char hashDigit) {
        return new CertificationArtifactLock(
                new ArtifactReference("artifact:" + name), hash(hashDigit));
    }

    private static ContentHash hash(char digit) {
        return new ContentHash(String.valueOf(digit).repeat(64));
    }
}
