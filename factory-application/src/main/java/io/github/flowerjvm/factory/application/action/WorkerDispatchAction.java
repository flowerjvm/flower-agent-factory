package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import java.util.Map;
import java.util.Set;

/** Stable public contract for the first active Factory action. */
public final class WorkerDispatchAction {
    public static final String ACTION_ID = "factory.worker.dispatch";
    public static final String PERMISSION = "factory.worker.dispatch";
    public static final String INPUT_SCHEMA_ID = "factory.worker.dispatch.input.v1";
    public static final String OUTPUT_SCHEMA_ID = "factory.worker.dispatch.output.v1";
    public static final String WORK_ORDER_ID = "workOrderId";
    public static final String WORKER_RUN_ID = "workerRunId";
    public static final String EXPECTED_WORKER_RUN_VERSION = "expectedWorkerRunVersion";
    public static final String RESOURCE_TYPE = "work-order";

    private WorkerDispatchAction() {}

    public static ActionDefinition definition() {
        return new ActionDefinition(
                ACTION_ID,
                "Dispatch coding worker",
                "Atomically prepare one durable Worker dispatch outbox intent.",
                ActionEffect.EXTERNAL_SEND,
                ActionRiskLevel.MEDIUM,
                Set.of(ActionRequestChannel.INTERNAL),
                Set.of(ActionProposerType.SERVICE),
                Set.of(PERMISSION),
                false,
                false,
                true,
                INPUT_SCHEMA_ID,
                OUTPUT_SCHEMA_ID,
                Map.of("resourceType", RESOURCE_TYPE));
    }
}
