package io.github.flowerjvm.factory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.decision.Decision;
import io.github.flowerjvm.factory.application.decision.DecisionOutcome;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.decision.DecisionRepository;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxRepository;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class RepositoryContractTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final BuildSessionId SESSION_ID = new BuildSessionId("session-1");
    private static final WorkOrderId WORK_ORDER_ID = new WorkOrderId("work-1");
    private static final Instant CREATED_AT = Instant.parse("2026-08-10T00:00:00Z");
    private static final Instant DEADLINE_AT = Instant.parse("2026-08-11T00:00:00Z");

    @Test
    void buildSessionRepositoryAllowsExactlyOneVersionCasWinner() {
        BuildSessionRepository repository = new InMemoryBuildSessionRepository();
        var initial = session(BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER, 0);
        repository.create(initial);
        var winner = session(BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT, 1);
        var staleLoser = session(BuildSessionStatus.CANCELLING, BuildSessionPhase.UNDERSTAND_CUSTOMER, 1);

        assertTrue(repository.compareAndSet(initial, winner));
        assertFalse(repository.compareAndSet(initial, staleLoser));
        assertEquals(winner, repository.find(TENANT, initial.buildSessionId()).orElseThrow());
    }

    @Test
    void buildSessionRepositoryRejectsVersionJumpsAndDuplicateCreate() {
        BuildSessionRepository repository = new InMemoryBuildSessionRepository();
        var initial = session(BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER, 0);
        repository.create(initial);

        assertThrows(IllegalStateException.class, () -> repository.create(initial));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.compareAndSet(
                        initial, session(BuildSessionStatus.RUNNING, BuildSessionPhase.DESIGN_AGENT, 2)));
    }

    @Test
    void workOrderRepositoryIsInsertOnlyAndTenantScoped() {
        WorkOrderRepository repository = new InMemoryWorkOrderRepository();
        var order = order();
        repository.create(order);

        assertEquals(order, repository.find(TENANT, order.workOrderId()).orElseThrow());
        assertTrue(repository.find(new TenantId("tenant-b"), order.workOrderId()).isEmpty());
        assertThrows(IllegalStateException.class, () -> repository.create(order));
    }

    @Test
    void workerRunRepositoryUsesVersionCasAndKeepsAttemptOperationIdentity() {
        WorkerRunRepository repository = new InMemoryWorkerRunRepository();
        var initial = workerRun(WorkerRunStatus.REQUESTED, 0);
        repository.create(initial);
        var completed = workerRun(WorkerRunStatus.SUCCEEDED, 1);

        assertEquals("operation:worker-run-1:attempt:1", initial.operationId());
        assertEquals(1, initial.attemptNo());
        assertTrue(repository.compareAndSet(initial, completed));
        assertFalse(repository.compareAndSet(initial, completed));
        assertEquals(completed, repository.find(TENANT, initial.workerRunId()).orElseThrow());
    }

    @Test
    void decisionPointRepositoryUsesCasWhileDecisionRepositoryIsInsertOnly() {
        DecisionPointRepository points = new InMemoryDecisionPointRepository();
        DecisionRepository decisions = new InMemoryDecisionRepository();
        var open = decisionPoint(DecisionPointStatus.OPEN, 0);
        var decision = decision();
        var approved = decisionPoint(DecisionPointStatus.APPROVED, 1);
        points.create(open);
        decisions.create(decision);

        assertTrue(points.compareAndSet(open, approved));
        assertFalse(points.compareAndSet(open, approved));
        assertEquals(approved, points.find(TENANT, open.decisionPointId()).orElseThrow());
        assertEquals(decision, decisions.find(TENANT, decision.decisionId()).orElseThrow());
        assertThrows(IllegalStateException.class, () -> decisions.create(decision));
    }

    @Test
    void decisionPointDefensivelyCopiesRequiredPermissions() {
        var permissions = ConcurrentHashMap.<String>newKeySet();
        permissions.add("factory.candidate.approve");
        var point = openDecisionPoint(permissions);

        permissions.clear();

        assertEquals(Set.of("factory.candidate.approve"), point.requiredPermissions());
        assertThrows(UnsupportedOperationException.class, () -> point.requiredPermissions().clear());
    }

    @Test
    void dispatchOutboxRepositoryUsesVersionCas() {
        DispatchOutboxRepository repository = new InMemoryDispatchOutboxRepository();
        var pending = outbox(DispatchOutboxStatus.PENDING, 0);
        var dispatched = outbox(DispatchOutboxStatus.DISPATCHED, 1);
        repository.create(pending);

        assertTrue(repository.compareAndSet(pending, dispatched));
        assertFalse(repository.compareAndSet(pending, dispatched));
        assertEquals(dispatched, repository.find(TENANT, pending.outboxId()).orElseThrow());
    }

    @Test
    void mutableRecordsRejectInvalidVersionAndTerminalShape() {
        assertThrows(
                IllegalArgumentException.class,
                () -> session(BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerRunRecord(
                        new WorkerRunId("worker-run-invalid"),
                        TENANT,
                        SESSION_ID,
                        WORK_ORDER_ID,
                        1,
                        "coding-worker",
                        "1.0.0",
                        new WorkerCapabilities(Set.of()),
                        WorkerRunStatus.SUCCEEDED,
                        Optional.of("action-run-invalid"),
                        "operation-invalid",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(CREATED_AT),
                        DEADLINE_AT,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("WORKER_SUCCEEDED"),
                        Optional.empty(),
                        Optional.of(WorkerRetryDisposition.NEVER),
                        0,
                        CREATED_AT,
                        CREATED_AT));
    }

    @Test
    void applicationDoesNotSeeOuterModules() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("io.github.flowerjvm.factory.infrastructure.FactoryInfrastructureModule"));
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("io.github.flowerjvm.factory.host.FactoryBuilderHostModule"));
    }

    private static BuildSession session(
            BuildSessionStatus status, BuildSessionPhase phase, long version) {
        return new BuildSession(
                SESSION_ID,
                TENANT,
                new ProjectId("project-1"),
                ProductLineId.AGENT_PACK,
                "request-1",
                "principal-1",
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
                CREATED_AT,
                DEADLINE_AT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                version,
                CREATED_AT,
                CREATED_AT.plusSeconds(version));
    }

    private static WorkOrder order() {
        return new WorkOrder(
                WORK_ORDER_ID,
                TENANT,
                SESSION_ID,
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
                DEADLINE_AT,
                3,
                "work-order:session-1:generate:1",
                WorkOrderCreatorType.SYSTEM,
                "factory-builder",
                CREATED_AT);
    }

    private static WorkerRunRecord workerRun(WorkerRunStatus status, long version) {
        boolean terminal = status.isTerminal();
        boolean succeeded = status == WorkerRunStatus.SUCCEEDED;
        return new WorkerRunRecord(
                new WorkerRunId("worker-run-1"),
                TENANT,
                SESSION_ID,
                WORK_ORDER_ID,
                1,
                "coding-worker",
                "1.0.0",
                new WorkerCapabilities(Set.of(new WorkerCapability("repository-read"))),
                status,
                status == WorkerRunStatus.REQUESTED ? Optional.empty() : Optional.of("action-run-1"),
                "operation:worker-run-1:attempt:1",
                status == WorkerRunStatus.REQUESTED ? Optional.empty() : Optional.of(hash("3")),
                Optional.empty(),
                status == WorkerRunStatus.REQUESTED
                        ? Optional.empty()
                        : Optional.of(new DispatchOutboxId("outbox-1")),
                status == WorkerRunStatus.REQUESTED ? Optional.empty() : Optional.of(CREATED_AT),
                DEADLINE_AT,
                Optional.empty(),
                Optional.empty(),
                terminal ? Optional.of(CREATED_AT.plusSeconds(10)) : Optional.empty(),
                succeeded ? Optional.of(new ArtifactReference("artifact:result-manifest")) : Optional.empty(),
                succeeded ? Optional.of(hash("4")) : Optional.empty(),
                terminal ? Optional.of("WORKER_SUCCEEDED") : Optional.empty(),
                terminal ? Optional.of("durable worker result") : Optional.empty(),
                terminal ? Optional.of(WorkerRetryDisposition.NEVER) : Optional.empty(),
                version,
                CREATED_AT,
                CREATED_AT.plusSeconds(version));
    }

    private static DecisionPoint decisionPoint(DecisionPointStatus status, long version) {
        if (status == DecisionPointStatus.OPEN) {
            return openDecisionPoint(Set.of("factory.candidate.approve"));
        }
        return new DecisionPoint(
                new DecisionPointId("decision-point-1"),
                TENANT,
                SESSION_ID,
                "candidate-release-review",
                status,
                "candidate",
                "candidate-1",
                1,
                hash("5"),
                new ArtifactReference("artifact:question"),
                "factory.release-review-options.v1",
                Set.of("factory.candidate.approve"),
                1,
                new ArtifactReference("artifact:policy"),
                CREATED_AT,
                DEADLINE_AT,
                Optional.of(CREATED_AT.plusSeconds(10)),
                Optional.of(new DecisionId("decision-1")),
                version);
    }

    private static DecisionPoint openDecisionPoint(Set<String> permissions) {
        return new DecisionPoint(
                new DecisionPointId("decision-point-1"),
                TENANT,
                SESSION_ID,
                "candidate-release-review",
                DecisionPointStatus.OPEN,
                "candidate",
                "candidate-1",
                1,
                hash("5"),
                new ArtifactReference("artifact:question"),
                "factory.release-review-options.v1",
                permissions,
                1,
                new ArtifactReference("artifact:policy"),
                CREATED_AT,
                DEADLINE_AT,
                Optional.empty(),
                Optional.empty(),
                0);
    }

    private static Decision decision() {
        return new Decision(
                new DecisionId("decision-1"),
                TENANT,
                new DecisionPointId("decision-point-1"),
                "decision-request-1",
                DecisionOutcome.APPROVE,
                Optional.of("approve"),
                Optional.of("Verified exact candidate hash"),
                "principal-approver",
                new ArtifactReference("artifact:authority-snapshot"),
                hash("5"),
                CREATED_AT.plusSeconds(10));
    }

    private static DispatchOutbox outbox(DispatchOutboxStatus status, long version) {
        return new DispatchOutbox(
                new DispatchOutboxId("outbox-1"),
                TENANT,
                WorkerOutboxOperations.DISPATCH,
                "worker-run",
                "worker-run-1",
                "operation:worker-run-1:attempt:1",
                new ArtifactReference("artifact:dispatch-payload"),
                status,
                CREATED_AT,
                status == DispatchOutboxStatus.DISPATCHED ? 1 : 0,
                status == DispatchOutboxStatus.DISPATCHED
                        ? Optional.of("WORKER_DISPATCH_ACCEPTED")
                        : Optional.empty(),
                version,
                CREATED_AT,
                CREATED_AT.plusSeconds(version));
    }

    private static ContentHash hash(String digit) {
        return new ContentHash(digit.repeat(64));
    }

    private record BuildSessionKey(TenantId tenantId, BuildSessionId buildSessionId) {}

    private static final class InMemoryBuildSessionRepository implements BuildSessionRepository {
        private final Map<BuildSessionKey, BuildSession> records = new ConcurrentHashMap<>();

        @Override
        public void create(BuildSession session) {
            var key = new BuildSessionKey(session.tenantId(), session.buildSessionId());
            if (records.putIfAbsent(key, session) != null) {
                throw new IllegalStateException("BuildSession already exists");
            }
        }

        @Override
        public Optional<BuildSession> find(TenantId tenantId, BuildSessionId buildSessionId) {
            return Optional.ofNullable(records.get(new BuildSessionKey(tenantId, buildSessionId)));
        }

        @Override
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            requireSameIdentity(expected.tenantId(), expected.buildSessionId(), next.tenantId(), next.buildSessionId());
            requireNextVersion(expected.version(), next.version());
            return records.replace(
                    new BuildSessionKey(expected.tenantId(), expected.buildSessionId()), expected, next);
        }
    }

    private record WorkOrderKey(TenantId tenantId, WorkOrderId workOrderId) {}

    private static final class InMemoryWorkOrderRepository implements WorkOrderRepository {
        private final Map<WorkOrderKey, WorkOrder> records = new ConcurrentHashMap<>();

        @Override
        public void create(WorkOrder workOrder) {
            var key = new WorkOrderKey(workOrder.tenantId(), workOrder.workOrderId());
            if (records.putIfAbsent(key, workOrder) != null) {
                throw new IllegalStateException("WorkOrder already exists");
            }
        }

        @Override
        public Optional<WorkOrder> find(TenantId tenantId, WorkOrderId workOrderId) {
            return Optional.ofNullable(records.get(new WorkOrderKey(tenantId, workOrderId)));
        }
    }

    private record WorkerRunKey(TenantId tenantId, WorkerRunId workerRunId) {}

    private static final class InMemoryWorkerRunRepository implements WorkerRunRepository {
        private final Map<WorkerRunKey, WorkerRunRecord> records = new ConcurrentHashMap<>();

        @Override
        public void create(WorkerRunRecord workerRun) {
            var key = new WorkerRunKey(workerRun.tenantId(), workerRun.workerRunId());
            if (records.putIfAbsent(key, workerRun) != null) {
                throw new IllegalStateException("WorkerRun already exists");
            }
        }

        @Override
        public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
            return Optional.ofNullable(records.get(new WorkerRunKey(tenantId, workerRunId)));
        }

        @Override
        public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
            requireSameIdentity(expected.tenantId(), expected.workerRunId(), next.tenantId(), next.workerRunId());
            requireNextVersion(expected.version(), next.version());
            return records.replace(new WorkerRunKey(expected.tenantId(), expected.workerRunId()), expected, next);
        }
    }

    private record DecisionPointKey(TenantId tenantId, DecisionPointId decisionPointId) {}

    private static final class InMemoryDecisionPointRepository implements DecisionPointRepository {
        private final Map<DecisionPointKey, DecisionPoint> records = new ConcurrentHashMap<>();

        @Override
        public void create(DecisionPoint decisionPoint) {
            var key = new DecisionPointKey(decisionPoint.tenantId(), decisionPoint.decisionPointId());
            if (records.putIfAbsent(key, decisionPoint) != null) {
                throw new IllegalStateException("DecisionPoint already exists");
            }
        }

        @Override
        public Optional<DecisionPoint> find(TenantId tenantId, DecisionPointId decisionPointId) {
            return Optional.ofNullable(records.get(new DecisionPointKey(tenantId, decisionPointId)));
        }

        @Override
        public boolean compareAndSet(DecisionPoint expected, DecisionPoint next) {
            requireSameIdentity(
                    expected.tenantId(), expected.decisionPointId(), next.tenantId(), next.decisionPointId());
            requireNextVersion(expected.version(), next.version());
            return records.replace(
                    new DecisionPointKey(expected.tenantId(), expected.decisionPointId()), expected, next);
        }
    }

    private record DecisionKey(TenantId tenantId, DecisionId decisionId) {}

    private static final class InMemoryDecisionRepository implements DecisionRepository {
        private final Map<DecisionKey, Decision> records = new ConcurrentHashMap<>();

        @Override
        public void create(Decision decision) {
            var key = new DecisionKey(decision.tenantId(), decision.decisionId());
            if (records.putIfAbsent(key, decision) != null) {
                throw new IllegalStateException("Decision already exists");
            }
        }

        @Override
        public Optional<Decision> find(TenantId tenantId, DecisionId decisionId) {
            return Optional.ofNullable(records.get(new DecisionKey(tenantId, decisionId)));
        }

        @Override
        public Optional<Decision> findByRequestIdempotencyKey(
                TenantId tenantId,
                DecisionPointId decisionPointId,
                String requestIdempotencyKey) {
            return records.values().stream()
                    .filter(decision -> decision.tenantId().equals(tenantId))
                    .filter(decision -> decision.decisionPointId().equals(decisionPointId))
                    .filter(decision -> decision.requestIdempotencyKey().equals(requestIdempotencyKey))
                    .findFirst();
        }
    }

    private record DispatchOutboxKey(TenantId tenantId, DispatchOutboxId outboxId) {}

    private static final class InMemoryDispatchOutboxRepository implements DispatchOutboxRepository {
        private final Map<DispatchOutboxKey, DispatchOutbox> records = new ConcurrentHashMap<>();

        @Override
        public void create(DispatchOutbox outbox) {
            var key = new DispatchOutboxKey(outbox.tenantId(), outbox.outboxId());
            if (records.putIfAbsent(key, outbox) != null) {
                throw new IllegalStateException("DispatchOutbox already exists");
            }
        }

        @Override
        public Optional<DispatchOutbox> find(TenantId tenantId, DispatchOutboxId outboxId) {
            return Optional.ofNullable(records.get(new DispatchOutboxKey(tenantId, outboxId)));
        }

        @Override
        public boolean compareAndSet(DispatchOutbox expected, DispatchOutbox next) {
            requireSameIdentity(expected.tenantId(), expected.outboxId(), next.tenantId(), next.outboxId());
            requireNextVersion(expected.version(), next.version());
            return records.replace(new DispatchOutboxKey(expected.tenantId(), expected.outboxId()), expected, next);
        }
    }

    private static void requireSameIdentity(
            Object expectedTenant, Object expectedId, Object nextTenant, Object nextId) {
        if (!expectedTenant.equals(nextTenant) || !expectedId.equals(nextId)) {
            throw new IllegalArgumentException("CAS identity must not change");
        }
    }

    private static void requireNextVersion(long expected, long next) {
        if (next != expected + 1) {
            throw new IllegalArgumentException("CAS version must increase by exactly one");
        }
    }
}
