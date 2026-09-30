package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.flow.Flow;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.worker.DuplicatePolicy;
import java.util.Objects;

/** Authenticated, idempotent entry point for a ProductLine lifecycle continuation. */
public final class FactoryContinuationFlowLauncher {
    private final BuildSessionRepository sessions;
    private final FactoryFlowRegistry flows;
    private final Engine engine;
    private final String workerName;

    public FactoryContinuationFlowLauncher(
            BuildSessionRepository sessions,
            FactoryFlowRegistry flows,
            Engine engine,
            String workerName) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.flows = Objects.requireNonNull(flows, "flows");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.workerName = requireText(workerName, "workerName");
    }

    public FlowId launch(
            TenantId trustedTenantId,
            BuildSessionId buildSessionId,
            String flowRunId,
            String traceId) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        BuildSession session = sessions.find(trustedTenantId, buildSessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "BuildSession not found in trusted tenant scope"));
        Flow flow = flows.createContinuation(
                session,
                requireText(flowRunId, "flowRunId"),
                requireText(traceId, "traceId"));
        engine.submit(workerName, flow, DuplicatePolicy.IGNORE);
        return flow.flowId();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
