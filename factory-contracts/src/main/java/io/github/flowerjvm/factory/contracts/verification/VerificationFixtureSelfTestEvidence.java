package io.github.flowerjvm.factory.contracts.verification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import java.util.List;
import java.util.Objects;

/** Immutable machine evidence that the host's seeded negative fixture failed for the expected rule. */
public record VerificationFixtureSelfTestEvidence(
        List<String> fixtureIds,
        List<String> expectedRuleIds,
        List<String> observedRuleIds,
        int exitCode,
        ArtifactReference machineEvidenceRef,
        ContentHash machineEvidenceHash) {
    public VerificationFixtureSelfTestEvidence {
        fixtureIds = exactTexts(fixtureIds, "fixtureIds");
        expectedRuleIds = exactTexts(expectedRuleIds, "expectedRuleIds");
        observedRuleIds = exactTexts(observedRuleIds, "observedRuleIds");
        if (exitCode == 0) throw new IllegalArgumentException("negative fixture must fail Flower Check");
        Objects.requireNonNull(machineEvidenceRef, "machineEvidenceRef");
        Objects.requireNonNull(machineEvidenceHash, "machineEvidenceHash");
    }

    private static List<String> exactTexts(List<String> values, String name) {
        values = List.copyOf(Objects.requireNonNull(values, name));
        if (values.isEmpty() || values.size() > 64
                || values.stream().anyMatch(value -> value == null || value.isBlank())
                || values.stream().distinct().count() != values.size()) {
            throw new IllegalArgumentException(name + " must contain bounded unique text");
        }
        return values;
    }
}
