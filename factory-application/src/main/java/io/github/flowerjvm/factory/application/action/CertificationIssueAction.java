package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import java.util.Map;
import java.util.Set;

/** Stable governed boundary for issuing one exact Agent Pack Certification. */
public final class CertificationIssueAction {
    public static final String ACTION_ID = "factory.certification.issue";
    public static final String PERMISSION = "factory.certification.issue";
    public static final String INPUT_SCHEMA_ID = "factory.certification.issue.input.v1";
    public static final String OUTPUT_SCHEMA_ID = "factory.certification.issue.output.v1";
    public static final String CERTIFICATION_ID = "certificationId";
    public static final String INPUT_LOCK_MANIFEST_HASH = "inputLockManifestHash";
    public static final String EXPECTED_CERTIFICATION_VERSION = "expectedCertificationVersion";
    public static final String RESOURCE_TYPE = "certification";

    private CertificationIssueAction() {}

    public static ActionDefinition definition() {
        return new ActionDefinition(
                ACTION_ID,
                "Issue Agent Pack certification",
                "Issue evidence for one exact trusted Agent Pack certification input lock.",
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
