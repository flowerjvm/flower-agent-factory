package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionDecision;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Rechecks mutable ledger state immediately before creating the dispatch intent. */
public final class WorkerDispatchPreExecutionGuard implements PreExecutionGuard {
    private final WorkOrderRepository workOrders;
    private final WorkerRunRepository workerRuns;
    private final BuildSessionRepository buildSessions;
    private final Clock clock;

    public WorkerDispatchPreExecutionGuard(
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            BuildSessionRepository buildSessions,
            Clock clock) {
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PreExecutionDecision check(
            ActionProposal proposal,
            ActionDefinition definition,
            ExecutionContext context,
            PolicyDecision policyDecision) {
        WorkerDispatchInput input;
        try {
            input = WorkerDispatchInput.from(proposal.input());
        } catch (IllegalArgumentException exception) {
            return deny("WORKER_DISPATCH_INPUT_INVALID", exception.getMessage());
        }
        TenantId tenantId;
        try {
            tenantId = new TenantId(context.tenantId());
        } catch (IllegalArgumentException exception) {
            return deny("WORKER_DISPATCH_SCOPE_MISMATCH", "trusted tenant is missing");
        }
        var workOrder = workOrders.find(tenantId, input.workOrderId()).orElse(null);
        if (workOrder == null) {
            return deny("WORK_ORDER_NOT_FOUND", "WorkOrder is not visible in the tenant scope");
        }
        var workerRun = workerRuns.find(tenantId, input.workerRunId()).orElse(null);
        if (workerRun == null) {
            return deny("WORKER_RUN_NOT_FOUND", "WorkerRun is not visible in the tenant scope");
        }
        if (!workerRun.workOrderId().equals(workOrder.workOrderId())
                || !workerRun.buildSessionId().equals(workOrder.buildSessionId())) {
            return deny("WORKER_DISPATCH_SCOPE_MISMATCH", "WorkerRun does not belong to the WorkOrder");
        }
        if (workerRun.attemptNo() > workOrder.maxAttempts()) {
            return deny("WORKER_ATTEMPT_LIMIT_EXCEEDED", "WorkerRun attempt exceeds the WorkOrder limit");
        }
        if (workerRun.version() != input.expectedWorkerRunVersion()) {
            return deny("WORKER_DISPATCH_VERSION_CONFLICT", "WorkerRun version changed before dispatch");
        }
        if (workerRun.status() != WorkerRunStatus.REQUESTED) {
            return deny("WORKER_DISPATCH_IN_PROGRESS", "WorkerRun is no longer REQUESTED");
        }
        if (workerRun.actionRunId().isPresent()) {
            return deny("WORKER_DISPATCH_ACTION_RUN_MISMATCH", "REQUESTED WorkerRun is already claimed");
        }
        if (!WorkerDispatchOperationIds.derive(workerRun).equals(workerRun.operationId())) {
            return deny("WORKER_OPERATION_ID_MISMATCH", "WorkerRun operation identity is not deterministic");
        }
        if (!workerRun.workerCapabilitySnapshot().supportsAll(workOrder.requiredCapabilities())) {
            return deny("WORK_ORDER_CAPABILITY_MISMATCH", "Worker binding lacks required capabilities");
        }

        var buildSession = buildSessions.find(tenantId, workerRun.buildSessionId()).orElse(null);
        if (buildSession == null) {
            return deny("BUILD_SESSION_NOT_FOUND", "BuildSession is not visible in the tenant scope");
        }
        if ((buildSession.status() != BuildSessionStatus.RUNNING
                        && buildSession.status() != BuildSessionStatus.REPAIRING)
                || buildSession.cancellationRequestedAt().isPresent()) {
            return deny(
                    "BUILD_SESSION_NOT_DISPATCHABLE",
                    "BuildSession is not in a coding-worker dispatch state");
        }
        if (!workOrder.phase().equals(buildSession.currentPhase().id())) {
            return deny("WORK_ORDER_PHASE_STALE", "WorkOrder does not match the current BuildSession phase");
        }
        if (buildSession.selectedCodingWorkerBinding().isEmpty()
                || !buildSession.selectedCodingWorkerBinding().orElseThrow().equals(workerRun.workerBindingId())) {
            return deny(
                    "WORKER_BINDING_MISMATCH",
                    "WorkerRun does not use an explicitly selected coding-worker binding");
        }
        Instant now = clock.instant();
        if (!now.isBefore(buildSession.deadlineAt())) {
            return deny("BUILD_SESSION_DEADLINE_EXCEEDED", "BuildSession deadline has elapsed");
        }
        if (!now.isBefore(workOrder.deadlineAt()) || !now.isBefore(workerRun.deadlineAt())) {
            return deny("WORK_ORDER_DEADLINE_EXCEEDED", "WorkOrder or WorkerRun deadline has elapsed");
        }
        return PreExecutionDecision.allow();
    }

    private static PreExecutionDecision deny(String code, String reason) {
        return PreExecutionDecision.deny(code, reason);
    }
}
