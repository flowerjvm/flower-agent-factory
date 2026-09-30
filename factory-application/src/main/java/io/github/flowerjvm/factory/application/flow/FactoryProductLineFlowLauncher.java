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

/** Authenticated, idempotent entry point for an installed primary ProductLine Flow. */
public final class FactoryProductLineFlowLauncher {
    private final BuildSessionRepository sessions;
    private final FactoryProductLineRegistry productLines;
    private final Engine engine;
    private final String workerName;

    public FactoryProductLineFlowLauncher(
            BuildSessionRepository sessions,
            FactoryProductLineRegistry productLines,
            Engine engine,
            String workerName) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.productLines = Objects.requireNonNull(productLines, "productLines");
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
        Flow flow = productLines.create(
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
