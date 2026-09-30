package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import java.util.Map;
import java.util.Set;

public final class DecisionRecordAction {
    public static final String ACTION_ID = "factory.decision.record";
    public static final String PERMISSION = ACTION_ID;
    public static final String RESOURCE_TYPE = "decision-point";
    public static final String INPUT_SCHEMA_ID = ACTION_ID + ".input.v1";
    public static final String OUTPUT_SCHEMA_ID = ACTION_ID + ".output.v1";
    private DecisionRecordAction() {}
    public static ActionDefinition definition() {
        return new ActionDefinition(ACTION_ID, "Record exact human release decision",
                "Record a trusted local operator's explicit decision; never infer approval from production success.",
                ActionEffect.WRITE, ActionRiskLevel.HIGH, Set.of(ActionRequestChannel.CLI), Set.of(ActionProposerType.USER),
                Set.of(PERMISSION), false, false, true, INPUT_SCHEMA_ID, OUTPUT_SCHEMA_ID, Map.of("resourceType", RESOURCE_TYPE));
    }
}
