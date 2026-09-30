package io.github.flowerjvm.factory.application.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEvent;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecision;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecisionType;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.duplicate.InMemoryDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Real Action Runtime controls. The counted domain fixture proves preparation only, never a produced product. */
class AgentPackProductionActionRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-09-06T03:30:00Z");
    private static final TenantId TENANT = new TenantId("production-action-tenant");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void registryAndStrictInputRejectUnknownActionExtraAuthorityFieldsAndFractionalVersions() {
        Fixture f = new Fixture();
        BuildSession session = session("strict"); f.sessions.create(session);
        var runtime = f.runtime(f.policy(), f.duplicates, f.preparer());
        assertEquals(ActionExecutionStatus.VALIDATION_FAILED, runtime.handle(proposal(session)
                .toBuilder().input(Map.of("buildSessionId", session.buildSessionId().value(),
                        "expectedSessionVersion", 0, "tenantId", TENANT.value())).build(), context(session, "extra", true)).status());
        assertEquals(ActionExecutionStatus.VALIDATION_FAILED, runtime.handle(proposal(session)
                .toBuilder().input(Map.of("buildSessionId", session.buildSessionId().value(),
                        "expectedSessionVersion", 0.5)).build(), context(session, "fractional", true)).status());
        var unknown = ActionProposal.builder("factory.unknown").requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE).requesterId(AgentPackProductionAction.REQUESTER_ID)
                .input(Map.of()).idempotencyKey("unknown").build();
        assertFalse(runtime.handle(unknown, context(session, "unknown", true)).terminalSuccess());
        assertEquals(0, f.effects.get()); assertEquals(0, f.duplicates.reservations.get());
    }

    @Test
    void deniedPrincipalCannotReadTheCompletedCachedResultOrInvokePreparationAgain() {
        Fixture f = new Fixture(); BuildSession session = session("denied"); f.sessions.create(session);
        var runtime = f.runtime(f.policy(), f.duplicates, f.preparer());
        var first = runtime.handle(proposal(session), context(session, "authorized", true));
        assertEquals(ActionExecutionStatus.SUCCEEDED, first.status());
        assertEquals(1, f.effects.get()); assertEquals(1, f.duplicates.reservations.get());
        var denied = runtime.handle(proposal(session), context(session, "denied", false));
        assertEquals(ActionExecutionStatus.DENIED, denied.status());
        assertTrue(denied.output().isEmpty());
        assertEquals(1, f.effects.get()); assertEquals(1, f.duplicates.reservations.get());
        assertFalse(f.audit.isEmpty());
    }

    @Test
    void authorizedOtherResourceCannotReuseTheFirstResourcesLogicalKeyOrCachedResult() {
        Fixture f = new Fixture(); BuildSession a = session("resource-a"), b = session("resource-b");
        f.sessions.create(a); f.sessions.create(b);
        var runtime = f.runtime(f.policy(), f.duplicates, f.preparer());
        var first = runtime.handle(proposal(a), context(a, "first-a", true));
        var otherwiseAuthorized = proposal(b);
        assertTrue(f.policy().evaluate(otherwiseAuthorized, AgentPackProductionAction.definition(), context(b, "check-b", true))
                .allowedToExecuteNow());
        var reuse = runtime.handle(otherwiseAuthorized.toBuilder().idempotencyKey(proposal(a).idempotencyKey()).build(),
                context(b, "reused-a-key", true));
        assertEquals(ActionExecutionStatus.DENIED, reuse.status()); assertTrue(reuse.output().isEmpty());
        assertEquals(1, f.effects.get()); assertEquals(1, f.duplicates.reservations.get());
        var actualB = runtime.handle(proposal(b), context(b, "real-b", true));
        assertEquals(ActionExecutionStatus.SUCCEEDED, actualB.status());
        assertNotEquals(first.output().get("workOrderId"), actualB.output().get("workOrderId"));
        var scopes = new AgentPackProductionVisibilityScopeResolver(f.sessions);
        assertNotEquals(scopes.resolve(proposal(a), context(a, "scope-a", true)),
                scopes.resolve(proposal(b), context(b, "scope-b", true)));
        assertEquals(2, f.effects.get());
    }

    @Test
    void separateTenantAndForgedTrustedResourceCannotObserveAnotherSessionsResult() {
        Fixture f = new Fixture(); BuildSession a = session("tenant"); f.sessions.create(a);
        var runtime = f.runtime(f.policy(), f.duplicates, f.preparer());
        runtime.handle(proposal(a), context(a, "first", true));
        ExecutionContext original = context(a, "foreign", true);
        var foreign = new ExecutionContext("other-tenant", original.userId(), original.runId(), original.traceId(), original.metadata());
        assertEquals(ActionExecutionStatus.DENIED, runtime.handle(proposal(a), foreign).status());
        var wrongResource = new ExecutionContext(original.tenantId(), original.userId(), "wrong-resource", original.traceId(),
                Map.of("actor.permissions", Set.of(AgentPackProductionAction.PERMISSION), "resource.type", "build-session", "resource.id", "another"));
        assertEquals(ActionExecutionStatus.DENIED, runtime.handle(proposal(a), wrongResource).status());
        assertEquals(1, f.effects.get()); assertEquals(1, f.duplicates.reservations.get());
    }

    @Test
    void cancellationBetweenPolicyAndGuardPreventsTheRegisteredExecutor() {
        Fixture f = new Fixture(); BuildSession a = session("guard"); f.sessions.create(a);
        PolicyGate changingPolicy = (proposal, definition, context) -> {
            var allowed = f.policy().evaluate(proposal, definition, context);
            assertTrue(allowed.allowedToExecuteNow());
            assertTrue(f.sessions.compareAndSet(a, a.requestCancellation(NOW)));
            return allowed;
        };
        var result = f.runtime(changingPolicy, f.duplicates, f.preparer()).handle(proposal(a), context(a, "guard-race", true));
        assertFalse(result.terminalSuccess()); assertEquals("PRODUCTION_PREPARATION_STALE", result.code());
        assertEquals(0, f.effects.get());
    }

    @Test
    void deadlineAndStaleVersionAreDeniedBeforeTheDuplicateBoundary() {
        Fixture f = new Fixture(); BuildSession a = session("deadline"); f.sessions.create(a);
        var runtime = f.runtime(f.policy(), f.duplicates, f.preparer());
        var stale = proposal(a).toBuilder().input(new AgentPackProductionInput(a.buildSessionId(), 1).toMap()).build();
        assertEquals(ActionExecutionStatus.DENIED, runtime.handle(stale, context(a, "stale", true)).status());
        var expiredPolicy = new AgentPackProductionPolicyGate(f.sessions, Clock.fixed(a.deadlineAt(), ZoneOffset.UTC));
        assertEquals(ActionExecutionStatus.DENIED, f.runtime(expiredPolicy, f.duplicates, f.preparer())
                .handle(proposal(a), context(a, "expired", true)).status());
        assertEquals(0, f.effects.get()); assertEquals(0, f.duplicates.reservations.get());
    }

    @Test
    void fullRuntimeReserveVersusCompleteRaceHasOneAcceptOnePreparationAndStableTerminalReplay() throws Exception {
        Fixture f = new Fixture(); BuildSession a = session("race"); f.sessions.create(a);
        var completeReached = new CountDownLatch(1);
        var boundary = new CyclicBarrier(2);
        var calls = new AtomicInteger();
        var completions = new AtomicInteger();
        DuplicateActionPolicy racing = new DuplicateActionPolicy() {
            public DuplicateActionDecision reserve(ActionProposal p, ExecutionContext c) {
                if (calls.incrementAndGet() == 2) await(boundary);
                return f.duplicates.reserve(p, c);
            }
            public void complete(ActionProposal p, ExecutionContext c, ActionExecutionResult result) {
                if (completions.incrementAndGet() == 1) { completeReached.countDown(); await(boundary); }
                f.duplicates.complete(p, c, result);
            }
            public void release(ActionProposal p, ExecutionContext c, Throwable cause) { f.duplicates.release(p, c, cause); }
        };
        var runtime = f.runtime(f.policy(), racing, f.preparer());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> runtime.handle(proposal(a), context(a, "race-owner", true)));
            assertTrue(completeReached.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> runtime.handle(proposal(a), context(a, "race-duplicate", true)));
            ActionExecutionResult result = first.get(15, TimeUnit.SECONDS);
            ActionExecutionResult duplicate = second.get(15, TimeUnit.SECONDS);
            assertEquals(ActionExecutionStatus.SUCCEEDED, result.status());
            assertTrue(duplicate.status() == ActionExecutionStatus.DENIED || duplicate.equals(result));
            assertEquals(1, f.duplicates.accepted.get()); assertEquals(1, f.effects.get());
            var retry = runtime.handle(proposal(a), context(a, "terminal-retry", true));
            assertEquals(result, retry); assertEquals(1, f.effects.get()); assertEquals(1, f.duplicates.accepted.get());
        }
    }

    @Test
    void launcherKeepsLogicalIdentityButUsesFreshLifecycleIdsAndOnlyCallsTheRuntime() {
        BuildSession a = session("launcher");
        var proposals = new ArrayList<ActionProposal>(); var contexts = new ArrayList<ExecutionContext>();
        var ids = new AtomicInteger();
        var launcher = new ActionBackedAgentPackProductionLauncher((proposal, context) -> {
            proposals.add(proposal); contexts.add(context); return ActionExecutionResult.succeeded(Map.of());
        }, CLOCK, () -> "lifecycle-" + ids.incrementAndGet());
        launcher.prepare(a); launcher.prepare(a);
        assertEquals(proposals.getFirst().idempotencyKey(), proposals.getLast().idempotencyKey());
        assertNotEquals(proposals.getFirst().proposalId(), proposals.getLast().proposalId());
        assertNotEquals(contexts.getFirst().runId(), contexts.getLast().runId());
        assertEquals(TENANT.value(), contexts.getFirst().tenantId());
        assertEquals(AgentPackProductionAction.REQUESTER_ID, contexts.getFirst().userId());
        assertEquals(Set.of("buildSessionId", "expectedSessionVersion"), proposals.getFirst().input().keySet());
        assertEquals(a.buildSessionId().value(), contexts.getFirst().metadata().get("resource.id"));
        assertThrows(IllegalArgumentException.class, () -> launcher.prepare(a.requestCancellation(NOW)));
    }

    @Test
    void unknownPreparationFailureRemainsManualReviewAndNeverExposesPrivateExceptionText() {
        Fixture f = new Fixture(); BuildSession a = session("failure"); f.sessions.create(a);
        var result = f.runtime(f.policy(), f.duplicates, (tenant, session, version) -> {
            throw new IllegalStateException("private-detail-must-not-escape");
        }).handle(proposal(a), context(a, "failure-run", true));
        assertEquals(ActionExecutionStatus.FAILED, result.status());
        assertEquals(RetryDisposition.MANUAL_REVIEW, result.retryDisposition());
        assertEquals("PRODUCTION_PREPARATION_UNCERTAIN", result.code());
        assertFalse(result.toString().contains("private-detail"));
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static ActionProposal proposal(BuildSession session) {
        return ActionProposal.builder(AgentPackProductionAction.ACTION_ID).requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE).requesterId(AgentPackProductionAction.REQUESTER_ID)
                .input(new AgentPackProductionInput(session.buildSessionId(), session.version()).toMap())
                .idempotencyKey(AgentPackProductionIdempotencyKeys.derive(session)).build();
    }

    private static ExecutionContext context(BuildSession session, String run, boolean allowed) {
        return new ExecutionContext(session.tenantId().value(), allowed ? AgentPackProductionAction.REQUESTER_ID : "denied-principal",
                run, AgentPackProductionIdempotencyKeys.trace(session), Map.of("actor.permissions",
                        allowed ? Set.of(AgentPackProductionAction.PERMISSION) : Set.of(),
                        "resource.type", AgentPackProductionAction.RESOURCE_TYPE, "resource.id", session.buildSessionId().value()));
    }

    private static BuildSession session(String id) {
        return new BuildSession(new BuildSessionId(id), TENANT, new ProjectId("project-" + id), ProductLineId.AGENT_PACK,
                "request-" + id, "human-owner", BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT,
                new ArtifactReference("artifact:requirements:" + id), new ContentHash("a".repeat(64)),
                Optional.of("manager"), Optional.of("coding"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 0, 2, NOW.minusSeconds(1), NOW.plusSeconds(60), Optional.empty(), Optional.empty(),
                Optional.empty(), 0, NOW.minusSeconds(1), NOW.minusSeconds(1));
    }

    private static final class Fixture {
        final Sessions sessions = new Sessions();
        final AtomicInteger effects = new AtomicInteger();
        final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
        final CountingDuplicates duplicates = new CountingDuplicates(new InMemoryDuplicateActionPolicy(
                new AgentPackProductionVisibilityScopeResolver(sessions)));
        AgentPackProductionPolicyGate policy() { return new AgentPackProductionPolicyGate(sessions, CLOCK); }
        AgentPackProductionPreparer preparer() {
            return (tenant, session, version) -> {
                effects.incrementAndGet();
                return new ProductionPreparationResult("PRODUCTION_WORK_PREPARED", Optional.of(new WorkOrderId("work-" + session.value())),
                        Optional.of(new WorkerRunId("worker-" + session.value())), Optional.empty());
            };
        }
        DefaultActionRuntime runtime(PolicyGate policy, DuplicateActionPolicy duplicates, AgentPackProductionPreparer preparer) {
            return new DefaultActionRuntime(new InMemoryActionRegistry(List.of(new AgentPackProductionActionExecutor(preparer))),
                    new AgentPackProductionActionValidator(), policy, ApprovalGate.unsupported(), duplicates, audit::add,
                    TraceSink.noop(), new InMemoryRunStore(), new AgentPackProductionPreExecutionGuard(sessions, CLOCK));
        }
    }

    private static final class CountingDuplicates implements DuplicateActionPolicy {
        final DuplicateActionPolicy delegate;
        final AtomicInteger reservations = new AtomicInteger();
        final AtomicInteger accepted = new AtomicInteger();
        CountingDuplicates(DuplicateActionPolicy delegate) { this.delegate = delegate; }
        public DuplicateActionDecision reserve(ActionProposal p, ExecutionContext c) {
            reservations.incrementAndGet(); var decision = delegate.reserve(p, c);
            if (decision.type() == DuplicateActionDecisionType.ACCEPT) accepted.incrementAndGet();
            return decision;
        }
        public void complete(ActionProposal p, ExecutionContext c, ActionExecutionResult result) { delegate.complete(p, c, result); }
        public void release(ActionProposal p, ExecutionContext c, Throwable cause) { delegate.release(p, c, cause); }
    }

    private static final class Sessions implements BuildSessionRepository {
        private final ConcurrentHashMap<String, BuildSession> records = new ConcurrentHashMap<>();
        private static String key(TenantId tenant, BuildSessionId id) { return tenant.value() + "\n" + id.value(); }
        public void create(BuildSession session) {
            if (records.putIfAbsent(key(session.tenantId(), session.buildSessionId()), session) != null) throw new IllegalArgumentException("duplicate");
        }
        public Optional<BuildSession> find(TenantId tenant, BuildSessionId id) { return Optional.ofNullable(records.get(key(tenant, id))); }
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            if (next.version() != expected.version() + 1) throw new IllegalArgumentException("CAS version");
            return records.replace(key(expected.tenantId(), expected.buildSessionId()), expected, next);
        }
    }
}
