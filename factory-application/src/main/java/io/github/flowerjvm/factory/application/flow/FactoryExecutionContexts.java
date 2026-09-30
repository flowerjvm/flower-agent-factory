package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import java.util.Objects;

/** Identity-only Flower context mapping shared by specialized Factory production lines. */
public final class FactoryExecutionContexts {
    private FactoryExecutionContexts() {}

    /**
     * Maps the trusted production identity without including product state or authority. The
     * project remains the correlation id; Flower restores these same six values from checkpoints.
     */
    public static ExecutionContext create(
            BuildSessionId buildSessionId,
            String tenantId,
            String initiator,
            String flowRunId,
            String traceId,
            String projectId) {
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        return ExecutionContext.builder()
                .tenantId(requireText(tenantId, "tenantId"))
                .userId(requireText(initiator, "initiator"))
                .sessionId(buildSessionId.value())
                .runId(requireText(flowRunId, "flowRunId"))
                .traceId(requireText(traceId, "traceId"))
                .correlationId(requireText(projectId, "projectId"))
                .build();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
