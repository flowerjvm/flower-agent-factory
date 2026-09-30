package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.flow.CreateCustomerAgentFlowFactory;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineRegistry;
import io.github.flowerjvm.factory.application.production.ActionBackedAgentPackProductionIntakeLauncher;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeAction;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeInput;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.persistence.FlowCheckpoint;
import io.github.flowerjvm.flower.core.persistence.FlowCheckpointStore;
import io.github.flowerjvm.flower.core.step.StepResult;
import io.github.flowerjvm.flower.core.time.ManualClock;
import io.github.flowerjvm.flower.core.worker.Worker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/** Host boundary only: a fake governed intake result is not evidence of real product production. */
class FactoryAgentPackLocalOrderRunnerTest {
    static final Instant NOW = Instant.parse("2026-09-06T05:00:00Z");
    static final TenantId TENANT = new TenantId("local-order-tenant");
    static final BuildSessionId SESSION = new BuildSessionId("local-order-session");
    static final ProjectId PROJECT = new ProjectId("local-order-project");
    static final String REQUEST = "local-order-fixed-request";
    static final AgentPackProductionIntakeInput INPUT = new AgentPackProductionIntakeInput(
            SESSION, PROJECT, NOW.plusSeconds(3600), 2);

    @Test
    void successfulRegisteredIntakeAndExactCanonicalLedgerSubmitOneIdentityOnlyFlow() {
        try (Fixture f = new Fixture()) {
            f.behavior = (proposal, context) -> {
                assertTrue(f.worker.snapshot().isEmpty());
                f.sessions.create(pristine());
                // Caller-facing output is not the session/tenant/Flow authority.
                return ActionExecutionResult.succeeded(Map.of("tenantId", "untrusted-output-tenant",
                        "buildSessionId", "untrusted-output-session", "approved", true));
            };
            f.runner().run(new DefaultApplicationArguments());
            assertEquals(1, f.proposals.size());
            assertEquals(0, f.stepVisits.get(), "runner must submit, never manually drive the Worker");
            f.worker.tickOnce();
            assertEquals(1, f.worker.snapshot().size());
            assertEquals(FlowId.of(CreateCustomerAgentFlowFactory.FLOW_TYPE, SESSION.value()),
                    f.worker.snapshot().getFirst().flowId());
            assertEquals(expectedFlowIdentity(), f.worker.snapshot().getFirst().executionContext());
            assertEquals(1, f.sessions.creates.get());
            assertEquals(INPUT.toMap(), f.proposals.getFirst().input());
            assertEquals(REQUEST, f.proposals.getFirst().idempotencyKey());
        }
    }

    @Test
    void nonSuccessfulIntakeNeverReadsTheLedgerOrSubmitsAFlowEvenIfAnOldRowExists() {
        for (ActionExecutionResult result : List.of(ActionExecutionResult.denied("DENIED", "synthetic denied"),
                ActionExecutionResult.denied("DUPLICATE_ACTION", "synthetic in-progress duplicate"),
                ActionExecutionResult.accepted("ACCEPTED", Map.of()),
                ActionExecutionResult.failed("FAILED", "synthetic failed"))) {
            try (Fixture f = new Fixture()) {
                f.sessions.value.set(pristine());
                f.behavior = (proposal, context) -> result;
                assertThrows(IllegalStateException.class, () -> f.runner().run(new DefaultApplicationArguments()));
                assertEquals(0, f.sessions.reads.get());
                f.assertNoFlow();
            }
        }
    }

    @Test
    void thrownOrMissingIntakeResultCannotSubmitAFlow() {
        try (Fixture f = new Fixture()) {
            f.behavior = (proposal, context) -> { throw new IllegalStateException("synthetic runtime error"); };
            assertThrows(IllegalStateException.class, () -> f.runner().run(new DefaultApplicationArguments()));
            assertEquals(0, f.sessions.reads.get());
            f.assertNoFlow();
        }
        try (Fixture f = new Fixture()) {
            f.behavior = (proposal, context) -> null;
            assertThrows(NullPointerException.class, () -> f.runner().run(new DefaultApplicationArguments()));
            assertEquals(0, f.sessions.reads.get());
            f.assertNoFlow();
        }
    }

