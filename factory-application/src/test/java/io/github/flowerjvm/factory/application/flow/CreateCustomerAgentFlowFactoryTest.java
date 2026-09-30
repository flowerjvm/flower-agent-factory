package io.github.flowerjvm.factory.application.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.build.BuildPhase;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import io.github.flowerjvm.flower.core.flow.Flow;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.flow.FlowState;
import io.github.flowerjvm.flower.core.step.RecoveryPolicy;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;
import io.github.flowerjvm.flower.testkit.FlowTestHarness;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CreateCustomerAgentFlowFactoryTest {
    private static final BuildSessionId SESSION_ID = new BuildSessionId("build-session-1");
    private static final FlowId FLOW_ID = FlowId.of(CreateCustomerAgentFlowFactory.FLOW_TYPE, SESSION_ID.value());
    private static final Instant STARTED_AT = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void pinsSixFreshDurableStepsContextAndPhaseMapping() {
        var coordinator = new ScriptedLedgerCoordinator();
        var factory = new CreateCustomerAgentFlowFactory(coordinator);

        Flow first = factory.create(session(), "flow-run-1", "trace-1");
        Flow second = factory.create(session(), "flow-run-2", "trace-2");

        assertEquals(CreateCustomerAgentFlowFactory.DEFINITION_VERSION, first.definitionVersion());
        assertEquals(
                List.of(
                        CreateCustomerAgentFlowFactory.ACCEPT_REQUIREMENTS,
                        CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE,
                        CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE,
                        CreateCustomerAgentFlowFactory.VERIFY_CANDIDATE,
                        CreateCustomerAgentFlowFactory.HUMAN_REVIEW,
                        CreateCustomerAgentFlowFactory.MARK_CANDIDATE_READY),
                first.steps().stream().map(step -> step.stepId()).toList());
        first.steps().forEach(step -> assertEquals(RecoveryPolicy.REENTER_IDEMPOTENT, step.recoveryPolicy()));
        for (int index = 0; index < first.steps().size(); index++) {
            assertNotSame(first.steps().get(index).step(), second.steps().get(index).step());
        }
        assertEquals(fullContext("flow-run-1", "trace-1"), first.executionContext());
        assertEquals(BuildSessionPhase.UNDERSTAND_CUSTOMER,
                CreateCustomerAgentFlowFactory.durablePhase(BuildPhase.ACCEPT_REQUIREMENTS));
        assertEquals(BuildSessionPhase.DESIGN_AGENT,
                CreateCustomerAgentFlowFactory.durablePhase(BuildPhase.DESIGN_CANDIDATE));
        assertEquals(BuildSessionPhase.GENERATE_CANDIDATE,
                CreateCustomerAgentFlowFactory.durablePhase(BuildPhase.GENERATE_CANDIDATE));
        assertEquals(BuildSessionPhase.TEST,
                CreateCustomerAgentFlowFactory.durablePhase(BuildPhase.VERIFY_CANDIDATE));
        assertEquals(BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                CreateCustomerAgentFlowFactory.durablePhase(BuildPhase.HUMAN_REVIEW));
        assertEquals(BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                CreateCustomerAgentFlowFactory.durablePhase(BuildPhase.MARK_CANDIDATE_READY));
    }

    @Test
    void happyPathFinishesAtCandidateReadyFlowBoundary() {
        var coordinator = new ScriptedLedgerCoordinator();
        var factory = new CreateCustomerAgentFlowFactory(coordinator);

        try (var harness = FlowTestHarness.create()) {
            harness.submit(factory.create(session(), "flow-run-1", "trace-1"));
            tickUntilTerminal(harness);

            assertEquals(FlowState.FINISHED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
            for (BuildPhase phase : BuildPhase.values()) {
                assertEquals(1, coordinator.visits(phase));
            }
        }
    }

    @Test
    void correctableVerificationReturnsToGenerateAndChangeRequestReturnsToDesign() {
        var coordinator = new ScriptedLedgerCoordinator();
        coordinator.verificationResults.add(StepResult.goTo(CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE));
        coordinator.reviewResults.add(StepResult.goTo(CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE));
        var factory = new CreateCustomerAgentFlowFactory(coordinator);

        try (var harness = FlowTestHarness.create()) {
            harness.submit(factory.create(session(), "flow-run-1", "trace-1"));
            tickUntilTerminal(harness);

            assertEquals(FlowState.FINISHED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
            assertEquals(2, coordinator.visits(BuildPhase.DESIGN_CANDIDATE));
            assertEquals(3, coordinator.visits(BuildPhase.GENERATE_CANDIDATE));
            assertEquals(3, coordinator.visits(BuildPhase.VERIFY_CANDIDATE));
            assertEquals(2, coordinator.visits(BuildPhase.HUMAN_REVIEW));
            assertEquals(1, coordinator.visits(BuildPhase.MARK_CANDIDATE_READY));
        }
    }

    @Test
    void waitingWorkerRecoveryKeepsFullContextAndDispatchesExistingLogicalAttemptOnce() {
        AtomicInteger dispatches = new AtomicInteger();
        ActionRuntime actionRuntime = (proposal, context) -> {
            assertEquals(WorkerDispatchAction.ACTION_ID, proposal.actionId());
            assertEquals("tenant-a", context.tenantId());
            dispatches.incrementAndGet();
            return ActionExecutionResult.accepted("WAITING_EXTERNAL", Map.of());
        };
        var coordinator = new ActionBackedWorkerLedgerCoordinator(actionRuntime);
        var factory = new CreateCustomerAgentFlowFactory(coordinator);
        FlowTestHarness first = FlowTestHarness.create();
        first.submit(factory.create(session(), "flow-run-1", "trace-1"));
        first.tick(); // accept requirements
        first.tick(); // dispatch and wait in design

        assertEquals(1, dispatches.get());
        assertEquals(CreateCustomerAgentFlowFactory.DESIGN_CANDIDATE,
                first.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());
        assertEquals(fullContext("flow-run-1", "trace-1"),
                first.checkpointStore().get(FLOW_ID).executionContext());

        FlowTestHarness restarted = first.restart();
        assertEquals(1, restarted.recoverActiveCount(factory.registry()));
        restarted.tick();

        assertEquals(1, dispatches.get());
        assertEquals(fullContext("flow-run-1", "trace-1"),
                restarted.activeSnapshot(FLOW_ID).orElseThrow().executionContext());
        coordinator.workerTerminal = true;
        restarted.publish(new CreateCustomerAgentFlowWakeup(SESSION_ID));
        restarted.tick();
        assertEquals(CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE,
                restarted.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());
        restarted.close();
    }

    @Test
    void everyStepRecoversWithAllSixExecutionIdentityFields() {
        for (BuildPhase heldPhase : BuildPhase.values()) {
            var coordinator = new HoldingLedgerCoordinator(heldPhase);
            var factory = new CreateCustomerAgentFlowFactory(coordinator);
            FlowTestHarness first = FlowTestHarness.create();
            first.submit(factory.create(session(), "flow-run-1", "trace-1"));
            tickUntilStep(first, heldPhase.name());

            FlowTestHarness restarted = first.restart();
            assertEquals(1, restarted.recoverActiveCount(factory.registry()));
            restarted.tick();

            assertEquals(heldPhase.name(), restarted.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());
            assertEquals(fullContext("flow-run-1", "trace-1"),
                    restarted.activeSnapshot(FLOW_ID).orElseThrow().executionContext());
            coordinator.released = true;
            tickUntilTerminal(restarted);
            assertEquals(fullContext("flow-run-1", "trace-1"),
                    restarted.latestSnapshot(FLOW_ID).orElseThrow().executionContext());
            restarted.close();
        }
    }

    @Test
    void duplicateWakeupsCannotRepeatTerminalLedgerEffect() {
        var coordinator = new ActionBackedWorkerLedgerCoordinator((proposal, context) ->
                ActionExecutionResult.accepted("WAITING_EXTERNAL", Map.of()));
        var factory = new CreateCustomerAgentFlowFactory(coordinator);

        try (var harness = FlowTestHarness.create()) {
            harness.submit(factory.create(session(), "flow-run-1", "trace-1"));
            harness.tick();
            harness.tick();
            coordinator.workerTerminal = true;
            coordinator.completeCallback();
            coordinator.completeCallback();
            harness.publish(new CreateCustomerAgentFlowWakeup(SESSION_ID));
            harness.publish(new CreateCustomerAgentFlowWakeup(SESSION_ID));
            harness.tick();

            assertEquals(1, coordinator.callbackEffects.get());
            assertEquals(1, coordinator.terminalEffects.get());
            assertEquals(CreateCustomerAgentFlowFactory.GENERATE_CANDIDATE,
                    harness.activeSnapshot(FLOW_ID).orElseThrow().currentStepId());
        }
    }

    @Test
    void externalCancelAndPersistedDeadlineProduceTerminalFlowerStates() {
        var holding = new HoldingLedgerCoordinator(BuildPhase.DESIGN_CANDIDATE);
        var factory = new CreateCustomerAgentFlowFactory(holding);
        try (var harness = FlowTestHarness.create()) {
            harness.submit(factory.create(session(), "flow-run-1", "trace-1"));
            harness.tick();
            harness.tick();
            assertTrue(harness.worker().cancel(FLOW_ID));
            harness.tick();
            assertEquals(FlowState.CANCELLED, harness.latestSnapshot(FLOW_ID).orElseThrow().state());
        }

        var deadline = new HoldingLedgerCoordinator(BuildPhase.DESIGN_CANDIDATE);
        deadline.deadlineExceeded = true;
        var deadlineFactory = new CreateCustomerAgentFlowFactory(deadline);
        try (var harness = FlowTestHarness.create()) {
            harness.submit(deadlineFactory.create(session(), "flow-run-2", "trace-2"));
            harness.tick();
            harness.tick();
            var snapshot = harness.latestSnapshot(FLOW_ID).orElseThrow();
            assertEquals(FlowState.FAILED, snapshot.state());
            assertTrue(snapshot.failureCause().getMessage().contains("persisted deadline"));
        }
    }

    @Test
    void rejectsWrongRecoveryTypeAndTerminalPrecondition() {
        var factory = new CreateCustomerAgentFlowFactory(new ScriptedLedgerCoordinator());
        assertThrows(IllegalArgumentException.class, () -> factory.create(FlowId.of("other", "1")));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(session(BuildSessionStatus.CANCELLED), "run", "trace"));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(session(BuildSessionStatus.DRAFT), "run", "trace"));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(session(BuildSessionStatus.CANCELLING), "run", "trace"));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        session(BuildSessionStatus.RUNNING, new ProductLineId("other-line")), "run", "trace"));
    }

    private static void tickUntilTerminal(FlowTestHarness harness) {
        for (int tick = 0; tick < 30; tick++) {
            harness.tick();
            if (harness.latestSnapshot(FLOW_ID).orElseThrow().state().isTerminal()) {
                return;
            }
        }
        throw new AssertionError("Flow did not terminate within bounded manual ticks");
    }

    private static void tickUntilStep(FlowTestHarness harness, String stepId) {
        for (int tick = 0; tick < 20; tick++) {
            harness.tick();
            if (harness.activeSnapshot(FLOW_ID)
                    .map(snapshot -> stepId.equals(snapshot.currentStepId()))
                    .orElse(false)) {
                return;
            }
        }
        throw new AssertionError("Flow did not reach " + stepId);
    }

    private static ExecutionContext fullContext(String runId, String traceId) {
        return ExecutionContext.builder()
                .tenantId("tenant-a")
                .userId("principal-a")
                .sessionId(SESSION_ID.value())
                .runId(runId)
                .traceId(traceId)
                .correlationId("project-a")
                .build();
    }

    private static BuildSession session() {
        return session(BuildSessionStatus.RUNNING);
    }

    private static BuildSession session(BuildSessionStatus status) {
        return session(status, ProductLineId.AGENT_PACK);
    }

    private static BuildSession session(BuildSessionStatus status, ProductLineId productLineId) {
        Optional<String> terminalCode = status.isTerminal() ? Optional.of("CANCELLED") : Optional.empty();
        return new BuildSession(
                SESSION_ID,
                new TenantId("tenant-a"),
                new ProjectId("project-a"),
                productLineId,
                "request-1",
                "principal-a",
                status,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                new ArtifactReference("artifact:requirements"),
                new ContentHash("0".repeat(64)),
                Optional.empty(),
                Optional.of("fake-coding-worker"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                3,
                STARTED_AT,
                STARTED_AT.plusSeconds(3600),
                status == BuildSessionStatus.CANCELLED ? Optional.of(STARTED_AT.plusSeconds(1)) : Optional.empty(),
                terminalCode,
                Optional.empty(),
                0,
                STARTED_AT,
                STARTED_AT);
    }

    private static class ScriptedLedgerCoordinator implements CreateCustomerAgentFlowCoordinator {
        private final Map<BuildPhase, AtomicInteger> visits = new EnumMap<>(BuildPhase.class);
        private final Queue<StepResult> verificationResults = new ArrayDeque<>();
        private final Queue<StepResult> reviewResults = new ArrayDeque<>();

        private ScriptedLedgerCoordinator() {
            for (BuildPhase phase : BuildPhase.values()) {
                visits.put(phase, new AtomicInteger());
            }
        }

        @Override
        public StepResult advance(BuildPhase phase, StepContext context) {
            visits.get(phase).incrementAndGet();
            if (phase == BuildPhase.VERIFY_CANDIDATE && !verificationResults.isEmpty()) {
                return verificationResults.remove();
            }
            if (phase == BuildPhase.HUMAN_REVIEW && !reviewResults.isEmpty()) {
                return reviewResults.remove();
            }
            return StepResult.done();
        }

        int visits(BuildPhase phase) {
            return visits.get(phase).get();
        }
    }

    private static final class HoldingLedgerCoordinator extends ScriptedLedgerCoordinator {
        private final BuildPhase heldPhase;
        private boolean deadlineExceeded;
        private boolean released;

        private HoldingLedgerCoordinator(BuildPhase heldPhase) {
            this.heldPhase = heldPhase;
        }

        @Override
        public StepResult advance(BuildPhase phase, StepContext context) {
            if (phase == heldPhase && !released) {
                return deadlineExceeded
                        ? StepResult.fail(new IllegalStateException("persisted deadline exceeded"))
                        : StepResult.stay();
            }
            return super.advance(phase, context);
        }
    }

    private static final class ActionBackedWorkerLedgerCoordinator
            implements CreateCustomerAgentFlowCoordinator {
        private final ActionRuntime actionRuntime;
        private final AtomicInteger callbackEffects = new AtomicInteger();
        private final AtomicInteger terminalEffects = new AtomicInteger();
        private boolean dispatchRecorded;
        private boolean workerTerminal;
        private boolean terminalObserved;

        private ActionBackedWorkerLedgerCoordinator(ActionRuntime actionRuntime) {
            this.actionRuntime = actionRuntime;
        }

        @Override
        public StepResult advance(BuildPhase phase, StepContext context) {
            if (phase != BuildPhase.DESIGN_CANDIDATE) {
                return StepResult.done();
            }
            if (!dispatchRecorded) {
                ActionProposal proposal = ActionProposal.builder(WorkerDispatchAction.ACTION_ID)
                        .proposalId("proposal:" + SESSION_ID.value() + ":design:1")
                        .requestChannel(ActionRequestChannel.INTERNAL)
                        .proposerType(ActionProposerType.SERVICE)
                        .requesterId("factory-builder")
                        .input(Map.of(
                                WorkerDispatchAction.WORK_ORDER_ID, "work-order:design:1",
                                WorkerDispatchAction.WORKER_RUN_ID, "worker-run:design:1",
                                WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, 0L))
                        .idempotencyKey("worker-dispatch:" + SESSION_ID.value() + ":design:1")
                        .build();
                var actionContext = new io.github.flowerjvm.flower.action.runtime.ExecutionContext(
                        context.executionContext().tenantIdOrNull(),
                        context.executionContext().userIdOrNull(),
                        context.executionContext().runIdOrNull(),
                        context.executionContext().traceIdOrNull(),
                        Map.of(
                                "actor.permissions", java.util.Set.of(WorkerDispatchAction.PERMISSION),
                                "resource.type", WorkerDispatchAction.RESOURCE_TYPE,
                                "resource.id", "work-order:design:1"));
                actionRuntime.handle(proposal, actionContext);
                dispatchRecorded = true;
            }
            if (!workerTerminal) {
                return StepResult.stay();
            }
            if (!terminalObserved) {
                terminalEffects.incrementAndGet();
                terminalObserved = true;
            }
            return StepResult.done();
        }

        private void completeCallback() {
            if (!workerTerminal) {
                workerTerminal = true;
            }
            if (callbackEffects.compareAndSet(0, 1)) {
                return;
            }
        }
    }
}
