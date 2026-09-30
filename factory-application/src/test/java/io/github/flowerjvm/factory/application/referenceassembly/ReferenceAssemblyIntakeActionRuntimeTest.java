package io.github.flowerjvm.factory.application.referenceassembly;

import static io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.*;
import io.github.flowerjvm.flower.action.runtime.duplicate.*;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Full shipped runtime pipeline, real service, synthetic immutable certification and transaction. */
class ReferenceAssemblyIntakeActionRuntimeTest {
    @Test void registeredExecutorAcceptsExactMaintenanceComponentWithoutChangingCertifiedBytesOrStartingAnyOtherPhase() {
        Fixture f = new Fixture(); var input = f.domain.input("new");
        var original = f.domain.certification;
        var result = f.runtime().handle(proposal(input, "key"), context(input, "run", true));
        assertEquals(ActionExecutionStatus.SUCCEEDED, result.status()); assertEquals("REFERENCE_ASSEMBLY_ORDER_ACCEPTED", result.code());
        assertEquals(Map.of("buildSessionId", "new", "projectId", "project-new"), result.output());
        var session = f.domain.service().requireAcceptedSession(TENANT, input.projectId(), input.buildSessionId(), "key", input);
        assertEquals(ProductLineId.REFERENCE_ASSEMBLY, session.productLineId());
        assertEquals(BuildSessionStatus.RUNNING, session.status()); assertEquals(BuildSessionPhase.UNDERSTAND_CUSTOMER, session.currentPhase());
        assertEquals(0, session.version()); assertEquals(0, session.repairRound()); assertEquals(0, session.maxRepairRounds());
        assertTrue(session.currentCandidateId().isEmpty()); assertTrue(session.selectedCodingWorkerBinding().isEmpty());
        assertEquals(original, f.domain.certification); assertEquals(1, f.domain.fullReads.get()); assertEquals(1, f.domain.commits.get());
        assertTrue(f.domain.artifactRows.values().stream().noneMatch(a -> a.reference().equals(original.certificationManifest().orElseThrow().reference())));
        assertFalse(f.audit.isEmpty()); assertTrue(f.runs.find("run").isPresent());
    }

    @Test void deniedPrincipalCannotReadAuthorizedCachedResultOrCauseAnotherFullReadOrCommit() {
        Fixture f = new Fixture(); var input = f.domain.input("private"); var runtime = f.runtime(); var p = proposal(input, "key");
        assertTrue(runtime.handle(p, context(input, "owner", true)).terminalSuccess());
        var denied = runtime.handle(p, context(input, "denied", false));
        assertEquals(ActionExecutionStatus.DENIED, denied.status()); assertTrue(denied.output().isEmpty());
        assertFalse(denied.toString().contains("project-private")); assertEquals(1, f.duplicates.reserves.get());
        assertEquals(1, f.domain.fullReads.get()); assertEquals(1, f.domain.commits.get());
    }

    @Test void authorizedResourceBReusingAKeyCannotReadACachedResultAndItsOwnKeyStillWorks() {
        Fixture f = new Fixture(); var a = f.domain.input("a"); var b = f.domain.input("b"); var runtime = f.runtime();
        var visibility = new ReferenceAssemblyIntakeVisibilityScopeResolver(f.admission);
        assertNotEquals(visibility.resolve(proposal(a, "key"), context(a, "scope-a", true)), visibility.resolve(proposal(b, "key"), context(b, "scope-b", true)));
        assertTrue(runtime.handle(proposal(a, "key"), context(a, "owner-a", true)).terminalSuccess());
        var rejected = runtime.handle(proposal(b, "key"), context(b, "owner-b", true));
        assertEquals(ActionExecutionStatus.DENIED, rejected.status()); assertTrue(rejected.output().isEmpty());
        assertFalse(rejected.toString().contains("project-a")); assertEquals(1, f.domain.commits.get());
        assertTrue(runtime.handle(proposal(b, "own-key"), context(b, "own-b", true)).terminalSuccess()); assertEquals(2, f.domain.commits.get());
    }

    @ParameterizedTest @ValueSource(strings = {"deadlineAt", "sourceHash", "certificationManifestHash", "projectId"})
    void changedPayloadUnderCompletedKeyIsRejectedBeforeCachedLookup(String field) {
        Fixture f = new Fixture(); var input = f.domain.input("changed"); var runtime = f.runtime();
        assertTrue(runtime.handle(proposal(input, "key"), context(input, "first", true)).terminalSuccess());
        var changed = new HashMap<>(input.toMap());
        changed.put(field, switch (field) { case "deadlineAt" -> input.deadlineAt().plusSeconds(1).toString(); case "projectId" -> "other-project"; default -> "0".repeat(64); });
        var p = proposal(input, "key").toBuilder().input(changed).build();
        var result = runtime.handle(p, context(input, "changed", true));
        assertEquals(ActionExecutionStatus.DENIED, result.status()); assertTrue(result.output().isEmpty());
        assertEquals(1, f.domain.commits.get()); assertEquals(1, f.duplicates.reserves.get());
    }

