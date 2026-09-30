package io.github.flowerjvm.factory.contracts.ids;

/** Identity of one Factory production attempt. */
public record BuildSessionId(String value) {
    public BuildSessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("buildSessionId must not be blank");
        }
    }
}
