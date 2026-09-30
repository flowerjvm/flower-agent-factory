package io.github.flowerjvm.factory.contracts.ids;

/** Identity of one durable question or hash-bound review target. */
public record DecisionPointId(String value) {
    public DecisionPointId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("decisionPointId must not be blank");
        }
    }
}
