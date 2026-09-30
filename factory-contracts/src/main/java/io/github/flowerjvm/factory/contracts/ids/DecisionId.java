package io.github.flowerjvm.factory.contracts.ids;

/** Identity of one immutable human decision submission. */
public record DecisionId(String value) {
    public DecisionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("decisionId must not be blank");
        }
    }
}
