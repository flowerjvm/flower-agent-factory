package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionEffect;
import io.github.flowerjvm.flower.action.runtime.action.ActionRiskLevel;
import java.util.Map;
import java.util.Set;

/** Stable governed boundary for packaging one exact Reference Assembly release. */
public final class ReferenceAssemblyReleaseAction {
    public static final String ACTION_ID = "factory.release.package";
    public static final String PERMISSION = "factory.release.package";
    public static final String INPUT_SCHEMA_ID = "factory.release.package.input.v1";
    public static final String OUTPUT_SCHEMA_ID = "factory.release.package.output.v1";
    public static final String REFERENCE_ASSEMBLY_ID = "referenceAssemblyId";
    public static final String ASSEMBLY_MANIFEST_HASH = "assemblyManifestHash";
    public static final String INSPECTION_REPORT_HASH = "inspectionReportHash";
    public static final String RELEASE_DECISION_POINT_ID = "releaseDecisionPointId";
    public static final String RELEASE_SUBJECT_HASH = "releaseSubjectHash";
    public static final String EXPECTED_REFERENCE_ASSEMBLY_VERSION =
            "expectedReferenceAssemblyVersion";
    public static final String RESOURCE_TYPE = "reference-assembly";

    private ReferenceAssemblyReleaseAction() {}

    public static ActionDefinition definition() {
        return new ActionDefinition(
                ACTION_ID,
                "Package Reference Assembly release",
                "Package the exact inspected Reference Assembly under its bound release review.",
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
