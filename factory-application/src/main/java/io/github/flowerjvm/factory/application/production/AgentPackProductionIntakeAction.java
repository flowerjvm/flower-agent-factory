package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import java.util.Map;
import java.util.Set;

/** Accept an immutable production order, not approval, model execution or product release. */
public final class AgentPackProductionIntakeAction {
    public static final String ACTION_ID = "factory.agent-pack.order.submit";
    public static final String PERMISSION = ACTION_ID;
    public static final String INPUT_SCHEMA_ID = ACTION_ID + ".input.v1";
    public static final String OUTPUT_SCHEMA_ID = ACTION_ID + ".output.v1";
    public static final String RESOURCE_TYPE = "build-session";
    public static final String REQUESTER_ID = "factory-builder";
    private AgentPackProductionIntakeAction() {}

    public static ActionDefinition definition() {
        return new ActionDefinition(ACTION_ID, "Submit Agent Pack production order",
                "Atomically accept an immutable production plan and pristine BuildSession; no Flow is submitted here.",
                ActionEffect.WRITE, ActionRiskLevel.MEDIUM, Set.of(ActionRequestChannel.INTERNAL),
                Set.of(ActionProposerType.SERVICE), Set.of(PERMISSION), false, false, true,
                INPUT_SCHEMA_ID, OUTPUT_SCHEMA_ID, Map.of("resourceType", RESOURCE_TYPE));
    }
}
