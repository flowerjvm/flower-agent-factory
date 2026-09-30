package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.util.Map;
import java.util.Set;

public record WorkerDispatchInput(
        WorkOrderId workOrderId,
        WorkerRunId workerRunId,
        long expectedWorkerRunVersion) {

    private static final Set<String> FIELDS = Set.of(
            WorkerDispatchAction.WORK_ORDER_ID,
            WorkerDispatchAction.WORKER_RUN_ID,
            WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION);

    public static WorkerDispatchInput from(Map<String, Object> input) {
        if (input == null || !input.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("input must contain exactly " + FIELDS);
        }
        String workOrderId = requireText(input.get(WorkerDispatchAction.WORK_ORDER_ID),
                WorkerDispatchAction.WORK_ORDER_ID);
        String workerRunId = requireText(input.get(WorkerDispatchAction.WORKER_RUN_ID),
                WorkerDispatchAction.WORKER_RUN_ID);
        Object expectedVersion = input.get(WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION);
        if (!(expectedVersion instanceof Byte
                || expectedVersion instanceof Short
                || expectedVersion instanceof Integer
                || expectedVersion instanceof Long)) {
            throw new IllegalArgumentException("expectedWorkerRunVersion must be an integer");
        }
        long version = ((Number) expectedVersion).longValue();
        if (version < 0) {
            throw new IllegalArgumentException("expectedWorkerRunVersion must not be negative");
        }
        return new WorkerDispatchInput(
                new WorkOrderId(workOrderId),
                new WorkerRunId(workerRunId),
                version);
    }

    private static String requireText(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text.trim();
    }
}
