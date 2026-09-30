package io.github.flowerjvm.factory.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import java.util.Map;
import javax.sql.DataSource;

final class ActionRunFixtures {
    private ActionRunFixtures() {}

    static void createWorkerDispatchOwner(
            DataSource dataSource,
            WorkerRunRecord workerRun,
            String actionRunId) {
        createWorkerDispatchOwner(
                dataSource,
                workerRun,
                actionRunId,
                workerRun.workOrderId().value(),
                workerRun.workerRunId().value());
    }

    static void createWorkerDispatchOwner(
            DataSource dataSource,
            WorkerRunRecord workerRun,
            String actionRunId,
            String inputWorkOrderId,
            String inputWorkerRunId) {
        ActionProposal proposal = ActionProposal.builder(WorkerDispatchAction.ACTION_ID)
                .proposalId("proposal-" + actionRunId)
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-service")
                .input(Map.of(
                        WorkerDispatchAction.WORK_ORDER_ID,
                        inputWorkOrderId,
                        WorkerDispatchAction.WORKER_RUN_ID,
                        inputWorkerRunId,
                        WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION,
                        workerRun.version()))
                .idempotencyKey("dispatch-" + workerRun.workerRunId().value())
                .build();
        ExecutionContext context = new ExecutionContext(
                workerRun.tenantId().value(),
                "factory-service",
                actionRunId,
                "trace-" + actionRunId,
                Map.of());
        new JdbcRunStore(dataSource, new ObjectMapper()).create(ActionRun.requested(proposal, context));
    }

    static ActionRun createWaitingWorkerDispatchOwner(
            DataSource dataSource,
            WorkerRunRecord workerRun,
            String actionRunId) {
        createWorkerDispatchOwner(dataSource, workerRun, actionRunId);
        JdbcRunStore store = new JdbcRunStore(dataSource, new ObjectMapper());
        ActionRun requested = store.find(actionRunId).orElseThrow();
        ActionRun waiting = requested.toBuilder()
                .version(requested.version() + 1)
                .status(ActionRunStatus.WAITING_EXTERNAL)
                .currentStage("EXECUTE")
                .attemptToken("attempt-" + actionRunId)
                .externalOperationId(workerRun.operationId())
                .updatedAt(requested.updatedAt().plusMillis(1))
                .build();
        if (!store.compareAndSet(requested, waiting)) {
            throw new IllegalStateException("could not park ActionRun in WAITING_EXTERNAL");
        }
        return waiting;
    }

    static ActionRun createRunningWorkerDispatchOwner(
            DataSource dataSource,
            WorkerRunRecord workerRun,
            String actionRunId) {
        createWorkerDispatchOwner(dataSource, workerRun, actionRunId);
        JdbcRunStore store = new JdbcRunStore(dataSource, new ObjectMapper());
        ActionRun requested = store.find(actionRunId).orElseThrow();
        ActionRun running = requested.toBuilder()
                .version(requested.version() + 1)
                .status(ActionRunStatus.RUNNING)
                .currentStage("execute-action")
                .attemptToken("attempt-" + actionRunId)
                .externalOperationId("")
                .externalOperationMetadata(Map.of())
                .dueAt(null)
                .result(null)
                .failureReason("")
                .updatedAt(requested.updatedAt().plusMillis(1))
                .build();
        if (!store.compareAndSet(requested, running)) {
            throw new IllegalStateException("could not place ActionRun in pre-park RUNNING");
        }
        return running;
    }
}
