package io.github.flowerjvm.factory.application.production;

import static io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.*;
import io.github.flowerjvm.flower.action.runtime.duplicate.*;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Shipped Action Runtime controls with the real intake + production acceptance service. No model or Flow runs. */
class AgentPackProductionIntakeActionRuntimeTest {
    @Test
    void changedHostRecipeIsDeniedBeforeCompletedDuplicateLookupOrAnotherInputAssembly() {
        Fixture f = new Fixture(); var input = input("recipe-duplicate"); var p = proposal(input, "recipe-key");
        var first = f.runtime().handle(p, context(input, "normal-owner", true));
        assertTrue(first.terminalSuccess());
        var checks = new AtomicInteger();
        var changed = new AgentPackProductionIntakePolicyGate(f.domain.sessions, f.domain.clock, (tenant, target) -> {
            checks.incrementAndGet(); assertEquals(TENANT, tenant); assertEquals(input, target);
            throw new IllegalArgumentException("INTAKE_RECIPE_CHANGED");
        });
        var denied = f.runtime(changed, f.duplicates).handle(p, context(input, "demo-retry", true));
        assertEquals(ActionExecutionStatus.DENIED, denied.status()); assertTrue(denied.output().isEmpty());
        assertEquals(1, checks.get()); assertEquals(1, f.duplicates.reservations.get());
        assertEquals(1, f.domain.inputReads.get()); assertEquals(1, f.domain.commits.get());
        assertEquals(first, f.runtime().handle(p, context(input, "normal-retry", true)));
        assertEquals(1, f.domain.commits.get());
    }

    @Test
    void recipeChangeBetweenPolicyAndGuardFailsBeforeExecutorAndUntrustedCallerCannotProbeRecipe() {
        Fixture f = new Fixture(); var input = input("recipe-guard"); var checks = new AtomicInteger();
        AgentPackProductionIntakeRecipeCheck current = (tenant, target) -> {
            if (checks.incrementAndGet() > 1) throw new IllegalArgumentException("INTAKE_RECIPE_CHANGED");
        };
        var policy = new AgentPackProductionIntakePolicyGate(f.domain.sessions, f.domain.clock, current);
        var runtime = f.runtime(policy, f.duplicates, current);
        assertEquals(ActionExecutionStatus.DENIED,
                runtime.handle(proposal(input, "key"), context(input, "untrusted", false)).status());
        assertEquals(0, checks.get());
        var result = runtime.handle(proposal(input, "key"), context(input, "guard-owner", true));
        assertFalse(result.terminalSuccess()); assertEquals("PRODUCTION_INTAKE_STALE", result.code());
        assertEquals(2, checks.get()); assertEquals(0, f.domain.inputReads.get()); assertEquals(0, f.domain.commits.get());
    }

    @Test
    void demoReservationScopeCannotAliasAnAbsentNormalOrderAndNormalScopeBytesStayUnchanged() {
        Fixture f = new Fixture(); var input = input("recipe-scope"); var p = proposal(input, "key"); var c = context(input, "scope", true);
        String original = new AgentPackProductionIntakeVisibilityScopeResolver(f.domain.sessions).resolve(p, c);
        assertEquals(original, new AgentPackProductionIntakeVisibilityScopeResolver(f.domain.sessions,
                MaintenanceProductionRecipe.ID).resolve(p, c));
        assertNotEquals(original, new AgentPackProductionIntakeVisibilityScopeResolver(f.domain.sessions,
                MaintenanceRepairDemoScenario.RECIPE_ID).resolve(p, c));
        assertThrows(IllegalArgumentException.class, () -> new AgentPackProductionIntakeVisibilityScopeResolver(f.domain.sessions, "unknown"));
        assertEquals(0, f.domain.inputReads.get());
    }

    @Test
    void v1AndV2DemoReservationsCannotShareEvenAnAbsentOrderCacheEntry() {
        Fixture f = new Fixture(); var input = input("demo-version-scope");
        var p = proposal(input, "same-demo-key"); var c = context(input, "same-owner", true);
        String v1 = new AgentPackProductionIntakeVisibilityScopeResolver(f.domain.sessions,
                MaintenanceRepairDemoScenario.RECIPE_ID).resolve(p, c);
        String v2 = new AgentPackProductionIntakeVisibilityScopeResolver(f.domain.sessions,
                MaintenanceRepairDemoScenario.RECIPE_ID_V2).resolve(p, c);
        assertNotEquals(v1, v2);
        assertEquals(v1, new AgentPackProductionIntakeVisibilityScopeResolver(f.domain.sessions,
                MaintenanceRepairDemoScenario.RECIPE_ID).resolve(p, c));
        assertEquals(v2, new AgentPackProductionIntakeVisibilityScopeResolver(f.domain.sessions,
                MaintenanceRepairDemoScenario.RECIPE_ID_V2).resolve(p, c));
        assertEquals(0, f.domain.inputReads.get()); assertEquals(0, f.domain.commits.get());
    }

