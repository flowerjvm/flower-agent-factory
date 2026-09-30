package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcWorkerTransportTransactionsTest {
    private static final Instant NOW = PersistenceFixtures.NOW;

    @Test
    void twoDispatchStartConnectionsSerializeOneExternalEffectClaim() throws Exception {
        Fixture fixture = fixture("dispatch-start-race");
        var first = new JdbcWorkerDispatchStartTransaction(fixture.dataSource());
        var second = new JdbcWorkerDispatchStartTransaction(fixture.dataSource());

        List<Boolean> outcomes = race(
                () -> first.claimNext(
                                NOW.plusSeconds(1), Duration.ofMinutes(1), "dispatch-start-a")
                        .isPresent(),
                () -> second.claimNext(
                                NOW.plusSeconds(1), Duration.ofMinutes(1), "dispatch-start-b")
                        .isPresent());

        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        WorkerRunRecord storedRun = fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow();
        DispatchOutbox stored = fixture.outbox().find(
                fixture.worker().tenantId(), storedRun.dispatchOutboxId().orElseThrow())
                .orElseThrow();
        assertEquals(DispatchOutboxStatus.DISPATCHING, stored.status());
        assertTrue(stored.claimToken().filter(value -> value.equals("dispatch-start-a")
                || value.equals("dispatch-start-b")).isPresent());
    }

    @Test
    void dispatchStartAtomicallyStopsWhenCancellationWasPersistedFirst() {
        Fixture fixture = fixture("dispatch-start-cancelled");
        BuildSession cancelling = fixture.buildSession().requestCancellation(NOW.plusSeconds(1));
        assertTrue(fixture.sessions().compareAndSet(fixture.buildSession(), cancelling));
        assertEquals(cancelling, fixture.sessions().findCancelling(NOW.plusSeconds(1), 10).getFirst());

        DispatchOutbox rejected = new JdbcWorkerDispatchStartTransaction(fixture.dataSource())
                .claimNext(NOW.plusSeconds(2), Duration.ofMinutes(1), "dispatch-cancelled")
                .orElseThrow();

        assertEquals(DispatchOutboxStatus.MANUAL_REVIEW, rejected.status());
        assertEquals(Optional.of("WORKER_DISPATCH_CANCELLED_BEFORE_SUBMIT"), rejected.lastCode());
        assertEquals(WorkerRunStatus.DISPATCHING, fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow().status());
        assertEquals(1, fixture.workerRuns()
                .findCancellationRecoveryCandidates(NOW.plusSeconds(2), 10)
                .size());
    }

    @Test
    void dispatchStartTreatsExactWorkOrderDeadlineAsElapsed() {
        Fixture fixture = fixture("dispatch-start-deadline");

        DispatchOutbox rejected = new JdbcWorkerDispatchStartTransaction(fixture.dataSource())
                .claimNext(NOW.plusSeconds(1800), Duration.ofMinutes(1), "dispatch-deadline")
                .orElseThrow();

        assertEquals(DispatchOutboxStatus.MANUAL_REVIEW, rejected.status());
        assertEquals(Optional.of("WORKER_DISPATCH_DEADLINE_EXCEEDED"), rejected.lastCode());
    }

    @Test
    void preParkRunningActionIsNeverClaimedAndGraceExpiryAtomicallyEntersManualReview() {
        Fixture fixture = preParkFixture("dispatch-pre-park-orphan");
        Instant actionUpdatedAt = fixture.actionRun().updatedAt();
        var starts = new JdbcWorkerDispatchStartTransaction(fixture.dataSource());
        var orphans = new JdbcWorkerDispatchPreParkOrphanTransaction(fixture.dataSource());

        assertTrue(starts.claimNext(
                actionUpdatedAt.plusSeconds(1), Duration.ofMinutes(1), "must-not-claim").isEmpty());
        assertFalse(orphans.reconcileNext(actionUpdatedAt.plusSeconds(29), Duration.ofSeconds(30)));
        assertEquals(DispatchOutboxStatus.PENDING, fixture.outbox().find(
                fixture.worker().tenantId(), dispatchOutboxId(fixture)).orElseThrow().status());

        assertTrue(orphans.reconcileNext(actionUpdatedAt.plusSeconds(30), Duration.ofSeconds(30)));
        DispatchOutbox reviewed = fixture.outbox().find(
                fixture.worker().tenantId(), dispatchOutboxId(fixture)).orElseThrow();
        assertEquals(DispatchOutboxStatus.MANUAL_REVIEW, reviewed.status());
        assertEquals(Optional.of("WORKER_DISPATCH_ACTION_PARK_ORPHANED"), reviewed.lastCode());
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, fixture.sessions().find(
                fixture.buildSession().tenantId(), fixture.buildSession().buildSessionId())
                .orElseThrow().status());
        assertEquals(WorkerRunStatus.DISPATCHING, fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow().status());
        assertEquals(ActionRunStatus.RUNNING,
                new JdbcRunStore(fixture.dataSource(), new ObjectMapper())
                        .find(fixture.actionRun().runId()).orElseThrow().status());
    }

    @Test
    void cancellingPreParkOrphanConvergesWithoutClaimingAnExternalEffect() {
        Fixture fixture = preParkFixture("dispatch-pre-park-cancel-orphan");
        Instant actionUpdatedAt = fixture.actionRun().updatedAt();
        BuildSession cancelling = fixture.buildSession().requestCancellation(actionUpdatedAt.plusSeconds(2));
        assertTrue(fixture.sessions().compareAndSet(fixture.buildSession(), cancelling));

        assertTrue(new JdbcWorkerDispatchStartTransaction(fixture.dataSource())
                .claimNext(actionUpdatedAt.plusSeconds(3), Duration.ofMinutes(1), "cancel-no-claim")
                .isEmpty());
        assertTrue(new JdbcWorkerDispatchPreParkOrphanTransaction(fixture.dataSource())
                .reconcileNext(actionUpdatedAt.plusSeconds(30), Duration.ofSeconds(30)));

        DispatchOutbox reviewed = fixture.outbox().find(
                fixture.worker().tenantId(), dispatchOutboxId(fixture)).orElseThrow();
        assertEquals(DispatchOutboxStatus.MANUAL_REVIEW, reviewed.status());
        assertEquals(Optional.of("WORKER_DISPATCH_ACTION_PARK_CANCEL_ORPHANED"), reviewed.lastCode());
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, fixture.sessions().find(
                fixture.buildSession().tenantId(), fixture.buildSession().buildSessionId())
                .orElseThrow().status());
        assertEquals(WorkerRunStatus.DISPATCHING, fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow().status());
        assertEquals(ActionRunStatus.RUNNING,
                new JdbcRunStore(fixture.dataSource(), new ObjectMapper())
                        .find(fixture.actionRun().runId()).orElseThrow().status());
    }

    @Test
    void twoAcceptanceConnectionsProduceOneCompositeCasWinner() throws Exception {
        Fixture fixture = fixture("acceptance_race");
        DispatchOutbox claimed = claim(fixture);
        var transactionA = new JdbcWorkerDispatchAcceptanceTransaction(fixture.dataSource());
        var transactionB = new JdbcWorkerDispatchAcceptanceTransaction(fixture.dataSource());

        List<Boolean> outcomes = race(
                () -> transactionA.accept(
                        fixture.worker().tenantId(), fixture.worker().workerRunId(), claimed.outboxId(),
                        claimed.operationId(), claimed.claimToken().orElseThrow(), "external-a",
                        NOW.plusSeconds(2), NOW.plusSeconds(2)),
                () -> transactionB.accept(
                        fixture.worker().tenantId(), fixture.worker().workerRunId(), claimed.outboxId(),
                        claimed.operationId(), claimed.claimToken().orElseThrow(), "external-b",
                        NOW.plusSeconds(2), NOW.plusSeconds(2)));

        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        WorkerRunRecord stored = fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow();
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, stored.status());
        assertTrue(stored.externalSessionRef().filter(value -> value.equals("external-a")
                || value.equals("external-b")).isPresent());
        assertEquals(DispatchOutboxStatus.DISPATCHED, fixture.outbox().find(
                fixture.worker().tenantId(), claimed.outboxId()).orElseThrow().status());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL,
                new JdbcRunStore(fixture.dataSource(), new ObjectMapper())
                        .find(fixture.actionRun().runId()).orElseThrow().status());
    }

    @Test
    void terminalActionOwnerPreventsLateDispatchAcceptance() {
        Fixture fixture = fixture("acceptance_action_cancelled");
        DispatchOutbox claimed = claim(fixture);
        terminalizeAction(fixture);

        boolean accepted = new JdbcWorkerDispatchAcceptanceTransaction(fixture.dataSource()).accept(
                fixture.worker().tenantId(), fixture.worker().workerRunId(), claimed.outboxId(),
                claimed.operationId(), claimed.claimToken().orElseThrow(), "external-late",
                NOW.plusSeconds(2), NOW.plusSeconds(2));

        assertFalse(accepted);
        assertEquals(WorkerRunStatus.DISPATCHING, fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow().status());
        assertEquals(DispatchOutboxStatus.DISPATCHING, fixture.outbox().find(
                fixture.worker().tenantId(), claimed.outboxId()).orElseThrow().status());
    }

    @Test
    void cancellationPreparationRequiresExactWaitingActionAndCommitsIntentAtomically() {
        Fixture fixture = fixture("cancel_prepare");
        DispatchOutbox claimed = claim(fixture);
        assertTrue(new JdbcWorkerDispatchAcceptanceTransaction(fixture.dataSource()).accept(
                fixture.worker().tenantId(), fixture.worker().workerRunId(), claimed.outboxId(),
                claimed.operationId(), claimed.claimToken().orElseThrow(), "external-cancel",
                NOW.plusSeconds(2), NOW.plusSeconds(2)));
        WorkerRunRecord waiting = fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow();
        WorkerRunRecord cancelRequested = waiting.requestCancellation(NOW.plusSeconds(3));
        DispatchOutbox cancelOutbox = pendingCancel(fixture, waiting, "cancel-prepare");

        assertTrue(new JdbcWorkerCancellationTransaction(fixture.dataSource()).prepare(
                fixture.workOrder(), waiting, cancelRequested, cancelOutbox));
        assertEquals(cancelRequested, fixture.workerRuns().find(
                waiting.tenantId(), waiting.workerRunId()).orElseThrow());
        assertEquals(cancelOutbox, fixture.outbox().find(waiting.tenantId(), cancelOutbox.outboxId()).orElseThrow());
    }

    @Test
    void lateCancellationAfterActionTerminalHasNoDomainOrOutboxEffect() {
        Fixture fixture = fixture("cancel_action_terminal");
        DispatchOutbox claimed = claim(fixture);
        assertTrue(new JdbcWorkerDispatchAcceptanceTransaction(fixture.dataSource()).accept(
                fixture.worker().tenantId(), fixture.worker().workerRunId(), claimed.outboxId(),
                claimed.operationId(), claimed.claimToken().orElseThrow(), "external-terminal",
                NOW.plusSeconds(2), NOW.plusSeconds(2)));
        WorkerRunRecord waiting = fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow();
        terminalizeAction(fixture);
        DispatchOutbox cancelOutbox = pendingCancel(fixture, waiting, "cancel-terminal");

        assertFalse(new JdbcWorkerCancellationTransaction(fixture.dataSource()).prepare(
                fixture.workOrder(), waiting, waiting.requestCancellation(NOW.plusSeconds(3)), cancelOutbox));
        assertEquals(waiting, fixture.workerRuns().find(waiting.tenantId(), waiting.workerRunId()).orElseThrow());
        assertTrue(fixture.outbox().find(waiting.tenantId(), cancelOutbox.outboxId()).isEmpty());
    }

    @Test
    void cancellationCanBeStagedBeforePendingDispatchIsSubmitted() {
        Fixture fixture = fixture("cancel_before_submit");
        WorkerRunRecord dispatching = fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow();
        DispatchOutbox cancelOutbox = pendingCancel(fixture, dispatching, "cancel-before-submit");

        assertTrue(new JdbcWorkerCancellationTransaction(fixture.dataSource()).prepare(
                fixture.workOrder(),
                dispatching,
                dispatching.requestCancellation(NOW.plusSeconds(2)),
                cancelOutbox));

        assertEquals(WorkerRunStatus.CANCEL_REQUESTED, fixture.workerRuns().find(
                dispatching.tenantId(), dispatching.workerRunId()).orElseThrow().status());
        assertEquals(DispatchOutboxStatus.PENDING, fixture.outbox().find(
                dispatching.tenantId(), dispatching.dispatchOutboxId().orElseThrow())
                .orElseThrow().status());
        assertEquals(cancelOutbox, fixture.outbox().find(
                dispatching.tenantId(), cancelOutbox.outboxId()).orElseThrow());
    }

    @Test
    void lateInFlightAcceptancePreservesCancellationAndRecordsRemoteEffect() {
        Fixture fixture = fixture("late-acceptance-after-cancel");
        DispatchOutbox claimedDispatch = claim(fixture);
        WorkerRunRecord dispatching = fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow();
        BuildSession cancelling = fixture.buildSession().requestCancellation(NOW.plusSeconds(3));
        assertTrue(fixture.sessions().compareAndSet(fixture.buildSession(), cancelling));
        DispatchOutbox cancelOutbox = pendingCancel(fixture, dispatching, "late-acceptance");
        assertTrue(new JdbcWorkerCancellationTransaction(fixture.dataSource()).prepare(
                fixture.workOrder(), dispatching,
                dispatching.requestCancellation(NOW.plusSeconds(3)), cancelOutbox));
        terminalizeAction(fixture);

        assertTrue(new JdbcWorkerDispatchAcceptanceTransaction(fixture.dataSource()).accept(
                dispatching.tenantId(), dispatching.workerRunId(), claimedDispatch.outboxId(),
                dispatching.operationId(), claimedDispatch.claimToken().orElseThrow(),
                "external-late-effect", NOW.plusSeconds(2), NOW.plusSeconds(4)));

        WorkerRunRecord stored = fixture.workerRuns().find(
                dispatching.tenantId(), dispatching.workerRunId()).orElseThrow();
        assertEquals(WorkerRunStatus.CANCEL_REQUESTED, stored.status());
        assertEquals(DispatchOutboxStatus.DISPATCHED, fixture.outbox().find(
                dispatching.tenantId(), claimedDispatch.outboxId()).orElseThrow().status());
        assertEquals(DispatchOutboxStatus.PENDING, fixture.outbox().find(
                dispatching.tenantId(), cancelOutbox.outboxId()).orElseThrow().status());
    }

    @Test
    void twoCancellationOutcomeConnectionsProduceOneThreeLedgerCasWinner() throws Exception {
        CancelFixture cancellation = claimedCancellation(fixture("cancel_outcome_race"), "outcome-race");
        var first = new JdbcWorkerCancellationOutcomeTransaction(cancellation.fixture().dataSource());
        var second = new JdbcWorkerCancellationOutcomeTransaction(cancellation.fixture().dataSource());

        List<Boolean> outcomes = race(
                () -> first.confirm(
                        cancellation.run().tenantId(), cancellation.run().workerRunId(),
                        cancellation.outbox().outboxId(), cancellation.run().operationId(),
                        cancellation.outbox().claimToken().orElseThrow(),
                        "WORKER_CANCEL_CONFIRMED", NOW.plusSeconds(5)),
                () -> second.confirm(
                        cancellation.run().tenantId(), cancellation.run().workerRunId(),
                        cancellation.outbox().outboxId(), cancellation.run().operationId(),
                        cancellation.outbox().claimToken().orElseThrow(),
                        "WORKER_CANCEL_CONFIRMED", NOW.plusSeconds(5)));

        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        Fixture fixture = cancellation.fixture();
        assertEquals(WorkerRunStatus.CANCELLED, fixture.workerRuns().find(
                cancellation.run().tenantId(), cancellation.run().workerRunId()).orElseThrow().status());
        assertEquals(DispatchOutboxStatus.CONFIRMED, fixture.outbox().find(
                cancellation.run().tenantId(), cancellation.outbox().outboxId()).orElseThrow().status());
        assertEquals(BuildSessionStatus.CANCELLED, fixture.sessions().find(
                fixture.buildSession().tenantId(), fixture.buildSession().buildSessionId())
                .orElseThrow().status());
        assertTrue(fixture.workerRuns().findActiveByBuildSession(
                fixture.buildSession().tenantId(), fixture.buildSession().buildSessionId()).isEmpty());
    }

    @Test
    void cancellationOutcomeRejectsForgedClaimAndCanAtomicallyEnterManualReview() {
        CancelFixture cancellation = claimedCancellation(fixture("cancel_outcome_manual"), "outcome-manual");
        Fixture fixture = cancellation.fixture();
        var outcomes = new JdbcWorkerCancellationOutcomeTransaction(fixture.dataSource());

        assertFalse(outcomes.manualReview(
                cancellation.run().tenantId(), cancellation.run().workerRunId(),
                cancellation.outbox().outboxId(), cancellation.run().operationId(),
                "forged-claim", "WORKER_CANCEL_UNCONFIRMED", NOW.plusSeconds(5)));
        assertEquals(WorkerRunStatus.CANCEL_REQUESTED, fixture.workerRuns().find(
                cancellation.run().tenantId(), cancellation.run().workerRunId()).orElseThrow().status());
        assertEquals(BuildSessionStatus.CANCELLING, fixture.sessions().find(
                fixture.buildSession().tenantId(), fixture.buildSession().buildSessionId())
                .orElseThrow().status());

        assertTrue(outcomes.manualReview(
                cancellation.run().tenantId(), cancellation.run().workerRunId(),
                cancellation.outbox().outboxId(), cancellation.run().operationId(),
                cancellation.outbox().claimToken().orElseThrow(),
                "WORKER_CANCEL_UNCONFIRMED", NOW.plusSeconds(5)));
        assertEquals(WorkerRunStatus.MANUAL_REVIEW, fixture.workerRuns().find(
                cancellation.run().tenantId(), cancellation.run().workerRunId()).orElseThrow().status());
        assertEquals(DispatchOutboxStatus.MANUAL_REVIEW, fixture.outbox().find(
                cancellation.run().tenantId(), cancellation.outbox().outboxId()).orElseThrow().status());
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, fixture.sessions().find(
                fixture.buildSession().tenantId(), fixture.buildSession().buildSessionId())
                .orElseThrow().status());
    }

    @Test
    void canonicalCompletionSupersedesClaimedCancellationWithoutOverwritingWorkerTruth() {
        CancelFixture cancellation = claimedCancellationBeforeActionTerminal(
                fixture("cancel_completion_winner"), "completion-winner");
        Fixture fixture = cancellation.fixture();
        terminalizeActionSucceeded(fixture);
        WorkerRunRecord completionWinner = cancellation.run().complete(
                WorkerRunStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                "WORKER_PROVIDER_FAILED",
                "Worker completion won the Action first-terminal race",
                io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition.MANUAL_REVIEW,
                NOW.plusSeconds(5));
        assertTrue(fixture.workerRuns().compareAndSet(cancellation.run(), completionWinner));

        var outcomes = new JdbcWorkerCancellationOutcomeTransaction(fixture.dataSource());
        assertTrue(outcomes.supersedeAfterTerminalCompletion(
                completionWinner.tenantId(),
                completionWinner.workerRunId(),
                cancellation.outbox().outboxId(),
                completionWinner.operationId(),
                cancellation.outbox().claimToken().orElseThrow(),
                "WORKER_CANCEL_SUPERSEDED_BY_COMPLETION",
                NOW.plusSeconds(6)));

        assertEquals(completionWinner, fixture.workerRuns().find(
                completionWinner.tenantId(), completionWinner.workerRunId()).orElseThrow());
        assertEquals(DispatchOutboxStatus.SUPERSEDED, fixture.outbox().find(
                completionWinner.tenantId(), cancellation.outbox().outboxId()).orElseThrow().status());
        assertEquals(BuildSessionStatus.CANCELLED, fixture.sessions().find(
                fixture.buildSession().tenantId(), fixture.buildSession().buildSessionId())
                .orElseThrow().status());
    }

    @Test
    void cancelNotFoundConfirmsOnlyWhileDispatchIsProvablyUnsubmitted() {
        Fixture fixture = fixture("cancel-absent-proven-no-dispatch");
        WorkerRunRecord dispatching = fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow();
        BuildSession cancelling = fixture.buildSession().requestCancellation(NOW.plusSeconds(2));
        assertTrue(fixture.sessions().compareAndSet(fixture.buildSession(), cancelling));
        DispatchOutbox pendingCancel = pendingCancel(fixture, dispatching, "absent-proven");
        assertTrue(new JdbcWorkerCancellationTransaction(fixture.dataSource()).prepare(
                fixture.workOrder(), dispatching,
                dispatching.requestCancellation(NOW.plusSeconds(3)), pendingCancel));
        DispatchOutbox claimedCancel = fixture.outbox().claimNextForSubmission(
                        WorkerOutboxOperations.CANCEL,
                        NOW.plusSeconds(4), Duration.ofMinutes(1), "cancel-absent-claim")
                .orElseThrow();

        assertTrue(new JdbcWorkerCancellationOutcomeTransaction(fixture.dataSource())
                .confirmAbsentAfterProvenNoDispatch(
                        dispatching.tenantId(), dispatching.workerRunId(), claimedCancel.outboxId(),
                        dispatching.dispatchOutboxId().orElseThrow(), dispatching.operationId(),
                        claimedCancel.claimToken().orElseThrow(), "WORKER_CANCEL_OPERATION_ABSENT",
                        NOW.plusSeconds(5)));

        assertEquals(WorkerRunStatus.CANCELLED, fixture.workerRuns().find(
                dispatching.tenantId(), dispatching.workerRunId()).orElseThrow().status());
        assertEquals(DispatchOutboxStatus.PENDING, fixture.outbox().find(
                dispatching.tenantId(), dispatching.dispatchOutboxId().orElseThrow())
                .orElseThrow().status());
        assertEquals(DispatchOutboxStatus.CONFIRMED, fixture.outbox().find(
                dispatching.tenantId(), claimedCancel.outboxId()).orElseThrow().status());
        assertEquals(BuildSessionStatus.CANCELLED, fixture.sessions().find(
                fixture.buildSession().tenantId(), fixture.buildSession().buildSessionId())
                .orElseThrow().status());
    }

    @Test
    void cancelNotFoundCannotConfirmAfterDispatchCrossesItsClaimBoundary() {
        Fixture fixture = fixture("cancel-absent-claimed-dispatch");
        DispatchOutbox claimedDispatch = claim(fixture);
        WorkerRunRecord dispatching = fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow();
        BuildSession cancelling = fixture.buildSession().requestCancellation(NOW.plusSeconds(2));
        assertTrue(fixture.sessions().compareAndSet(fixture.buildSession(), cancelling));
        DispatchOutbox pendingCancel = pendingCancel(fixture, dispatching, "absent-uncertain");
        assertTrue(new JdbcWorkerCancellationTransaction(fixture.dataSource()).prepare(
                fixture.workOrder(), dispatching,
                dispatching.requestCancellation(NOW.plusSeconds(3)), pendingCancel));
        DispatchOutbox claimedCancel = fixture.outbox().claimNextForSubmission(
                        WorkerOutboxOperations.CANCEL,
                        NOW.plusSeconds(4), Duration.ofMinutes(1), "cancel-uncertain-claim")
                .orElseThrow();

        assertFalse(new JdbcWorkerCancellationOutcomeTransaction(fixture.dataSource())
                .confirmAbsentAfterProvenNoDispatch(
                        dispatching.tenantId(), dispatching.workerRunId(), claimedCancel.outboxId(),
                        claimedDispatch.outboxId(), dispatching.operationId(),
                        claimedCancel.claimToken().orElseThrow(), "WORKER_CANCEL_OPERATION_ABSENT",
                        NOW.plusSeconds(5)));

        assertEquals(WorkerRunStatus.CANCEL_REQUESTED, fixture.workerRuns().find(
                dispatching.tenantId(), dispatching.workerRunId()).orElseThrow().status());
        assertEquals(DispatchOutboxStatus.DISPATCHING, fixture.outbox().find(
                dispatching.tenantId(), claimedDispatch.outboxId()).orElseThrow().status());
        assertEquals(DispatchOutboxStatus.DISPATCHING, fixture.outbox().find(
                dispatching.tenantId(), claimedCancel.outboxId()).orElseThrow().status());
        assertEquals(BuildSessionStatus.CANCELLING, fixture.sessions().find(
                fixture.buildSession().tenantId(), fixture.buildSession().buildSessionId())
                .orElseThrow().status());
    }

    @Test
    void activeReconciliationScanIsStatusFilteredCutoffBoundedAndDeterministic() {
        Fixture waiting = fixture("scan-waiting");
        DispatchOutbox claimed = claim(waiting);
        assertTrue(new JdbcWorkerDispatchAcceptanceTransaction(waiting.dataSource()).accept(
                waiting.worker().tenantId(), waiting.worker().workerRunId(), claimed.outboxId(),
                claimed.operationId(), claimed.claimToken().orElseThrow(), "external-scan",
                NOW.plusSeconds(2), NOW.plusSeconds(2)));

        List<WorkerRunRecord> one = waiting.workerRuns().findActiveForReconciliation(NOW.plusSeconds(2), 1);
        assertEquals(1, one.size());
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, one.getFirst().status());
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, waiting.workerRuns()
                .findWaitingExternalForStatusPoll(NOW.plusSeconds(2), 1)
                .getFirst()
                .status());
        assertTrue(waiting.workerRuns().findActiveForReconciliation(NOW.plusSeconds(1), 10).isEmpty());
    }

    @Test
    void activeReconciliationScanIncludesPreAcceptanceDispatchForCancelIntentRecovery() {
        Fixture dispatching = fixture("scan-dispatching-cancel-recovery");

        List<WorkerRunRecord> active = dispatching.workerRuns()
                .findActiveForReconciliation(NOW.plusSeconds(1), 10);

        assertEquals(1, active.size());
        assertEquals(WorkerRunStatus.DISPATCHING, active.getFirst().status());
        assertEquals(dispatching.worker().workerRunId(), active.getFirst().workerRunId());
        assertTrue(dispatching.workerRuns()
                .findWaitingExternalForStatusPoll(NOW.plusSeconds(1), 10)
                .isEmpty());
    }

    private static Fixture fixture(String suffix) {
        return fixture(suffix, false);
    }

    private static Fixture preParkFixture(String suffix) {
        return fixture(suffix, true);
    }

    private static Fixture fixture(String suffix, boolean prePark) {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2("worker_transport_" + suffix);
        FactoryDatabaseMigrations.migrate(dataSource);
        var buildSession = PersistenceFixtures.buildSession(
                suffix, "request-" + suffix,
                io.github.flowerjvm.factory.application.build.BuildSessionPhase.GENERATE_CANDIDATE);
        WorkOrder workOrder = PersistenceFixtures.workOrder(buildSession, suffix);
        WorkerRunId workerRunId = new WorkerRunId("worker-" + suffix);
        WorkerRunRecord worker = PersistenceFixtures.workerRun(
                buildSession,
                workOrder,
                suffix,
                1,
                WorkerDispatchOperationIds.derive(
                        buildSession.tenantId(),
                        workOrder.workOrderId(),
                        workerRunId,
                        1,
                        "coding-binding"));
        var sessions = new JdbcBuildSessionRepository(dataSource);
        var orders = new JdbcWorkOrderRepository(dataSource);
        var workerRuns = new JdbcWorkerRunRepository(dataSource);
        var outbox = new JdbcDispatchOutboxRepository(dataSource);
        sessions.create(buildSession);
        orders.create(workOrder);
        workerRuns.create(worker);
        String actionRunId = "action-" + suffix;
        ActionRun owner = prePark
                ? ActionRunFixtures.createRunningWorkerDispatchOwner(dataSource, worker, actionRunId)
                : ActionRunFixtures.createWaitingWorkerDispatchOwner(dataSource, worker, actionRunId);
        DispatchOutbox fixtureOutbox = PersistenceFixtures.outbox(worker, suffix);
        DispatchOutbox dispatchOutbox = new DispatchOutbox(
                fixtureOutbox.outboxId(), fixtureOutbox.tenantId(), WorkerOutboxOperations.DISPATCH,
                fixtureOutbox.aggregateType(), fixtureOutbox.aggregateId(), fixtureOutbox.operationId(),
                workOrder.inputArtifactManifestRef(), fixtureOutbox.status(), fixtureOutbox.availableAt(),
                fixtureOutbox.attemptCount(), fixtureOutbox.lastCode(), fixtureOutbox.version(),
                fixtureOutbox.createdAt(), fixtureOutbox.updatedAt());
        WorkerRunRecord dispatching = worker.startDispatch(
                dispatchOutbox.outboxId(), actionRunId,
                WorkerDispatchOperationIds.hashAttemptToken(owner.attemptToken()), NOW.plusSeconds(1));
        assertTrue(new JdbcWorkerDispatchTransaction(
                        dataSource, new ObjectMapper(), Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC))
                .prepare(workOrder, worker, dispatching, dispatchOutbox));
        return new Fixture(
                dataSource, buildSession, workOrder, worker, owner, sessions, workerRuns, outbox);
    }

    private static DispatchOutboxId dispatchOutboxId(Fixture fixture) {
        return fixture.workerRuns().find(fixture.worker().tenantId(), fixture.worker().workerRunId())
                .orElseThrow()
                .dispatchOutboxId()
                .orElseThrow();
    }

    private static DispatchOutbox claim(Fixture fixture) {
        return fixture.outbox().claimNextForSubmission(
                        WorkerOutboxOperations.DISPATCH,
                        NOW.plusSeconds(1),
                        Duration.ofMinutes(1),
                        "claim-" + fixture.worker().workerRunId().value())
                .orElseThrow();
    }

    private static DispatchOutbox pendingCancel(
            Fixture fixture, WorkerRunRecord waiting, String suffix) {
        return new DispatchOutbox(
                new DispatchOutboxId("cancel-outbox-" + suffix), waiting.tenantId(),
                WorkerOutboxOperations.CANCEL, "WORKER_RUN", waiting.workerRunId().value(),
                waiting.operationId(), fixture.workOrder().inputArtifactManifestRef(),
                DispatchOutboxStatus.PENDING, NOW.plusSeconds(3), 0, Optional.empty(), 0,
                NOW.plusSeconds(3), NOW.plusSeconds(3));
    }

    private static CancelFixture claimedCancellation(Fixture fixture, String suffix) {
        CancelFixture cancellation = claimedCancellationBeforeActionTerminal(fixture, suffix);
        terminalizeAction(fixture);
        return cancellation;
    }

    private static CancelFixture claimedCancellationBeforeActionTerminal(
            Fixture fixture, String suffix) {
        DispatchOutbox claimedDispatch = claim(fixture);
        assertTrue(new JdbcWorkerDispatchAcceptanceTransaction(fixture.dataSource()).accept(
                fixture.worker().tenantId(), fixture.worker().workerRunId(), claimedDispatch.outboxId(),
                claimedDispatch.operationId(), claimedDispatch.claimToken().orElseThrow(),
                "external-" + suffix, NOW.plusSeconds(2), NOW.plusSeconds(2)));
        WorkerRunRecord waiting = fixture.workerRuns().find(
                fixture.worker().tenantId(), fixture.worker().workerRunId()).orElseThrow();
        BuildSession cancelling = fixture.buildSession().requestCancellation(NOW.plusSeconds(3));
        assertTrue(fixture.sessions().compareAndSet(fixture.buildSession(), cancelling));
        DispatchOutbox pending = pendingCancel(fixture, waiting, "cancel-" + suffix);
        assertTrue(new JdbcWorkerCancellationTransaction(fixture.dataSource()).prepare(
                fixture.workOrder(), waiting, waiting.requestCancellation(NOW.plusSeconds(3)), pending));
        WorkerRunRecord cancelRequested = fixture.workerRuns().find(
                waiting.tenantId(), waiting.workerRunId()).orElseThrow();
        DispatchOutbox claimedCancel = fixture.outbox().claimNextForSubmission(
                        WorkerOutboxOperations.CANCEL,
                        NOW.plusSeconds(4),
                        Duration.ofMinutes(1),
                        "cancel-claim-" + suffix)
                .orElseThrow();
        return new CancelFixture(fixture, cancelRequested, claimedCancel);
    }

    private static void terminalizeAction(Fixture fixture) {
        JdbcRunStore store = new JdbcRunStore(fixture.dataSource(), new ObjectMapper());
        ActionRun waiting = store.find(fixture.actionRun().runId()).orElseThrow();
        ActionRun cancelled = waiting.toBuilder()
                .version(waiting.version() + 1)
                .status(ActionRunStatus.CANCELLED)
                .currentStage("TERMINAL")
                .result(ActionExecutionResult.cancelled("ACTION_CANCELLED"))
                .updatedAt(waiting.updatedAt().plusMillis(1))
                .build();
        assertTrue(store.compareAndSet(waiting, cancelled));
    }

    private static void terminalizeActionSucceeded(Fixture fixture) {
        JdbcRunStore store = new JdbcRunStore(fixture.dataSource(), new ObjectMapper());
        ActionRun waiting = store.find(fixture.actionRun().runId()).orElseThrow();
        ActionRun succeeded = waiting.toBuilder()
                .version(waiting.version() + 1)
                .status(ActionRunStatus.SUCCEEDED)
                .currentStage("TERMINAL")
                .result(ActionExecutionResult.succeeded(Map.of("winner", "completion")))
                .updatedAt(waiting.updatedAt().plusMillis(1))
                .build();
        assertTrue(store.compareAndSet(waiting, succeeded));
    }

    private static List<Boolean> race(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(3);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> a = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return first.call();
            });
            Future<Boolean> b = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.call();
            });
            barrier.await(5, TimeUnit.SECONDS);
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private record Fixture(
            JdbcDataSource dataSource,
            BuildSession buildSession,
            WorkOrder workOrder,
            WorkerRunRecord worker,
            ActionRun actionRun,
            JdbcBuildSessionRepository sessions,
            JdbcWorkerRunRepository workerRuns,
            JdbcDispatchOutboxRepository outbox) {}

    private record CancelFixture(
            Fixture fixture, WorkerRunRecord run, DispatchOutbox outbox) {}
}