    @Test void revokedCertificationIsRejectedBeforeReturningAnEarlierSuccess() {
        Fixture f = new Fixture(); var input = f.domain.input("revoke"); var p = proposal(input, "key"); var runtime = f.runtime();
        assertTrue(runtime.handle(p, context(input, "first", true)).terminalSuccess());
        f.domain.certification = f.domain.certification.revoke("REVOKED", NOW);
        var result = runtime.handle(p, context(input, "after-revoke", true));
        assertEquals(ActionExecutionStatus.DENIED, result.status()); assertTrue(result.output().isEmpty());
        assertEquals(1, f.duplicates.reserves.get()); assertEquals(1, f.domain.fullReads.get());
    }

    @Test void certificationChangeBetweenPolicyAndGuardPreventsExecutorDispatch() {
        Fixture f = new Fixture(); var input = f.domain.input("guard");
        PolicyGate gate = (p, d, c) -> { var result = f.policy().evaluate(p, d, c); f.domain.certification = f.domain.certification.revoke("REVOKED", NOW); return result; };
        var result = f.runtime(gate, f.duplicates).handle(proposal(input, "key"), context(input, "run", true));
        assertFalse(result.terminalSuccess()); assertEquals("REFERENCE_ASSEMBLY_INTAKE_STALE", result.code());
        assertEquals(0, f.domain.fullReads.get()); assertEquals(0, f.domain.commits.get());
    }

    @Test void fullReadFailureNeverStagesArtifactsAndReturnsOnlyGenericManualReview() {
        Fixture f = new Fixture(); var input = f.domain.input("failed");
        f.domain.duringFullRead = () -> { throw new IllegalStateException("private-source-path"); };
        var result = f.runtime().handle(proposal(input, "key"), context(input, "run", true));
        assertEquals(ActionExecutionStatus.FAILED, result.status()); assertEquals(RetryDisposition.MANUAL_REVIEW, result.retryDisposition());
        assertTrue(result.output().isEmpty()); assertFalse(result.toString().contains("private-source"));
        assertEquals(0, f.domain.commits.get()); assertTrue(f.domain.artifactRows.isEmpty());
    }

    @Test void fullReadCrossingDeadlineNeverReachesAtomicAcceptance() {
        Fixture f = new Fixture(); var input = f.domain.input("deadline"); f.domain.duringFullRead = () -> f.domain.clock.now = input.deadlineAt();
        var result = f.runtime().handle(proposal(input, "key"), context(input, "run", true));
        assertFalse(result.terminalSuccess()); assertEquals(0, f.domain.attempts.get()); assertEquals(0, f.domain.commits.get());
    }

    @Test void certificationRevokedAfterServiceCheckIsRefusedByAtomicBoundary() {
        Fixture f = new Fixture(); var input = f.domain.input("commit"); f.domain.beforeCommit = () -> f.domain.certification = f.domain.certification.revoke("REVOKED", NOW);
        var result = f.runtime().handle(proposal(input, "key"), context(input, "run", true));
        assertFalse(result.terminalSuccess()); assertEquals(1, f.domain.attempts.get()); assertEquals(0, f.domain.commits.get());
    }

    @Test void exactRestartReadbackPreservesOriginalTimestampAndProgressedSessionWithoutResettingIt() {
        Fixture f = new Fixture(); var input = f.domain.input("restart"); var p = proposal(input, "key");
        assertTrue(f.runtime().handle(p, context(input, "first", true)).terminalSuccess());
        var initial = f.domain.sessions.find(TENANT, input.buildSessionId()).orElseThrow();
        var progressed = copy(initial, "currentPhase", BuildSessionPhase.RESOLVE_REUSE_STRATEGY, "version", 1L, "updatedAt", NOW.plusSeconds(1));
        f.domain.sessions.compareAndSet(initial, progressed); f.domain.clock.now = NOW.plusSeconds(2);
        // Recreate the runtime index explicitly; durable JDBC reservation recovery is covered by infrastructure tests.
        assertTrue(f.runtime(f.policy(), new InMemoryDuplicateActionPolicy(new ReferenceAssemblyIntakeVisibilityScopeResolver(f.admission)))
                .handle(p, context(input, "restarted", true)).terminalSuccess());
        assertEquals(progressed, f.domain.service().requireAcceptedSession(TENANT, input.projectId(), input.buildSessionId(), "key", input));
        assertEquals(initial.createdAt(), progressed.createdAt()); assertEquals(1, f.domain.commits.get());
    }

