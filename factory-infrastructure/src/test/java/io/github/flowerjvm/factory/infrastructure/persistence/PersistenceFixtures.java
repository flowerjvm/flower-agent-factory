package io.github.flowerjvm.factory.infrastructure.persistence;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.decision.Decision;
import io.github.flowerjvm.factory.application.decision.DecisionOutcome;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewPolicy;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
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
import java.util.Optional;
import java.util.Set;

final class PersistenceFixtures {
    static final TenantId TENANT = new TenantId("tenant-a");
    static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    static final ContentHash HASH_A = new ContentHash("a".repeat(64));
    static final ContentHash HASH_B = new ContentHash("b".repeat(64));

    private PersistenceFixtures() {}

    static BuildSession buildSession(String suffix) {
        return buildSession(suffix, "request-" + suffix);
    }

    static BuildSession buildSession(String suffix, String requestIdempotencyKey) {
        return buildSession(
                suffix,
                requestIdempotencyKey,
                BuildSessionPhase.UNDERSTAND_CUSTOMER);
    }

    static BuildSession buildSession(
            String suffix,
            String requestIdempotencyKey,
            BuildSessionPhase phase) {
        return new BuildSession(
                new BuildSessionId("build-" + suffix),
                TENANT,
                new ProjectId("project-" + suffix),
                ProductLineId.AGENT_PACK,
                requestIdempotencyKey,
                "principal-a",
                BuildSessionStatus.RUNNING,
                phase,
                new ArtifactReference("artifact:requirements:" + suffix),
                HASH_A,
                Optional.of("manager-binding"),
                Optional.of("coding-binding"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                3,
                NOW,
                NOW.plusSeconds(3600),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW);
    }

    static BuildSession terminal(
            BuildSession expected, BuildSessionStatus status, BuildSessionPhase phase, String code) {
        return new BuildSession(
                expected.buildSessionId(),
                expected.tenantId(),
                expected.projectId(),
                expected.productLineId(),
                expected.requestIdempotencyKey(),
                expected.createdBy(),
                status,
                phase,
                expected.requirementsArtifactRef(),
                expected.requirementsHash(),
                expected.selectedManagerWorkerBinding(),
                expected.selectedCodingWorkerBinding(),
                expected.currentBlueprintRef(),
                expected.currentCandidateId(),
                expected.currentCandidateHash(),
                expected.currentCertificationId(),
                expected.repairRound(),
                expected.maxRepairRounds(),
                expected.startedAt(),
                expected.deadlineAt(),
                expected.cancellationRequestedAt(),
                Optional.of(code),
                Optional.of(code),
                expected.version() + 1,
                expected.createdAt(),
                expected.updatedAt().plusMillis(1));
    }

    static WorkOrder workOrder(BuildSession buildSession, String suffix) {
        return workOrder(buildSession, suffix, Optional.empty());
    }

    static WorkOrder workOrder(
            BuildSession buildSession,
            String suffix,
            Optional<WorkOrderId> supersedesWorkOrderId) {
        return new WorkOrder(
                new WorkOrderId("work-" + suffix),
                buildSession.tenantId(),
                buildSession.buildSessionId(),
                "generate-candidate",
                "Generate the locked candidate",
                1,
                supersedesWorkOrderId,
                Optional.empty(),
                Optional.of("base-revision"),
                new ArtifactReference("artifact:instruction:" + suffix),
                HASH_A,
                new ArtifactReference("artifact:input-manifest:" + suffix),
                HASH_B,
                "workspace:" + suffix,
                List.of("/workspace/input"),
                List.of("/workspace/output"),
                Set.of(new WorkerCapability("java"), new WorkerCapability("maven")),
                "pack-candidate",
                "1",
                new ArtifactReference("artifact:policy:" + suffix),
                NOW.plusSeconds(1800),
                2,
                "logical-" + suffix,
                WorkOrderCreatorType.SYSTEM,
                "factory",
                NOW);
    }

    static DispatchOutbox outbox(WorkerRunRecord workerRun, String suffix) {
        return new DispatchOutbox(
                new DispatchOutboxId("outbox-" + suffix),
                workerRun.tenantId(),
                "WORKER_DISPATCH",
                "WORKER_RUN",
                workerRun.workerRunId().value(),
                workerRun.operationId(),
                new ArtifactReference("artifact:dispatch:" + suffix),
                DispatchOutboxStatus.PENDING,
                NOW,
                0,
                Optional.empty(),
                0,
                NOW,
                NOW);
    }

    static WorkerRunRecord workerRun(BuildSession buildSession, WorkOrder workOrder, String suffix) {
        return workerRun(buildSession, workOrder, suffix, 1);
    }

    static WorkerRunRecord workerRun(
            BuildSession buildSession, WorkOrder workOrder, String suffix, int attemptNo) {
        return workerRun(
                buildSession,
                workOrder,
                suffix,
                attemptNo,
                "operation-" + suffix);
    }

    static WorkerRunRecord workerRun(
            BuildSession buildSession,
            WorkOrder workOrder,
            String suffix,
            int attemptNo,
            String operationId) {
        return new WorkerRunRecord(
                new WorkerRunId("worker-" + suffix),
                buildSession.tenantId(),
                buildSession.buildSessionId(),
                workOrder.workOrderId(),
                attemptNo,
                "coding-binding",
                "1.0.0",
                new WorkerCapabilities(Set.of(new WorkerCapability("java"), new WorkerCapability("maven"))),
                WorkerRunStatus.REQUESTED,
                Optional.empty(),
                operationId,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW.plusSeconds(1800),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW);
    }

    static WorkerRunRecord failedWorkerRun(WorkerRunRecord expected, String code) {
        Instant completedAt = expected.updatedAt().plusMillis(1);
        return new WorkerRunRecord(
                expected.workerRunId(),
                expected.tenantId(),
                expected.buildSessionId(),
                expected.workOrderId(),
                expected.attemptNo(),
                expected.workerBindingId(),
                expected.workerAdapterVersion(),
                expected.workerCapabilitySnapshot(),
                WorkerRunStatus.FAILED,
                expected.actionRunId(),
                expected.operationId(),
                expected.attemptTokenHash(),
                expected.externalSessionRef(),
                expected.dispatchOutboxId(),
                expected.startedAt(),
                expected.deadlineAt(),
                expected.heartbeatAt(),
                expected.cancelRequestedAt(),
                Optional.of(completedAt),
                Optional.empty(),
                Optional.empty(),
                Optional.of(code),
                Optional.of(code),
                Optional.of(WorkerRetryDisposition.NEVER),
                expected.version() + 1,
                expected.createdAt(),
                completedAt);
    }

    static DecisionPoint decisionPoint(BuildSession buildSession, String suffix) {
        return new DecisionPoint(
                new DecisionPointId("point-" + suffix),
                buildSession.tenantId(),
                buildSession.buildSessionId(),
                "RELEASE_REVIEW",
                DecisionPointStatus.OPEN,
                "CANDIDATE",
                "candidate-" + suffix,
                1,
                HASH_A,
                new ArtifactReference("artifact:question:" + suffix),
                AgentPackReleaseReviewPolicy.OPTIONS_SCHEMA_ID,
                Set.of(AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION),
                1,
                new ArtifactReference("artifact:policy:" + suffix),
                NOW,
                NOW.plusSeconds(600),
                Optional.empty(),
                Optional.empty(),
                0);
    }

    static Decision decision(DecisionPoint point, String suffix) {
        return decision(point, suffix, point.subjectHash());
    }

    static Decision decision(DecisionPoint point, String suffix, ContentHash subjectHash) {
        return new Decision(
                new DecisionId("decision-" + suffix),
                point.tenantId(),
                point.decisionPointId(),
                "decision-request-" + suffix,
                DecisionOutcome.APPROVE,
                Optional.of("release"),
                Optional.of("verified"),
                "reviewer-a",
                new ArtifactReference("artifact:authority:" + suffix),
                subjectHash,
                NOW.plusSeconds(10));
    }

    static DecisionPoint terminalDecisionPoint(
            DecisionPoint expected,
            DecisionId terminalDecisionId) {
        return new DecisionPoint(
                expected.decisionPointId(),
                expected.tenantId(),
                expected.buildSessionId(),
                expected.type(),
                DecisionPointStatus.APPROVED,
                expected.subjectType(),
                expected.subjectId(),
                expected.subjectVersion(),
                expected.subjectHash(),
                expected.questionArtifactRef(),
                expected.optionsSchemaId(),
                expected.requiredPermissions(),
                expected.minimumApprovers(),
                expected.policySnapshotRef(),
                expected.openedAt(),
                expected.dueAt(),
                Optional.of(NOW.plusSeconds(10)),
                Optional.of(terminalDecisionId),
                expected.version() + 1);
    }
}
