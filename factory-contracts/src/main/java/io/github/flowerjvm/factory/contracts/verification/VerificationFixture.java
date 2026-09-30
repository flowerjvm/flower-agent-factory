package io.github.flowerjvm.factory.contracts.verification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import java.util.Objects;

/** One immutable negative verifier self-test fixture file. */
public record VerificationFixture(
        String fixtureId,
        String path,
        ArtifactReference artifactRef,
        ContentHash contentHash,
        long sizeBytes,
        String expectedRuleId) {
    public VerificationFixture {
        fixtureId = text(fixtureId, "fixtureId");
        path = text(path, "path");
        Objects.requireNonNull(artifactRef, "artifactRef");
        Objects.requireNonNull(contentHash, "contentHash");
        if (sizeBytes < 1) throw new IllegalArgumentException("sizeBytes must be positive");
        expectedRuleId = text(expectedRuleId, "expectedRuleId");
    }
    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