    @Test void lostAcknowledgementCanOnlyReobserveExactCommittedDomainTruthWithoutASecondSession() {
        Fixture f = new Fixture(); var input = f.domain.input("ack"); var p = proposal(input, "key"); f.domain.failAfterCommit = true;
        var uncertain = f.runtime().handle(p, context(input, "lost-ack", true));
        assertEquals(RetryDisposition.MANUAL_REVIEW, uncertain.retryDisposition()); assertEquals(1, f.domain.commits.get());
        f.domain.failAfterCommit = false;
        var result = f.runtime(f.policy(), new InMemoryDuplicateActionPolicy(new ReferenceAssemblyIntakeVisibilityScopeResolver(f.admission)))
                .handle(p, context(input, "canonical-readback", true));
        assertTrue(result.terminalSuccess()); assertEquals(1, f.domain.commits.get()); assertEquals(1, f.domain.sessionRows.size());
    }

    @Test void oldUnreceiptedSessionCannotBePromotedToNewIntakeOrRepairedByCatalogRestaging() {
        Fixture f = new Fixture(); var input = f.domain.input("legacy");
        assertTrue(f.runtime().handle(proposal(input, "key"), context(input, "first", true)).terminalSuccess());
        f.domain.artifactRows.remove(key(TENANT, ReferenceAssemblyIntakeReceipt.reference(TENANT, "key").value()));
        var result = f.runtime().handle(proposal(input, "key"), context(input, "legacy", true));
        assertEquals(ActionExecutionStatus.DENIED, result.status()); assertTrue(result.output().isEmpty()); assertEquals(1, f.domain.commits.get());
    }

    @Test void cancelledAcceptedSessionCannotUseAnEarlierCachedSuccessToRestartProduction() {
        Fixture f = new Fixture(); var input = f.domain.input("cancelled"); var p = proposal(input, "key"); var runtime = f.runtime();
        assertTrue(runtime.handle(p, context(input, "first", true)).terminalSuccess());
        var initial = f.domain.sessions.find(TENANT, input.buildSessionId()).orElseThrow();
        f.domain.sessions.compareAndSet(initial, copy(initial, "cancellationRequestedAt", Optional.of(NOW), "version", 1L));
        var result = runtime.handle(p, context(input, "cancelled", true));
        assertEquals(ActionExecutionStatus.DENIED, result.status()); assertTrue(result.output().isEmpty()); assertEquals(1, f.domain.commits.get());
    }

