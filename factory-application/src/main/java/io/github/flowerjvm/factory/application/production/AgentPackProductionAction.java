package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import java.util.Map;
import java.util.Set;

/** Prepares bounded durable work; it never grants a product approval or dispatches a model. */
public final class AgentPackProductionAction {
    public static final String ACTION_ID = "factory.agent-pack.production.prepare";
    public static final String PERMISSION = ACTION_ID;
    public static final String INPUT_SCHEMA_ID = ACTION_ID + ".input.v1";
    public static final String OUTPUT_SCHEMA_ID = ACTION_ID + ".output.v1";
    public static final String BUILD_SESSION_ID = "buildSessionId";
    public static final String EXPECTED_SESSION_VERSION = "expectedSessionVersion";
    public static final String RESOURCE_TYPE = "build-session";
    public static final String REQUESTER_ID = "factory-builder";

    private AgentPackProductionAction() {}

    public static ActionDefinition definition() {
        return new ActionDefinition(ACTION_ID, "Prepare Agent Pack production phase",
                "Atomically prepare immutable phase work or open an exact-subject review request.",
                ActionEffect.WRITE, ActionRiskLevel.MEDIUM, Set.of(ActionRequestChannel.INTERNAL),
                Set.of(ActionProposerType.SERVICE), Set.of(PERMISSION), false, false, true,
                INPUT_SCHEMA_ID, OUTPUT_SCHEMA_ID, Map.of("resourceType", RESOURCE_TYPE));
    }
}
