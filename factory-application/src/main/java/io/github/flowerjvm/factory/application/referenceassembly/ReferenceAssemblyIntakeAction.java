package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import java.util.Map;
import java.util.Set;

/** Accept an exact certified component for graph assembly, not a human approval or product release. */
public final class ReferenceAssemblyIntakeAction {
    public static final String ACTION_ID = "factory.reference-assembly.order.submit";
    public static final String PERMISSION = ACTION_ID;
    public static final String REQUESTER_ID = "factory-builder";
    public static final String RESOURCE_TYPE = "build-session";
    public static final String INPUT_SCHEMA_ID = ACTION_ID + ".input.v1";
    public static final String OUTPUT_SCHEMA_ID = ACTION_ID + ".output.v1";
    private ReferenceAssemblyIntakeAction() {}

    public static ActionDefinition definition() {
        return new ActionDefinition(ACTION_ID, "Submit Reference Assembly order",
                "Atomically accept one exact certified Maintenance component and pristine graph-assembly session; no Flow is submitted here.",
                ActionEffect.WRITE, ActionRiskLevel.MEDIUM, Set.of(ActionRequestChannel.INTERNAL),
                Set.of(ActionProposerType.SERVICE), Set.of(PERMISSION), false, false, true,
                INPUT_SCHEMA_ID, OUTPUT_SCHEMA_ID, Map.of("resourceType", RESOURCE_TYPE));
    }
}