    @Test void fullRuntimeReserveVersusCompleteRaceHasOneAcceptOneExecutorAndStableTerminalResult() throws Exception {
        Fixture f = new Fixture(); var input = f.domain.input("race"); var p = proposal(input, "key");
        var ready = new CountDownLatch(1); var boundary = new CyclicBarrier(2); var reserves = new AtomicInteger(); var completes = new AtomicInteger();
        DuplicateActionPolicy racing = new DuplicateActionPolicy() {
            public DuplicateActionDecision reserve(ActionProposal p, ExecutionContext c) { if (reserves.incrementAndGet() == 2) await(boundary); return f.duplicates.reserve(p, c); }
            public void complete(ActionProposal p, ExecutionContext c, ActionExecutionResult r) { if (completes.incrementAndGet() == 1) { ready.countDown(); await(boundary); } f.duplicates.complete(p, c, r); }
            public void release(ActionProposal p, ExecutionContext c, Throwable t) { f.duplicates.release(p, c, t); }
        };
        var runtime = f.runtime(f.policy(), racing);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> runtime.handle(p, context(input, "owner", true))); assertTrue(ready.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> runtime.handle(p, context(input, "contender", true)));
            var result = first.get(15, TimeUnit.SECONDS); var duplicate = second.get(15, TimeUnit.SECONDS);
            assertTrue(result.terminalSuccess()); assertTrue(duplicate.terminalSuccess() || duplicate.status() == ActionExecutionStatus.DENIED);
            assertEquals(1, f.duplicates.accepts.get()); assertEquals(1, f.domain.fullReads.get()); assertEquals(1, f.domain.commits.get());
            assertEquals(result, runtime.handle(p, context(input, "later", true)));
        }
    }

    @Test void concurrentDifferentPayloadsWithSameKeyHaveOneDomainWinnerAndNoTerminalOverwrite() throws Exception {
        Fixture f = new Fixture(); var a = f.domain.input("different"); var b = copy(a, "deadlineAt", a.deadlineAt().plusSeconds(1));
        var barrier = new CyclicBarrier(2); f.domain.beforeCommit = () -> await(barrier); var runtime = f.runtime();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> runtime.handle(proposal(a, "key"), context(a, "a", true)));
            var second = pool.submit(() -> runtime.handle(proposal(b, "key"), context(b, "b", true)));
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(ActionExecutionResult::terminalSuccess).count());
            assertEquals(1, f.domain.commits.get()); assertEquals(2, f.domain.attempts.get());
            assertTrue(results.stream().filter(r -> !r.terminalSuccess()).allMatch(r -> r.output().isEmpty()));
        }
    }

    @Test void launcherHasFreshLifecycleIdsIndependentTrustedTargetAndNoFlowReference() {
        Fixture f = new Fixture(); var input = f.domain.input("launch"); var contexts = new ArrayList<ExecutionContext>(); var proposals = new ArrayList<ActionProposal>();
        var ids = new AtomicInteger(); var launcher = new ActionBackedReferenceAssemblyIntakeLauncher((p, c) -> {
            proposals.add(p); contexts.add(c); return ActionExecutionResult.succeeded(Map.of());
        }, f.domain.clock, () -> "lifecycle-" + ids.incrementAndGet());
        launcher.submit(TENANT, input.projectId(), input.buildSessionId(), "key", input);
        launcher.submit(TENANT, input.projectId(), input.buildSessionId(), "key", input);
        assertNotEquals(contexts.getFirst().runId(), contexts.getLast().runId()); assertNotEquals(proposals.getFirst().proposalId(), proposals.getLast().proposalId());
        assertEquals(contexts.getFirst().traceId(), contexts.getLast().traceId()); assertEquals("key", proposals.getFirst().idempotencyKey());
        assertThrows(IllegalArgumentException.class, () -> launcher.submit(TENANT, new ProjectId("other"), input.buildSessionId(), "key", input));
        assertThrows(IllegalArgumentException.class, () -> launcher.submit(TENANT, input.projectId(), new BuildSessionId("other"), "key", input));
    }

    static ActionProposal proposal(ReferenceAssemblyIntakeInput input, String key) {
        return ActionProposal.builder(ReferenceAssemblyIntakeAction.ACTION_ID).requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE).requesterId(ReferenceAssemblyIntakeAction.REQUESTER_ID).idempotencyKey(key).input(input.toMap()).build();
    }
    static ExecutionContext context(ReferenceAssemblyIntakeInput input, String run, boolean allowed) {
        return new ExecutionContext(TENANT.value(), allowed ? ReferenceAssemblyIntakeAction.REQUESTER_ID : "denied-principal", run, "trace",
                Map.of("actor.permissions", Set.of(ReferenceAssemblyIntakeAction.PERMISSION), "resource.type", ReferenceAssemblyIntakeAction.RESOURCE_TYPE,
                        "resource.id", input.buildSessionId().value(), "resource.projectId", input.projectId().value()));
    }
    static void await(CyclicBarrier barrier) { try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError(e); } }
    static final class Fixture {
        final ReferenceAssemblyIntakeTestFixture domain = new ReferenceAssemblyIntakeTestFixture();
        final ReferenceAssemblyIntakeService service = domain.service();
        final ReferenceAssemblyIntakeAdmission admission = new ReferenceAssemblyIntakeAdmission(service, domain.clock);
        final CountingDuplicates duplicates = new CountingDuplicates(new InMemoryDuplicateActionPolicy(new ReferenceAssemblyIntakeVisibilityScopeResolver(admission)));
        final InMemoryRunStore runs = new InMemoryRunStore(); final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
        PolicyGate policy() { return new ReferenceAssemblyIntakePolicyGate(admission); }
        DefaultActionRuntime runtime() { return runtime(policy(), duplicates); }
        DefaultActionRuntime runtime(PolicyGate policy, DuplicateActionPolicy duplicate) {
            return new DefaultActionRuntime(new InMemoryActionRegistry(List.of(new ReferenceAssemblyIntakeActionExecutor(service, admission))),
                    new ReferenceAssemblyIntakeActionValidator(), policy, ApprovalGate.unsupported(), duplicate, audit::add, TraceSink.noop(), runs,
                    new ReferenceAssemblyIntakePreExecutionGuard(admission));
        }
    }
    static final class CountingDuplicates implements DuplicateActionPolicy {
        final DuplicateActionPolicy delegate; final AtomicInteger reserves = new AtomicInteger(); final AtomicInteger accepts = new AtomicInteger();
        CountingDuplicates(DuplicateActionPolicy delegate) { this.delegate = delegate; }
        public DuplicateActionDecision reserve(ActionProposal p, ExecutionContext c) { reserves.incrementAndGet(); var r = delegate.reserve(p, c); if (r.type() == DuplicateActionDecisionType.ACCEPT) accepts.incrementAndGet(); return r; }
        public void complete(ActionProposal p, ExecutionContext c, ActionExecutionResult r) { delegate.complete(p, c, r); }
        public void release(ActionProposal p, ExecutionContext c, Throwable t) { delegate.release(p, c, t); }
    }
}