    @Test
    void succeededWithoutADurableSessionCannotSubmitAFlow() {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.runner().run(new DefaultApplicationArguments()));
            assertEquals(1, f.sessions.reads.get());
            f.assertNoFlow();
        }
    }

    @Test
    void succeededWithAnyMismatchedDurableOwnerCannotSubmitAFlow() {
        for (String changed : List.of("tenant", "session", "project", "productLine", "principal", "request", "deadline", "repair")) {
            try (Fixture f = new Fixture()) {
                f.sessions.value.set(variant(changed, BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER));
                var rejected = assertThrows(IllegalStateException.class, () -> f.runner().run(new DefaultApplicationArguments()));
                assertEquals("intake result does not match the trusted local order", rejected.getMessage(), changed);
                f.assertNoFlow();
            }
        }
    }

    @Test
    void terminalAndCancellationRequestedSessionsNeverSubmitANewFlow() {
        for (BuildSessionStatus status : List.of(BuildSessionStatus.SUCCEEDED, BuildSessionStatus.CANCELLED, BuildSessionStatus.FAILED)) {
            try (Fixture f = new Fixture()) {
                f.sessions.value.set(variant("", status, BuildSessionPhase.UNDERSTAND_CUSTOMER));
                f.runner().run(new DefaultApplicationArguments());
                f.assertNoFlow();
            }
        }
        try (Fixture f = new Fixture()) {
            f.sessions.value.set(pristine().requestCancellation(NOW));
            f.runner().run(new DefaultApplicationArguments());
            f.assertNoFlow();
        }
    }

    @Test
    void progressedPhasesAndAttentionStatesLeaveRecoveryToTheExistingCheckpoint() {
        for (BuildSessionPhase phase : BuildSessionPhase.values()) {
            if (phase == BuildSessionPhase.UNDERSTAND_CUSTOMER) continue;
            try (Fixture f = new Fixture()) {
                f.sessions.value.set(variant("", BuildSessionStatus.RUNNING, phase));
                f.runner().run(new DefaultApplicationArguments());
                f.assertNoFlow();
            }
        }
        for (BuildSessionStatus status : List.of(BuildSessionStatus.MANUAL_REVIEW, BuildSessionStatus.BLOCKED,
                BuildSessionStatus.REPAIRING, BuildSessionStatus.CANCELLING, BuildSessionStatus.DRAFT)) {
            try (Fixture f = new Fixture()) {
                f.sessions.value.set(variant("", status, BuildSessionPhase.UNDERSTAND_CUSTOMER));
                f.runner().run(new DefaultApplicationArguments());
                f.assertNoFlow();
            }
        }
    }

    @Test
    void identicalOrderRetryUsesNewActionLifecyclesButOneStablePrimaryFlowIdentity() {
        try (Fixture f = new Fixture()) {
            f.behavior = (proposal, context) -> {
                if (f.sessions.value.get() == null) f.sessions.create(pristine());
                return ActionExecutionResult.succeeded(Map.of());
            };
            f.runner().run(new DefaultApplicationArguments());
            f.worker.tickOnce();
            assertEquals(expectedFlowIdentity(), f.worker.snapshot().getFirst().executionContext());
            f.runner().run(new DefaultApplicationArguments());
            f.worker.tickOnce();
            assertEquals(2, f.proposals.size());
            assertEquals(f.proposals.getFirst().idempotencyKey(), f.proposals.getLast().idempotencyKey());
            assertNotEquals(f.contexts.getFirst().runId(), f.contexts.getLast().runId());
            assertNotEquals(f.proposals.getFirst().proposalId(), f.proposals.getLast().proposalId());
            assertEquals(1, f.sessions.creates.get());
            assertEquals(1, f.worker.snapshot().size());
            assertEquals(expectedFlowIdentity(), f.worker.snapshot().getFirst().executionContext());
            assertEquals(1, f.checkpoints.values.size());
        }
    }

    @Test
    void expiredIntakeAndUnboundedLocalIdentityFailBeforeTheActionRuntime() {
        try (Fixture f = new Fixture()) {
            var expired = new AgentPackProductionIntakeInput(SESSION, PROJECT, NOW, 2);
            assertThrows(IllegalArgumentException.class, () -> new FactoryAgentPackLocalOrderRunner(
                    f.intake, f.sessions, f.flows, TENANT, REQUEST, expired).run(new DefaultApplicationArguments()));
            for (String request : Arrays.asList(null, "", " leading", "trailing ", "line\nbreak", "x".repeat(256))) {
                assertThrows(IllegalArgumentException.class, () -> new FactoryAgentPackLocalOrderRunner(
                        f.intake, f.sessions, f.flows, TENANT, request, INPUT));
            }
            assertTrue(f.proposals.isEmpty());
            f.assertNoFlow();
        }
    }

    static final class Fixture implements AutoCloseable {
        final MemorySessions sessions = new MemorySessions();
        final MemoryCheckpoints checkpoints = new MemoryCheckpoints();
        final Worker worker = Worker.builder("local-order-test").build();
        final Engine engine;
        final FactoryProductLineFlowLauncher flows;
        final ActionBackedAgentPackProductionIntakeLauncher intake;
        final AtomicInteger stepVisits = new AtomicInteger();
        final List<ActionProposal> proposals = new ArrayList<>();
        final List<ExecutionContext> contexts = new ArrayList<>();
        ActionRuntime behavior = (proposal, context) -> ActionExecutionResult.succeeded(Map.of());

        Fixture() {
            ManualClock clock = new ManualClock(); clock.setTime(NOW.toEpochMilli());
            engine = Engine.builder().clock(clock).worker(worker).checkpointStore(checkpoints).build();
            engine.attach();
            var line = new CreateCustomerAgentFlowFactory((phase, context) -> {
                stepVisits.incrementAndGet(); return StepResult.stay();
            });
            flows = new FactoryProductLineFlowLauncher(sessions, new FactoryProductLineRegistry(List.of(line)), engine, "local-order-test");
            AtomicInteger lifecycle = new AtomicInteger();
            intake = new ActionBackedAgentPackProductionIntakeLauncher((proposal, context) -> {
                assertEquals(AgentPackProductionIntakeAction.ACTION_ID, proposal.actionId());
                assertEquals(ActionRequestChannel.INTERNAL, proposal.requestChannel());
                assertEquals(ActionProposerType.SERVICE, proposal.proposerType());
                assertEquals(AgentPackProductionIntakeAction.REQUESTER_ID, proposal.requesterId());
                assertEquals(TENANT.value(), context.tenantId());
                assertEquals(AgentPackProductionIntakeAction.REQUESTER_ID, context.userId());
                assertEquals(Map.of("actor.permissions", Set.of(AgentPackProductionIntakeAction.PERMISSION),
                        "resource.type", AgentPackProductionIntakeAction.RESOURCE_TYPE,
                        "resource.id", SESSION.value(), "resource.projectId", PROJECT.value()), context.metadata());
                proposals.add(proposal); contexts.add(context);
                return behavior.handle(proposal, context);
            }, Clock.fixed(NOW, ZoneOffset.UTC), () -> "local-lifecycle-" + lifecycle.incrementAndGet());
        }

        FactoryAgentPackLocalOrderRunner runner() {
            return new FactoryAgentPackLocalOrderRunner(intake, sessions, flows, TENANT, REQUEST, INPUT);
        }
        void assertNoFlow() {
            worker.tickOnce();
            assertTrue(worker.snapshot().isEmpty());
            assertEquals(0, stepVisits.get());
            assertTrue(checkpoints.values.isEmpty());
        }
        public void close() { engine.stop(); }
    }

    static final class MemorySessions implements BuildSessionRepository {
        final AtomicReference<BuildSession> value = new AtomicReference<>();
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        public void create(BuildSession session) { assertTrue(value.compareAndSet(null, session)); creates.incrementAndGet(); }
        public Optional<BuildSession> find(TenantId tenant, BuildSessionId id) {
            assertEquals(TENANT, tenant); assertEquals(SESSION, id); reads.incrementAndGet();
            // Deliberately return the fixture row even when corrupt: the runner must check its owner independently.
            return Optional.ofNullable(value.get());
        }
        public boolean compareAndSet(BuildSession expected, BuildSession next) { return value.compareAndSet(expected, next); }
    }

    static final class MemoryCheckpoints implements FlowCheckpointStore {
        final Map<FlowId, FlowCheckpoint> values = new HashMap<>();
        public void save(FlowCheckpoint checkpoint) { values.put(checkpoint.flowId(), checkpoint); }
        public void delete(FlowId id) { values.remove(id); }
        public Optional<FlowCheckpoint> find(FlowId id) { return Optional.ofNullable(values.get(id)); }
    }

    static BuildSession pristine() { return variant("", BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER); }

    private static BuildSession variant(String changed, BuildSessionStatus status, BuildSessionPhase phase) {
        return new BuildSession(changed.equals("session") ? new BuildSessionId("wrong-session") : SESSION,
                changed.equals("tenant") ? new TenantId("wrong-tenant") : TENANT,
                changed.equals("project") ? new ProjectId("wrong-project") : PROJECT,
                changed.equals("productLine") ? ProductLineId.REFERENCE_ASSEMBLY : ProductLineId.AGENT_PACK,
                changed.equals("request") ? "wrong-request" : REQUEST,
                changed.equals("principal") ? "wrong-principal" : AgentPackProductionIntakeAction.REQUESTER_ID,
                status, phase, new ArtifactReference("artifact:synthetic-requirements"), new ContentHash("a".repeat(64)),
                Optional.of("synthetic-manager"), Optional.of("synthetic-coder"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 0, changed.equals("repair") ? 3 : INPUT.maxRepairRounds(), NOW,
                changed.equals("deadline") ? INPUT.deadlineAt().plusSeconds(1) : INPUT.deadlineAt(), Optional.empty(),
                status.isTerminal() ? Optional.of("SYNTHETIC_TERMINAL") : Optional.empty(), Optional.empty(), 0, NOW, NOW);
    }

    private static io.github.flowerjvm.flower.core.context.ExecutionContext expectedFlowIdentity() {
        try {
            String material = TENANT.value().length() + ":" + TENANT.value() + SESSION.value().length() + ":" + SESSION.value();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
            return CreateCustomerAgentFlowFactory.executionContext(SESSION, TENANT.value(), AgentPackProductionIntakeAction.REQUESTER_ID,
                    "agent-pack-production-" + digest, "production-" + digest, PROJECT.value());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
