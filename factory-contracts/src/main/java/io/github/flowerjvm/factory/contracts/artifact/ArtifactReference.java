package io.github.flowerjvm.factory.contracts.artifact;

/** Tenant-scoped opaque reference to immutable artifact content. */
public record ArtifactReference(String value) {
    public ArtifactReference {
        if (value == null || value.isBlank() || value.length() > 512
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("artifact reference must be bounded opaque text");
        }
    }
}
