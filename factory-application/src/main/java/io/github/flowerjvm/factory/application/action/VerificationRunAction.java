package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import java.util.Map;
import java.util.Set;

/** Stable governed boundary for one independent candidate verification execution. */
public final class VerificationRunAction {
    public static final String ACTION_ID = "factory.verification.run";
    public static final String PERMISSION = "factory.verification.run";
    public static final String INPUT_SCHEMA_ID = "factory.verification.run.input.v1";
    public static final String OUTPUT_SCHEMA_ID = "factory.verification.run.output.v1";
    public static final String VERIFICATION_RUN_ID = "verificationRunId";
    public static final String CANDIDATE_ID = "candidateId";
    public static final String EXPECTED_VERIFICATION_RUN_VERSION = "expectedVerificationRunVersion";
    public static final String RESOURCE_TYPE = "candidate";

    private VerificationRunAction() {}

    public static ActionDefinition definition() {
        return new ActionDefinition(
                ACTION_ID,
                "Run independent candidate verification",
                "Execute the locked candidate in the Factory verifier sandbox and persist evidence.",
                ActionEffect.WRITE,
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
