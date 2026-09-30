package io.github.flowerjvm.factory.contracts.ids;

/** Stable, non-sensitive identity of the customer problem or Agent product. */
public record ProjectId(String value) {
    public ProjectId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
    }
}