    @Test
    void registeredExecutorCommitsPristineSessionAndExactInputsWithoutAnyProductionPhaseOrApproval() {
        Fixture f = new Fixture(); var input = input("fresh");
        var result = f.runtime().handle(proposal(input, "request-fresh"), context(input, "fresh-run", true));
        assertEquals(ActionExecutionStatus.SUCCEEDED, result.status());
        assertEquals("PRODUCTION_ORDER_ACCEPTED", result.code());
        assertEquals(Map.of("buildSessionId", "fresh", "projectId", "project-fresh"), result.output());
        assertEquals(1, f.domain.commits.get()); assertEquals(1, f.domain.inputReads.get());
        var session = f.domain.sessions.find(TENANT, input.buildSessionId()).orElseThrow();
        assertEquals(io.github.flowerjvm.factory.application.build.BuildSessionPhase.UNDERSTAND_CUSTOMER, session.currentPhase());
        assertEquals(io.github.flowerjvm.factory.application.build.BuildSessionStatus.RUNNING, session.status());
        assertEquals(0, session.version()); assertEquals(0, session.repairRound());
        assertTrue(session.currentCandidateId().isEmpty()); assertTrue(session.currentBlueprintRef().isEmpty());
        assertTrue(session.currentCertificationId().isEmpty()); assertFalse(f.audit.isEmpty());
        assertTrue(f.runs.find("fresh-run").isPresent());
    }

    @Test
    void deniedPrincipalCannotReadCompletedCachedResultOrTriggerInputReadsOrAcceptanceAgain() {
        Fixture f = new Fixture(); var input = input("denied"); var p = proposal(input, "same-key");
        var runtime = f.runtime();
        assertEquals(ActionExecutionStatus.SUCCEEDED, runtime.handle(p, context(input, "allowed", true)).status());
        var denied = runtime.handle(p, context(input, "denied", false));
        assertEquals(ActionExecutionStatus.DENIED, denied.status()); assertTrue(denied.output().isEmpty());
        assertFalse(denied.toString().contains("project-denied"));
        assertEquals(1, f.duplicates.reservations.get()); assertEquals(1, f.domain.inputReads.get());
        assertEquals(1, f.domain.commits.get());
    }

    @Test
    void authorizedResourceBReusingResourceAKeyNeverReceivesAResultAndDurableAcceptanceRejectsTheCollision() {
        Fixture f = new Fixture(); var a = input("resource-a"); var b = input("resource-b");
        var runtime = f.runtime(); var pB = proposal(b, "shared-key");
        var first = runtime.handle(proposal(a, "shared-key"), context(a, "first-a", true));
        assertEquals(ActionExecutionStatus.SUCCEEDED, first.status());
        assertTrue(f.policy().evaluate(pB, AgentPackProductionIntakeAction.definition(), context(b, "authorized-b", true))
                .allowedToExecuteNow());
        var resultB = runtime.handle(pB, context(b, "attempt-b", true));
        assertEquals(ActionExecutionStatus.FAILED, resultB.status()); assertTrue(resultB.output().isEmpty());
        assertEquals(RetryDisposition.MANUAL_REVIEW, resultB.retryDisposition());
        assertFalse(resultB.toString().contains("resource-a"));
        assertEquals(2, f.domain.acceptanceAttempts.get()); assertEquals(1, f.domain.commits.get());
        assertEquals(2, f.duplicates.accepted.get());
        var scopes = new AgentPackProductionIntakeVisibilityScopeResolver(f.domain.sessions);
        assertNotEquals(scopes.resolve(proposal(a, "shared-key"), context(a, "scope-a", true)),
                scopes.resolve(pB, context(b, "scope-b", true)));
        var ownB = runtime.handle(proposal(b, "own-b-key"), context(b, "own-b", true));
        assertEquals(ActionExecutionStatus.SUCCEEDED, ownB.status());
        assertNotEquals(first.output(), ownB.output()); assertEquals(2, f.domain.commits.get());
    }

