package io.github.flowerjvm.factory.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDispatch;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecisionType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkerDispatchActionContractTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final BuildSessionId SESSION = new BuildSessionId("session-1");
    private static final WorkOrderId WORK_ORDER = new WorkOrderId("work-order-1");
    private static final WorkerRunId WORKER_RUN = new WorkerRunId("worker-run-1");
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(3600);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void definitionAndStrictInputSchemaArePinned() {
        var definition = WorkerDispatchAction.definition();
        var validator = new WorkerDispatchActionValidator();

        assertEquals(WorkerDispatchAction.ACTION_ID, definition.actionId());
        assertEquals(ActionEffect.EXTERNAL_SEND, definition.effect());
        assertEquals(ActionRiskLevel.MEDIUM, definition.riskLevel());
        assertEquals(Set.of(ActionRequestChannel.INTERNAL), definition.allowedRequestChannels());
        assertEquals(Set.of(ActionProposerType.SERVICE), definition.allowedProposerTypes());
        assertEquals(Set.of(WorkerDispatchAction.PERMISSION), definition.requiredPermissions());
        assertFalse(definition.approvalRequiredByDefault());
        assertTrue(definition.auditRequired());

        assertTrue(validator.validate(proposal(input(0)), definition, context(true)).valid());
        var unknownField = Map.<String, Object>of(
                WorkerDispatchAction.WORK_ORDER_ID, WORK_ORDER.value(),
                WorkerDispatchAction.WORKER_RUN_ID, WORKER_RUN.value(),
                WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, 0L,
                "tenantId", TENANT.value());
        assertFalse(validator.validate(proposal(unknownField), definition, context(true)).valid());
    }

    @Test
    void policyUsesTrustedPermissionAndCanonicalTenantWorkOrder() {
        var order = workOrder();
        var run = workerRun(WorkerRunStatus.REQUESTED, 0, Optional.empty(), Optional.empty());
        var policy = new WorkerDispatchPolicyGate(workOrders(order), workerRuns(run));

        assertEquals(
                PolicyDecisionType.ALLOW,
                policy.evaluate(proposal(input(0)), WorkerDispatchAction.definition(), context(true)).type());
        assertEquals(
                PolicyDecisionType.DENY,
                policy.evaluate(proposal(input(0)), WorkerDispatchAction.definition(), context(false)).type());
        assertEquals(
                PolicyDecisionType.DENY,
                policy.evaluate(
                                proposal(input(0)).toBuilder().idempotencyKey("unbound-key").build(),
                                WorkerDispatchAction.definition(),
                                context(true))
                        .type());

        var wrongResource = new ExecutionContext(
                TENANT.value(),
                "factory-builder",
                "action-run-1",
                "trace-1",
                Map.of(
                        "actor.permissions", Set.of(WorkerDispatchAction.PERMISSION),
                        "resource.type", WorkerDispatchAction.RESOURCE_TYPE,
                        "resource.id", "another-work-order"));
        assertEquals(
                PolicyDecisionType.DENY,
                policy.evaluate(proposal(input(0)), WorkerDispatchAction.definition(), wrongResource).type());
    }

    @Test
    void guardRechecksCasVersionActionRunCapabilitiesAndDeadlines() {
        var order = workOrder();
        var run = workerRun(WorkerRunStatus.REQUESTED, 0, Optional.empty(), Optional.empty());
        var guard = new WorkerDispatchPreExecutionGuard(
                workOrders(order),
                workerRuns(run),
                buildSessions(buildSession()),
                CLOCK);

        assertTrue(guard.check(
                        proposal(input(0)),
                        WorkerDispatchAction.definition(),
                        context(true),
                        PolicyDecision.allow())
                .allowed());
        assertEquals(
                "WORKER_DISPATCH_VERSION_CONFLICT",
                guard.check(
                                proposal(input(1)),
                                WorkerDispatchAction.definition(),
                                context(true),
                                PolicyDecision.allow())
                        .code());

        var blockedGuard = new WorkerDispatchPreExecutionGuard(
                workOrders(order),
                workerRuns(run),
                buildSessions(buildSession(BuildSessionStatus.BLOCKED, BuildSessionPhase.GENERATE_CANDIDATE)),
                CLOCK);
        assertEquals(
                "BUILD_SESSION_NOT_DISPATCHABLE",
                blockedGuard.check(
                                proposal(input(0)),
                                WorkerDispatchAction.definition(),
                                context(true),
                                PolicyDecision.allow())
                        .code());

        var stalePhaseGuard = new WorkerDispatchPreExecutionGuard(
                workOrders(order),
                workerRuns(run),
                buildSessions(buildSession(BuildSessionStatus.RUNNING, BuildSessionPhase.TEST)),
                CLOCK);
        assertEquals(
                "WORK_ORDER_PHASE_STALE",
                stalePhaseGuard.check(
                                proposal(input(0)),
                                WorkerDispatchAction.definition(),
                                context(true),
                                PolicyDecision.allow())
                        .code());
    }

    @Test
    void deferredExecutorCreatesOnlyTheDurableOutboxIntent() {
        var order = workOrder();
        var run = workerRun(WorkerRunStatus.REQUESTED, 0, Optional.empty(), Optional.empty());
        AtomicReference<DispatchOutbox> captured = new AtomicReference<>();
        WorkerDispatchTransaction transaction = (submittedOrder, expected, dispatching, outbox) -> {
            assertEquals(order, submittedOrder);
            captured.set(outbox);
            assertEquals(WorkerRunStatus.DISPATCHING, dispatching.status());
            assertEquals(Optional.of(outbox.outboxId()), dispatching.dispatchOutboxId());
            assertEquals(Optional.of("action-run-1"), dispatching.actionRunId());
            assertEquals(
                    Optional.of(WorkerDispatchOperationIds.hashAttemptToken("runtime-attempt-token")),
                    dispatching.attemptTokenHash());
            return true;
        };
        var executor = new WorkerDispatchActionExecutor(
                workOrders(order), workerRuns(run), transaction, CLOCK);
        var executionContext = new ActionExecutionContext(
                context(true),
                proposal(input(0)),
                WorkerDispatchAction.definition(),
                input(0),
                "runtime-attempt-token");

        ActionDispatch.Awaiting awaiting = executor.dispatchDeferred(executionContext);

        assertEquals(run.operationId(), awaiting.operationId());
        assertEquals(DEADLINE, awaiting.dueAt());
        assertEquals(order.inputArtifactManifestRef(), captured.get().payloadArtifactRef());
        assertEquals(run.operationId(), captured.get().operationId());
        assertThrows(
                IllegalStateException.class,
                () -> executor.cancel(executionContext, run.operationId(), "cancel"));
    }

    private static Map<String, Object> input(long version) {
        return Map.of(
                WorkerDispatchAction.WORK_ORDER_ID, WORK_ORDER.value(),
                WorkerDispatchAction.WORKER_RUN_ID, WORKER_RUN.value(),
                WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, version);
    }

    private static ActionProposal proposal(Map<String, Object> input) {
        WorkOrder order = workOrder();
        WorkerRunRecord run = workerRun(WorkerRunStatus.REQUESTED, 0, Optional.empty(), Optional.empty());
        return ActionProposal.builder(WorkerDispatchAction.ACTION_ID)
                .proposalId("proposal-1")
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-builder")
                .input(input)
                .idempotencyKey(WorkerDispatchIdempotencyKeys.derive(order, run))
                .build();
    }

    private static ExecutionContext context(boolean permission) {
        return new ExecutionContext(
                TENANT.value(),
                "factory-builder",
                "action-run-1",
                "trace-1",
                Map.of(
                        "actor.permissions",
                        permission ? Set.of(WorkerDispatchAction.PERMISSION) : Set.of(),
                        "resource.type",
                        WorkerDispatchAction.RESOURCE_TYPE,
                        "resource.id",
                        WORK_ORDER.value()));
    }

    private static WorkOrder workOrder() {
        return new WorkOrder(
                WORK_ORDER,
                TENANT,
                SESSION,
                "generate-candidate",
                "Generate a locked candidate",
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new ArtifactReference("artifact:instruction"),
                hash("1"),
                new ArtifactReference("artifact:input-manifest"),
                hash("2"),
                "workspace:session-1",
                List.of("inputs"),
                List.of("candidate"),
                Set.of(new WorkerCapability("repository-read")),
                "factory.pack-candidate",
                "1.0.0",
                new ArtifactReference("artifact:policy"),
                DEADLINE,
                3,
                "work-order:session-1:generate:1",
                WorkOrderCreatorType.SYSTEM,
                "factory-builder",
                NOW.minusSeconds(60));
    }

    private static BuildSession buildSession() {
        return buildSession(BuildSessionStatus.RUNNING, BuildSessionPhase.GENERATE_CANDIDATE);
    }

    private static BuildSession buildSession(BuildSessionStatus status, BuildSessionPhase phase) {
        return new BuildSession(
                SESSION,
                TENANT,
                new ProjectId("project-1"),
                ProductLineId.AGENT_PACK,
                "request-1",
                "factory-builder",
                status,
                phase,
                new ArtifactReference("artifact:requirements"),
                hash("0"),
                Optional.empty(),
                Optional.of("coding-worker"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                3,
                NOW.minusSeconds(60),
                DEADLINE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60));
    }

    private static WorkerRunRecord workerRun(
            WorkerRunStatus status,
            long version,
            Optional<io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId> outboxId,
            Optional<Instant> startedAt) {
        String operationId = WorkerDispatchOperationIds.derive(
                TENANT, WORK_ORDER, WORKER_RUN, 1, "coding-worker");
        return new WorkerRunRecord(
                WORKER_RUN,
                TENANT,
                SESSION,
                WORK_ORDER,
                1,
                "coding-worker",
                "1.0.0",
                new WorkerCapabilities(Set.of(new WorkerCapability("repository-read"))),
                status,
                status == WorkerRunStatus.REQUESTED ? Optional.empty() : Optional.of("action-run-1"),
                operationId,
                Optional.empty(),
                Optional.empty(),
                outboxId,
                startedAt,
                DEADLINE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                version,
                NOW.minusSeconds(60),
                NOW.plusSeconds(version));
    }

    private static WorkOrderRepository workOrders(WorkOrder order) {
        return new WorkOrderRepository() {
            @Override
            public void create(WorkOrder ignored) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<WorkOrder> find(TenantId tenantId, WorkOrderId workOrderId) {
                return TENANT.equals(tenantId) && WORK_ORDER.equals(workOrderId)
                        ? Optional.of(order)
                        : Optional.empty();
            }
        };
    }

    private static WorkerRunRepository workerRuns(WorkerRunRecord run) {
        return new WorkerRunRepository() {
            @Override
            public void create(WorkerRunRecord ignored) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
                return TENANT.equals(tenantId) && WORKER_RUN.equals(workerRunId)
                        ? Optional.of(run)
                        : Optional.empty();
            }

            @Override
            public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static BuildSessionRepository buildSessions(BuildSession session) {
        return new BuildSessionRepository() {
            @Override
            public void create(BuildSession ignored) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<BuildSession> find(TenantId tenantId, BuildSessionId buildSessionId) {
                return TENANT.equals(tenantId) && SESSION.equals(buildSessionId)
                        ? Optional.of(session)
                        : Optional.empty();
            }

            @Override
            public boolean compareAndSet(BuildSession expected, BuildSession next) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ContentHash hash(String digit) {
        return new ContentHash(digit.repeat(64));
    }
}
