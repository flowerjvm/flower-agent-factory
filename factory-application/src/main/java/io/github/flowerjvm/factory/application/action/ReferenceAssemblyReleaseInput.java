package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import java.util.Map;
import java.util.Set;

/** Strict v1 release input; tenant authority remains outside the caller-controlled payload. */
public record ReferenceAssemblyReleaseInput(
        ReferenceAssemblyId referenceAssemblyId,
        ContentHash assemblyManifestHash,
        ContentHash inspectionReportHash,
        DecisionPointId releaseDecisionPointId,
        ContentHash releaseSubjectHash,
        long expectedReferenceAssemblyVersion) {

    private static final Set<String> FIELDS = Set.of(
            ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
            ReferenceAssemblyReleaseAction.ASSEMBLY_MANIFEST_HASH,
            ReferenceAssemblyReleaseAction.INSPECTION_REPORT_HASH,
            ReferenceAssemblyReleaseAction.RELEASE_DECISION_POINT_ID,
            ReferenceAssemblyReleaseAction.RELEASE_SUBJECT_HASH,
            ReferenceAssemblyReleaseAction.EXPECTED_REFERENCE_ASSEMBLY_VERSION);

    public ReferenceAssemblyReleaseInput {
        if (referenceAssemblyId == null
                || assemblyManifestHash == null
                || inspectionReportHash == null
                || releaseDecisionPointId == null
                || releaseSubjectHash == null) {
            throw new IllegalArgumentException("all Reference Assembly release locks are required");
        }
        requireBoundedIdentity(releaseDecisionPointId.value(), "releaseDecisionPointId");
        if (expectedReferenceAssemblyVersion < 0) {
            throw new IllegalArgumentException(
                    "expectedReferenceAssemblyVersion must not be negative");
        }
    }

    public static ReferenceAssemblyReleaseInput from(Map<String, Object> input) {
        if (input == null || !input.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("input must contain exactly " + FIELDS);
        }
        String assemblyId = requireText(
                input.get(ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID),
                ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID);
        String assemblyHash = requireText(
                input.get(ReferenceAssemblyReleaseAction.ASSEMBLY_MANIFEST_HASH),
                ReferenceAssemblyReleaseAction.ASSEMBLY_MANIFEST_HASH);
        String inspectionHash = requireText(
                input.get(ReferenceAssemblyReleaseAction.INSPECTION_REPORT_HASH),
                ReferenceAssemblyReleaseAction.INSPECTION_REPORT_HASH);
        String decisionPointId = requireText(
                input.get(ReferenceAssemblyReleaseAction.RELEASE_DECISION_POINT_ID),
                ReferenceAssemblyReleaseAction.RELEASE_DECISION_POINT_ID);
        String subjectHash = requireText(
                input.get(ReferenceAssemblyReleaseAction.RELEASE_SUBJECT_HASH),
                ReferenceAssemblyReleaseAction.RELEASE_SUBJECT_HASH);
        Object expectedVersion =
                input.get(ReferenceAssemblyReleaseAction.EXPECTED_REFERENCE_ASSEMBLY_VERSION);
        if (!(expectedVersion instanceof Byte
                || expectedVersion instanceof Short
                || expectedVersion instanceof Integer
                || expectedVersion instanceof Long)) {
            throw new IllegalArgumentException(
                    "expectedReferenceAssemblyVersion must be an integer");
        }
        return new ReferenceAssemblyReleaseInput(
                new ReferenceAssemblyId(assemblyId),
                new ContentHash(assemblyHash),
                new ContentHash(inspectionHash),
                new DecisionPointId(decisionPointId),
                new ContentHash(subjectHash),
                ((Number) expectedVersion).longValue());
    }

    public Map<String, Object> toMap() {
        return Map.of(
                ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                referenceAssemblyId.value(),
                ReferenceAssemblyReleaseAction.ASSEMBLY_MANIFEST_HASH,
                assemblyManifestHash.sha256(),
                ReferenceAssemblyReleaseAction.INSPECTION_REPORT_HASH,
                inspectionReportHash.sha256(),
                ReferenceAssemblyReleaseAction.RELEASE_DECISION_POINT_ID,
                releaseDecisionPointId.value(),
                ReferenceAssemblyReleaseAction.RELEASE_SUBJECT_HASH,
                releaseSubjectHash.sha256(),
                ReferenceAssemblyReleaseAction.EXPECTED_REFERENCE_ASSEMBLY_VERSION,
                expectedReferenceAssemblyVersion);
    }

    private static String requireText(Object value, String name) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " must be a bounded string");
        }
        return requireBoundedIdentity(text, name);
    }

    private static String requireBoundedIdentity(String value, String name) {
        if (value == null
                || value.isBlank()
                || value.length() > 128
                || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be a bounded non-control string");
        }
        return value;
    }
}
