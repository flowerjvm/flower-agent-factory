package io.github.flowerjvm.factory.application.decision;

import static io.github.flowerjvm.factory.application.decision.DecisionRecordTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.duplicate.*;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DecisionRecordActionRuntimeTest {
    @ParameterizedTest
    @CsvSource({"false,APPROVE", "false,REJECT", "false,REQUEST_CHANGES", "false,CANCEL", "true,APPROVE", "true,REJECT", "true,REQUEST_CHANGES", "true,CANCEL"})
    void registeredExecutorRecordsExactDecisionForBothProductLines(boolean assembly, DecisionOutcome outcome) {
        var f = new DecisionRecordTestFixture(); var point = f.add("positive", assembly); var authority = f.authority(point);
        var input = new DecisionRecordInput(point.decisionPointId(), 0, HASH, outcome, Optional.of("explicit synthetic review"));
        var result = f.runtime().handle(proposal(input, "request", authority.principal()), context(authority, point));
        assertEquals(ActionExecutionStatus.SUCCEEDED, result.status()); assertEquals("DECISION_RECORDED", result.code());
        var stored = f.decisions.find(TENANT, new DecisionId((String) result.output().get("decisionId"))).orElseThrow();
        assertEquals(outcome, stored.decision()); assertEquals(HASH, stored.subjectHash()); assertEquals(authority.principal(), stored.decidedBy());
        assertEquals(AUTH, stored.deciderAuthoritySnapshotRef()); assertEquals(NOW, stored.createdAt()); assertTrue(stored.selectedOption().isEmpty());
        assertEquals(DecisionRecordAdmission.terminalStatus(outcome), f.pointRows.get(point.decisionPointId()).status());
        assertEquals(1, f.commits.get()); assertEquals(1, f.executions.get()); assertFalse(f.audit.isEmpty());
    }

    @Test
    void deniedPrincipalCannotReceivePreviouslyAuthorizedCachedDecisionOrProtectedIdentifiers() {
        var f = new DecisionRecordTestFixture(); var point = f.add("private", false); var authority = f.authority(point); var runtime = f.runtime();
        assertTrue(runtime.handle(proposal(input(point), "same", authority.principal()), context(authority, point)).terminalSuccess());
        var denied = new DecisionRecordAuthority(TENANT, authority.projectId(), "denied-operator", Set.of(), AUTH);
        var result = runtime.handle(proposal(input(point), "same", denied.principal()), context(denied, point));
        assertEquals(ActionExecutionStatus.DENIED, result.status()); assertTrue(result.output().isEmpty());
        assertFalse(result.toString().contains("point-private")); assertEquals(1, f.commits.get()); assertEquals(1, f.executions.get());
    }

    @Test
    void authorizedResourceBMayReuseARequestKeyWithoutReceivingResourceAResult() {
        var f = new DecisionRecordTestFixture(); var a = f.add("resource-a", false); var b = f.add("resource-b", true); var runtime = f.runtime();
        var first = runtime.handle(proposal(input(a), "shared", "operator-a"), context(f.authority(a), a));
        var next = runtime.handle(proposal(input(b), "shared", "operator-a"), context(f.authority(b), b));
        assertTrue(first.terminalSuccess()); assertTrue(next.terminalSuccess()); assertEquals(b.decisionPointId().value(), next.output().get("decisionPointId"));
        assertNotEquals(first.output().get("decisionId"), next.output().get("decisionId")); assertEquals(2, f.commits.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"outcome", "hash", "reason", "version"})
    void changedPayloadOnCompletedRequestKeyNeverGetsTheOriginalSuccess(String changed) {
        var f = new DecisionRecordTestFixture(); var point = f.add("changed", false); var authority = f.authority(point); var runtime = f.runtime();
        assertTrue(runtime.handle(proposal(input(point), "same", authority.principal()), context(authority, point)).terminalSuccess());
        var input = switch (changed) {
            case "outcome" -> copy(input(point), "outcome", DecisionOutcome.REJECT);
            case "hash" -> copy(input(point), "subjectHash", new ContentHash("b".repeat(64)));
            case "reason" -> copy(input(point), "reason", Optional.of("changed reason"));
            default -> copy(input(point), "expectedDecisionPointVersion", 1L);
        };
        var result = runtime.handle(proposal(input, "same", authority.principal()), context(authority, point));
        assertEquals(ActionExecutionStatus.DENIED, result.status()); assertTrue(result.output().isEmpty()); assertEquals(1, f.commits.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactTerminalRetrySurvivesFlowAdvancementDeadlineAndRuntimeRecreation(boolean assembly) {
        var f = new DecisionRecordTestFixture(); var point = f.add("retry", assembly); var authority = f.authority(point);
        var first = f.runtime().handle(proposal(input(point), "same", authority.principal()), context(authority, point));
        assertTrue(first.terminalSuccess());
        var session = f.sessionRows.get(point.buildSessionId());
        session = copy(session, "status", BuildSessionStatus.RUNNING); session = copy(session, "currentPhase", BuildSessionPhase.CERTIFY);
        f.sessionRows.put(session.buildSessionId(), session); f.clock.instant = NOW.plusSeconds(3600);
        var again = f.runtime().handle(proposal(input(point), "same", authority.principal()), context(authority, point));
        assertTrue(again.terminalSuccess()); assertEquals(first.output(), again.output()); assertEquals(1, f.commits.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "hash", "version", "cancel", "deadline", "schema", "permission", "approvers", "phase", "candidate"})
    void staleOrWrongAuthorityFailsBeforeAnyDecisionIsRecorded(String mutation) {
        var f = new DecisionRecordTestFixture(); var point = f.add("stale", false); var authority = f.authority(point); var input = input(point);
        switch (mutation) {
            case "project" -> authority = copy(authority, "projectId", new ProjectId("unauthorized-project"));
            case "hash" -> input = copy(input, "subjectHash", new ContentHash("b".repeat(64)));
            case "version" -> input = copy(input, "expectedDecisionPointVersion", 1L);
            case "deadline" -> f.clock.instant = point.dueAt();
            case "schema" -> f.pointRows.put(point.decisionPointId(), copy(point, "optionsSchemaId", "wrong-schema"));
            case "permission" -> f.pointRows.put(point.decisionPointId(), copy(point, "requiredPermissions", Set.of("unrelated.permission")));
            case "approvers" -> f.pointRows.put(point.decisionPointId(), copy(point, "minimumApprovers", 2));
            default -> {
                var session = f.sessionRows.get(point.buildSessionId());
                session = switch (mutation) {
                    case "cancel" -> copy(session, "cancellationRequestedAt", Optional.of(NOW));
                    case "candidate" -> copy(session, "currentCandidateHash", Optional.of(new ContentHash("b".repeat(64))));
                    default -> copy(session, "currentPhase", BuildSessionPhase.GENERATE_CANDIDATE);
                };
                f.sessionRows.put(session.buildSessionId(), session);
            }
        }
        var result = f.runtime().handle(proposal(input, "stale", authority.principal()), context(authority, point));
        assertEquals(ActionExecutionStatus.DENIED, result.status()); assertTrue(result.output().isEmpty()); assertEquals(0, f.commits.get());
    }

    @Test
    void agentPackActionRejectsNonzeroCandidateSubjectVersionSeparatelyFromDecisionPointCasVersion() {
        var f = new DecisionRecordTestFixture(); var point = f.add("subject-version", false); var authority = f.authority(point);
        f.pointRows.put(point.decisionPointId(), copy(point, "subjectVersion", 7L));
        var result = f.runtime().handle(proposal(input(point), "request", authority.principal()), context(authority, point));
        assertEquals(ActionExecutionStatus.DENIED, result.status()); assertTrue(result.output().isEmpty());
        assertEquals(0, f.executions.get()); assertEquals(0, f.commits.get());
    }

    @Test
    void cancellationBetweenGuardAndServiceReadIsRejectedByDomainSubjectAuthority() {
        var f = new DecisionRecordTestFixture(); var point = f.add("service-cancel", false); var authority = f.authority(point);
        f.beforeServiceSessionRead = () -> f.sessionRows.compute(point.buildSessionId(), (id, value) -> copy(value, "cancellationRequestedAt", Optional.of(NOW)));
        var result = f.runtime().handle(proposal(input(point), "request", authority.principal()), context(authority, point));
        assertFalse(result.terminalSuccess()); assertEquals(0, f.commits.get()); assertTrue(result.output().isEmpty());
    }

    @Test
    void cancellationAfterPolicyIsRejectedByTheLastMomentGuardBeforeExecutor() {
        var f = new DecisionRecordTestFixture(); var point = f.add("guard-cancel", false); var authority = f.authority(point);
        io.github.flowerjvm.flower.action.runtime.policy.PolicyGate policy = (proposal, definition, context) -> {
            var result = new DecisionRecordPolicyGate(f.admission()).evaluate(proposal, definition, context);
            assertTrue(result.allowedToExecuteNow());
            f.sessionRows.compute(point.buildSessionId(), (id, value) -> copy(value, "cancellationRequestedAt", Optional.of(NOW)));
            return result;
        };
        var result = f.runtime(policy, DuplicateActionPolicy.acceptAll(), new DecisionRecordPreExecutionGuard(f.admission()))
                .handle(proposal(input(point), "request", authority.principal()), context(authority, point));
        assertFalse(result.terminalSuccess()); assertEquals("DECISION_RECORD_STALE", result.code()); assertEquals(0, f.executions.get());
    }

    @Test
    void crossTenantOwnerCannotDiscoverThePointOrPreviouslyRecordedDecision() {
        var f = new DecisionRecordTestFixture(); var point = f.add("tenant-private", false); var authority = f.authority(point); var runtime = f.runtime();
        assertTrue(runtime.handle(proposal(input(point), "same", authority.principal()), context(authority, point)).terminalSuccess());
        var other = new DecisionRecordAuthority(new TenantId("other-tenant"), authority.projectId(), authority.principal(), authority.permissions(), AUTH);
        var result = runtime.handle(proposal(input(point), "same", other.principal()), context(other, point));
        assertEquals(ActionExecutionStatus.DENIED, result.status()); assertTrue(result.output().isEmpty());
        assertFalse(result.toString().contains("tenant-private")); assertEquals(1, f.executions.get());
    }

    @Test
    void cancellationAfterDomainReadLosesTheAtomicSessionFenceWithoutInsertingDecision() {
        var f = new DecisionRecordTestFixture(); var point = f.add("cas-cancel", false); var authority = f.authority(point);
        f.beforeCommit = () -> f.sessionRows.compute(point.buildSessionId(), (id, value) -> copy(value, "cancellationRequestedAt", Optional.of(NOW)));
        var result = f.runtime().handle(proposal(input(point), "request", authority.principal()), context(authority, point));
        assertFalse(result.terminalSuccess()); assertEquals("DECISION_RECORD_CONFLICT", result.code()); assertEquals(0, f.commits.get());
    }

    @Test
    void lostActionAcknowledgementAfterDomainCommitNeverOverwritesTheHumanDecisionOnRestart() {
        var f = new DecisionRecordTestFixture(); var point = f.add("uncertain", false); var authority = f.authority(point); f.failAfterCommit = true;
        var first = f.runtime().handle(proposal(input(point), "same", authority.principal()), context(authority, point));
        assertEquals(RetryDisposition.MANUAL_REVIEW, first.retryDisposition()); assertEquals(1, f.commits.get()); assertTrue(first.output().isEmpty());
        f.failAfterCommit = false;
        // Recreated in-memory runtime simulates lost transport state, not an automatic takeover of a live JDBC reservation.
        var retried = f.runtime().handle(proposal(input(point), "same", authority.principal()), context(authority, point));
        assertTrue(retried.terminalSuccess()); assertEquals(1, f.commits.get()); assertEquals(1, f.decisionRows.size());
    }

    @Test
    void fullRuntimeConcurrentSameKeyHasOneReservationExecutorAndImmutableDecision() throws Exception {
        var f = new DecisionRecordTestFixture(); var point = f.add("race", false); var authority = f.authority(point);
        var accepted = new AtomicInteger(); var gate = new CyclicBarrier(2);
        var underlying = new InMemoryDuplicateActionPolicy(new DecisionRecordVisibilityScopeResolver(f.admission()));
        DuplicateActionPolicy duplicates = new DuplicateActionPolicy() {
            public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
                if (accepted.get() == 0) await(gate);
                var result = underlying.reserve(proposal, context); if (result.type() == DuplicateActionDecisionType.ACCEPT) accepted.incrementAndGet(); return result;
            }
            public void complete(ActionProposal proposal, ExecutionContext context, ActionExecutionResult result) { underlying.complete(proposal, context, result); }
            public void release(ActionProposal proposal, ExecutionContext context, Throwable error) { underlying.release(proposal, context, error); }
        };
        var runtime = f.runtime(new DecisionRecordPolicyGate(f.admission()), duplicates, new DecisionRecordPreExecutionGuard(f.admission()));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> runtime.handle(proposal(input(point), "same", authority.principal()), context(authority, point)));
            var b = pool.submit(() -> runtime.handle(proposal(input(point), "same", authority.principal()), context(authority, point)));
            var first = a.get(10, TimeUnit.SECONDS); var second = b.get(10, TimeUnit.SECONDS);
            assertTrue(first.terminalSuccess() || second.terminalSuccess());
        }
        assertEquals(1, accepted.get()); assertEquals(1, f.executions.get()); assertEquals(1, f.commits.get());
        var stored = f.decisionRows.values().iterator().next();
        var later = runtime.handle(proposal(input(point), "same", authority.principal()), context(authority, point));
        assertTrue(later.terminalSuccess()); assertEquals(stored.decisionId().value(), later.output().get("decisionId")); assertEquals(1, f.commits.get());
    }

    @Test
    void fullRuntimeReserveRacingCompletionCannotReexecuteOrOverwriteTheRecordedDecision() throws Exception {
        var f = new DecisionRecordTestFixture(); var point = f.add("reserve-complete", false); var authority = f.authority(point);
        var boundary = new CyclicBarrier(2); var completing = new CountDownLatch(1);
        var reserves = new AtomicInteger(); var accepted = new AtomicInteger();
        var underlying = new InMemoryDuplicateActionPolicy(new DecisionRecordVisibilityScopeResolver(f.admission()));
        DuplicateActionPolicy duplicates = new DuplicateActionPolicy() {
            public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
                if (reserves.incrementAndGet() == 2) await(boundary);
                var result = underlying.reserve(proposal, context);
                if (result.type() == DuplicateActionDecisionType.ACCEPT) accepted.incrementAndGet(); return result;
            }
            public void complete(ActionProposal proposal, ExecutionContext context, ActionExecutionResult result) {
                completing.countDown(); await(boundary); underlying.complete(proposal, context, result);
            }
            public void release(ActionProposal proposal, ExecutionContext context, Throwable error) { underlying.release(proposal, context, error); }
        };
        var runtime = f.runtime(new DecisionRecordPolicyGate(f.admission()), duplicates, new DecisionRecordPreExecutionGuard(f.admission()));
        ActionExecutionResult first;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> runtime.handle(proposal(input(point), "same", authority.principal()), context(authority, point)));
            assertTrue(completing.await(10, TimeUnit.SECONDS));
            var b = pool.submit(() -> runtime.handle(proposal(input(point), "same", authority.principal()), context(authority, point)));
            first = a.get(10, TimeUnit.SECONDS); var second = b.get(10, TimeUnit.SECONDS);
            assertTrue(first.terminalSuccess()); if (second.terminalSuccess()) assertEquals(first.output(), second.output());
        }
        assertEquals(1, accepted.get()); assertEquals(1, f.executions.get()); assertEquals(1, f.commits.get());
        var later = runtime.handle(proposal(input(point), "same", authority.principal()), context(authority, point));
        assertEquals(first.output(), later.output()); assertEquals(1, f.commits.get());
    }

    @Test
    void simultaneousDifferentOutcomesWithSameKeyAreNotCachedAsEachOtherAndOnlyOneDomainCasWins() throws Exception {
        var f = new DecisionRecordTestFixture(); var point = f.add("different-outcomes", false); var authority = f.authority(point);
        var commitBoundary = new CyclicBarrier(2); f.beforeCommit = () -> await(commitBoundary);
        var runtime = f.runtime(); var approve = input(point); var reject = copy(approve, "outcome", DecisionOutcome.REJECT);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> runtime.handle(proposal(approve, "same", authority.principal()), context(authority, point)));
            var b = pool.submit(() -> runtime.handle(proposal(reject, "same", authority.principal()), context(authority, point)));
            var first = a.get(10, TimeUnit.SECONDS); var second = b.get(10, TimeUnit.SECONDS);
            assertNotEquals(first.terminalSuccess(), second.terminalSuccess());
            var winner = first.terminalSuccess() ? first : second; var loser = first.terminalSuccess() ? second : first;
            assertTrue(loser.output().isEmpty()); assertEquals("DECISION_RECORD_CONFLICT", loser.code());
            assertEquals(f.decisionRows.values().iterator().next().decision().name(), winner.output().get("outcome"));
        }
        assertEquals(2, f.executions.get()); assertEquals(1, f.commits.get()); assertEquals(1, f.decisionRows.size());
    }

    @Test
    void launcherSeparatesTrustedAuthorityAndTargetFromStrictPayloadAndUsesFreshRunIds() {
        var f = new DecisionRecordTestFixture(); var point = f.add("launcher", false); var authority = f.authority(point);
        var proposals = new ArrayList<ActionProposal>(); var contexts = new ArrayList<ExecutionContext>();
        var launcher = new ActionBackedDecisionRecordLauncher((proposal, context) -> { proposals.add(proposal); contexts.add(context); return ActionExecutionResult.succeeded(Map.of()); }, f.clock);
        launcher.record(authority, point.decisionPointId(), "logical", input(point)); launcher.record(authority, point.decisionPointId(), "logical", input(point));
        assertNotEquals(contexts.getFirst().runId(), contexts.getLast().runId()); assertNotEquals(proposals.getFirst().proposalId(), proposals.getLast().proposalId());
        assertEquals(contexts.getFirst().traceId(), contexts.getLast().traceId()); assertEquals(ActionRequestChannel.CLI, proposals.getFirst().requestChannel());
        assertEquals(ActionProposerType.USER, proposals.getFirst().proposerType()); assertEquals(authority.principal(), contexts.getFirst().userId());
        assertThrows(IllegalArgumentException.class, () -> launcher.record(authority, new DecisionPointId("different"), "logical", input(point)));
        assertEquals(2, proposals.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "deadline", "subject"})
    void postReservationMutableRejectionStillCompletesOriginalScopeAndRetryCannotReadCachedOutput(String change) {
        var f = new DecisionRecordTestFixture(); var point = f.add("owner-complete-" + change, false); var authority = f.authority(point);
        var p = proposal(input(point), "key", authority.principal()); var initial = context(authority, point);
        var underlying = new InMemoryDuplicateActionPolicy(new DecisionRecordVisibilityScopeResolver(f.admission()));
        var completes = new AtomicInteger(); var reserves = new AtomicInteger();
        DuplicateActionPolicy duplicates = new DuplicateActionPolicy() {
            public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
                reserves.incrementAndGet(); var result = underlying.reserve(proposal, context);
                if (result.type() == DuplicateActionDecisionType.ACCEPT) {
                    switch (change) {
                        case "cancel" -> f.sessionRows.compute(point.buildSessionId(), (id, value) -> copy(value, "cancellationRequestedAt", Optional.of(NOW)));
                        case "deadline" -> f.clock.instant = point.dueAt();
                        case "subject" -> f.pointRows.put(point.decisionPointId(), copy(point, "subjectHash", new ContentHash("b".repeat(64))));
                        default -> throw new AssertionError();
                    }
                }
                return result;
            }
            public void complete(ActionProposal proposal, ExecutionContext context, ActionExecutionResult result) {
                underlying.complete(proposal, context, result); completes.incrementAndGet();
            }
            public void release(ActionProposal proposal, ExecutionContext context, Throwable failure) { underlying.release(proposal, context, failure); }
        };
        var runtime = f.runtime(new DecisionRecordPolicyGate(f.admission()), duplicates, new DecisionRecordPreExecutionGuard(f.admission()));
        var rejected = runtime.handle(p, initial);
        assertFalse(rejected.terminalSuccess()); assertTrue(rejected.output().isEmpty()); assertEquals(1, completes.get());
        assertEquals(DuplicateActionDecisionType.RETURN_EXISTING, underlying.reserve(p, context(authority, point)).type());
        var retry = runtime.handle(p, context(authority, point));
        assertEquals(ActionExecutionStatus.DENIED, retry.status()); assertTrue(retry.output().isEmpty());
        assertEquals(1, reserves.get()); assertEquals(0, f.executions.get()); assertEquals(0, f.commits.get());
    }

    @Test
    void postReservationCancellationAndUnexpectedGuardFailureReleaseOriginalOwnerWithoutExposingItsResult() {
        var f = new DecisionRecordTestFixture(); var point = f.add("owner-release", false); var authority = f.authority(point);
        var p = proposal(input(point), "key", authority.principal()); var underlying = new InMemoryDuplicateActionPolicy(new DecisionRecordVisibilityScopeResolver(f.admission()));
        var releases = new AtomicInteger(); var reserves = new AtomicInteger();
        DuplicateActionPolicy duplicates = new DuplicateActionPolicy() {
            public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
                reserves.incrementAndGet(); var result = underlying.reserve(proposal, context);
                f.sessionRows.compute(point.buildSessionId(), (id, value) -> copy(value, "cancellationRequestedAt", Optional.of(NOW)));
                return result;
            }
            public void complete(ActionProposal proposal, ExecutionContext context, ActionExecutionResult result) { underlying.complete(proposal, context, result); }
            public void release(ActionProposal proposal, ExecutionContext context, Throwable failure) {
                underlying.release(proposal, context, failure); releases.incrementAndGet();
            }
        };
        var runtime = f.runtime(new DecisionRecordPolicyGate(f.admission()), duplicates, (p2, d, c, policy) -> { throw new IllegalStateException("guard transport failed"); });
        assertFalse(runtime.handle(p, context(authority, point)).terminalSuccess()); assertEquals(1, releases.get());
        var probeOwner = context(authority, point);
        assertEquals(DuplicateActionDecisionType.ACCEPT, underlying.reserve(p, probeOwner).type());
        underlying.release(p, probeOwner, new IllegalStateException("test observation complete"));
        var retry = runtime.handle(p, context(authority, point));
        assertEquals(ActionExecutionStatus.DENIED, retry.status()); assertTrue(retry.output().isEmpty());
        assertEquals(1, reserves.get()); assertEquals(0, f.executions.get()); assertEquals(0, f.commits.get());
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception error) { throw new AssertionError(error); }
    }
}