    @Test
    void fullRuntimeReserveVersusCompleteRaceHasOneAcceptOneAtomicCommitAndStableTerminalReplay() throws Exception {
        Fixture f = new Fixture(); var input = input("race"); var p = proposal(input, "race-key");
        var completeReached = new CountDownLatch(1); var boundary = new CyclicBarrier(2);
        var reservations = new AtomicInteger(); var completions = new AtomicInteger();
        DuplicateActionPolicy racing = new DuplicateActionPolicy() {
            public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
                if (reservations.incrementAndGet() == 2) await(boundary);
                return f.duplicates.reserve(proposal, context);
            }
            public void complete(ActionProposal proposal, ExecutionContext context, ActionExecutionResult result) {
                if (completions.incrementAndGet() == 1) { completeReached.countDown(); await(boundary); }
                f.duplicates.complete(proposal, context, result);
            }
            public void release(ActionProposal proposal, ExecutionContext context, Throwable failure) {
                f.duplicates.release(proposal, context, failure);
            }
        };
        var runtime = f.runtime(f.policy(), racing);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> runtime.handle(p, context(input, "race-owner", true)));
            assertTrue(completeReached.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> runtime.handle(p, context(input, "race-duplicate", true)));
            var result = first.get(15, TimeUnit.SECONDS); var duplicate = second.get(15, TimeUnit.SECONDS);
            assertEquals(ActionExecutionStatus.SUCCEEDED, result.status());
            assertTrue(duplicate.status() == ActionExecutionStatus.DENIED || duplicate.equals(result));
            assertEquals(1, f.duplicates.accepted.get()); assertEquals(1, f.domain.commits.get());
            assertEquals(1, f.domain.acceptanceAttempts.get()); assertEquals(1, f.domain.inputReads.get());
            assertEquals(result, runtime.handle(p, context(input, "stable-replay", true)));
            assertEquals(1, f.domain.inputReads.get()); assertEquals(1, f.duplicates.accepted.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"extra-tenant", "extra-requester", "extra-request-key", "missing", "fractional", "text-rounds",
            "huge-rounds", "negative-rounds", "too-many-rounds", "nano-deadline", "offset-deadline", "bad-deadline", "blank-id", "control-project"})
    void strictFourFieldSchemaRejectsAuthorityInjectionAndNonCanonicalValuesBeforeReservation(String mutation) {
        Fixture f = new Fixture(); var input = input("strict"); var fields = new HashMap<>(input.toMap());
        switch (mutation) {
            case "extra-tenant" -> fields.put("tenantId", TENANT.value());
            case "extra-requester" -> fields.put("createdBy", "human");
            case "extra-request-key" -> fields.put("requestKey", "key");
            case "missing" -> fields.remove("projectId");
            case "fractional" -> fields.put("maxRepairRounds", 1.5);
            case "text-rounds" -> fields.put("maxRepairRounds", "2");
            case "huge-rounds" -> fields.put("maxRepairRounds", Long.MAX_VALUE);
            case "negative-rounds" -> fields.put("maxRepairRounds", -1);
            case "too-many-rounds" -> fields.put("maxRepairRounds", 4);
            case "nano-deadline" -> fields.put("deadlineAt", NOW.plusSeconds(600).toString());
            case "offset-deadline" -> fields.put("deadlineAt", "2026-09-06T12:40:00+09:00");
            case "bad-deadline" -> fields.put("deadlineAt", "not-time");
            case "blank-id" -> fields.put("buildSessionId", " ");
            case "control-project" -> fields.put("projectId", "project\nprivate");
            default -> throw new AssertionError(mutation);
        }
        var result = f.runtime().handle(proposal(input, "strict-key").toBuilder().input(fields).build(), context(input, "invalid", true));
        assertEquals(ActionExecutionStatus.VALIDATION_FAILED, result.status());
        assertEquals(0, f.duplicates.reservations.get()); assertEquals(0, f.domain.inputReads.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "session", "resource-type", "permission", "extra-context", "requester", "request-key"})
    void untrustedOrMismatchedCreationAuthorityIsDeniedBeforeAnyInputRead(String mutation) {
        Fixture f = new Fixture(); var input = input("authority"); var p = proposal(input, "authority-key");
        var original = context(input, "authority-run", true); var metadata = new HashMap<>(original.metadata());
        switch (mutation) {
            case "project" -> metadata.put("resource.projectId", "another-project");
            case "session" -> metadata.put("resource.id", "another-session");
            case "resource-type" -> metadata.put("resource.type", "project");
            case "permission" -> metadata.put("actor.permissions", Set.of());
            case "extra-context" -> metadata.put("trustedByPayload", true);
            case "requester" -> p = p.toBuilder().requesterId("other-service").build();
            case "request-key" -> p = p.toBuilder().idempotencyKey("key\nwith-control").build();
            default -> throw new AssertionError(mutation);
        }
        var c = new ExecutionContext(original.tenantId(), original.userId(), original.runId(), original.traceId(), metadata);
        var result = f.runtime().handle(p, c);
        assertEquals(ActionExecutionStatus.DENIED, result.status()); assertTrue(result.output().isEmpty());
        assertEquals(0, f.duplicates.reservations.get()); assertEquals(0, f.domain.inputReads.get());
    }

    @Test
    void existingIdentityConflictAppearingBetweenPolicyAndGuardPreventsExecutorInvocation() {
        Fixture f = new Fixture(); var input = input("guard");
        var other = copy(input, "projectId", new ProjectId("other-project"));
        PolicyGate racing = (proposal, definition, context) -> {
            var decision = f.policy().evaluate(proposal, definition, context);
            assertTrue(decision.allowedToExecuteNow());
            f.domain.intake().accept(TENANT, AgentPackProductionIntakeAction.REQUESTER_ID, "guard-key", other);
            return decision;
        };
        // A no-row policy admits the trusted target; a competing committed row changes the guard's authority.
        var result = f.runtime(racing, DuplicateActionPolicy.acceptAll()).handle(proposal(input, "guard-key"), context(input, "guard-race", true));
        assertFalse(result.terminalSuccess()); assertEquals("PRODUCTION_INTAKE_STALE", result.code());
        assertEquals(1, f.domain.inputReads.get()); assertEquals(1, f.domain.commits.get());
    }

    @Test
    void expiredOrMoreThan48HourDeadlineIsDeniedBeforeReservation() {
        Fixture f = new Fixture(); var input = input("deadline");
        var expiredPolicy = new AgentPackProductionIntakePolicyGate(f.domain.sessions, Clock.fixed(input.deadlineAt(), ZoneOffset.UTC));
        assertEquals(ActionExecutionStatus.DENIED, f.runtime(expiredPolicy, f.duplicates)
                .handle(proposal(input, "expired"), context(input, "expired", true)).status());
        var distant = copy(input, "deadlineAt", input.deadlineAt().plusSeconds(48 * 3600));
        assertEquals(ActionExecutionStatus.DENIED, f.runtime().handle(proposal(distant, "distant"), context(distant, "distant", true)).status());
        assertEquals(0, f.duplicates.reservations.get()); assertEquals(0, f.domain.inputReads.get());
    }

    @Test
    void launcherUsesIndependentTrustedTargetFreshActionRunsAndNeverCallsAnyFlow() {
        var proposals = new ArrayList<ActionProposal>(); var contexts = new ArrayList<ExecutionContext>();
        var ids = new AtomicInteger(); var input = input("launcher");
        var launcher = new ActionBackedAgentPackProductionIntakeLauncher((p, c) -> {
            proposals.add(p); contexts.add(c); return ActionExecutionResult.succeeded(Map.of());
        }, Clock.fixed(NOW, ZoneOffset.UTC), () -> "lifecycle-" + ids.incrementAndGet());
        launcher.submit(TENANT, input.buildSessionId(), input.projectId(), "logical-key", input);
        launcher.submit(TENANT, input.buildSessionId(), input.projectId(), "logical-key", input);
        assertNotEquals(contexts.getFirst().runId(), contexts.getLast().runId());
        assertNotEquals(proposals.getFirst().proposalId(), proposals.getLast().proposalId());
        assertEquals("logical-key", proposals.getFirst().idempotencyKey());
        assertEquals(proposals.getFirst().idempotencyKey(), proposals.getLast().idempotencyKey());
        assertEquals(contexts.getFirst().traceId(), contexts.getLast().traceId());
        assertEquals(input.projectId().value(), contexts.getFirst().metadata().get("resource.projectId"));
        assertEquals(AgentPackProductionIntakeAction.REQUESTER_ID, contexts.getFirst().userId());
        assertThrows(IllegalArgumentException.class, () -> launcher.submit(TENANT, new BuildSessionId("untrusted"), input.projectId(), "key", input));
        assertThrows(IllegalArgumentException.class, () -> launcher.submit(TENANT, input.buildSessionId(), new ProjectId("untrusted"), "key", input));
        assertEquals(2, proposals.size());
    }

    @Test
    void unknownInputAssemblyFailureRequiresManualReviewAndNeverDisclosesPrivateFilesystemDetails() {
        Fixture f = new Fixture(); var input = input("failure");
        f.domain.inputFailure = new IllegalStateException("private-local-path-and-credential-name");
        var result = f.runtime().handle(proposal(input, "failure-key"), context(input, "failure-run", true));
        assertEquals(ActionExecutionStatus.FAILED, result.status()); assertEquals(RetryDisposition.MANUAL_REVIEW, result.retryDisposition());
        assertEquals("PRODUCTION_INTAKE_UNCERTAIN", result.code()); assertTrue(result.output().isEmpty());
        assertFalse(result.toString().contains("private-local")); assertEquals(0, f.domain.commits.get());
    }

    @Test
    void absentRowScopesDistinguishLimitsAndDeadlinesWithoutTreatingPayloadAsResourceAuthority() {
        Fixture f = new Fixture(); var a = input("no-row");
        var b = copy(a, "maxRepairRounds", 1); var c = copy(a, "deadlineAt", a.deadlineAt().plusSeconds(1));
        var scopes = new AgentPackProductionIntakeVisibilityScopeResolver(f.domain.sessions);
        String scopeA = scopes.resolve(proposal(a, "same-key"), context(a, "a", true));
        assertNotEquals(scopeA, scopes.resolve(proposal(b, "same-key"), context(b, "b", true)));
        assertNotEquals(scopeA, scopes.resolve(proposal(c, "same-key"), context(c, "c", true)));
        assertEquals(0, f.domain.inputReads.get());
    }

    private static ActionProposal proposal(AgentPackProductionIntakeInput input, String key) {
        return ActionProposal.builder(AgentPackProductionIntakeAction.ACTION_ID).requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE).requesterId(AgentPackProductionIntakeAction.REQUESTER_ID)
                .input(input.toMap()).idempotencyKey(key).build();
    }
    private static ExecutionContext context(AgentPackProductionIntakeInput input, String run, boolean allowed) {
        return new ExecutionContext(TENANT.value(), allowed ? AgentPackProductionIntakeAction.REQUESTER_ID : "denied-principal",
                run, "test-trace", Map.of("actor.permissions", Set.of(AgentPackProductionIntakeAction.PERMISSION),
                        "resource.type", AgentPackProductionIntakeAction.RESOURCE_TYPE,
                        "resource.id", input.buildSessionId().value(), "resource.projectId", input.projectId().value()));
    }
    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static final class Fixture {
        final AgentPackProductionIntakeTestFixture domain = new AgentPackProductionIntakeTestFixture();
        final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
        final InMemoryRunStore runs = new InMemoryRunStore();
        final CountingDuplicates duplicates = new CountingDuplicates(new InMemoryDuplicateActionPolicy(
                new AgentPackProductionIntakeVisibilityScopeResolver(domain.sessions)));
        AgentPackProductionIntakePolicyGate policy() { return new AgentPackProductionIntakePolicyGate(domain.sessions, domain.clock); }
        DefaultActionRuntime runtime() { return runtime(policy(), duplicates); }
        DefaultActionRuntime runtime(PolicyGate policy, DuplicateActionPolicy duplicatePolicy) {
            return runtime(policy, duplicatePolicy, (tenant, input) -> { });
        }
        DefaultActionRuntime runtime(PolicyGate policy, DuplicateActionPolicy duplicatePolicy,
                                     AgentPackProductionIntakeRecipeCheck recipes) {
            return new DefaultActionRuntime(new InMemoryActionRegistry(List.of(new AgentPackProductionIntakeActionExecutor(domain.intake()))),
                    new AgentPackProductionIntakeActionValidator(), policy, ApprovalGate.unsupported(), duplicatePolicy, audit::add,
                    TraceSink.noop(), runs, new AgentPackProductionIntakePreExecutionGuard(domain.sessions, domain.clock, recipes));
        }
    }
    private static final class CountingDuplicates implements DuplicateActionPolicy {
        final DuplicateActionPolicy delegate; final AtomicInteger reservations = new AtomicInteger(); final AtomicInteger accepted = new AtomicInteger();
        CountingDuplicates(DuplicateActionPolicy delegate) { this.delegate = delegate; }
        public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
            reservations.incrementAndGet(); var result = delegate.reserve(proposal, context);
            if (result.type() == DuplicateActionDecisionType.ACCEPT) accepted.incrementAndGet(); return result;
        }
        public void complete(ActionProposal proposal, ExecutionContext context, ActionExecutionResult result) { delegate.complete(proposal, context, result); }
        public void release(ActionProposal proposal, ExecutionContext context, Throwable cause) { delegate.release(proposal, context, cause); }
    }
}
