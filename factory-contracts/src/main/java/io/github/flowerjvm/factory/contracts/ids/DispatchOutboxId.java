package io.github.flowerjvm.factory.contracts.ids;

/** Identity of one durable outbound dispatch intent. */
public record DispatchOutboxId(String value) {
    public DispatchOutboxId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("dispatchOutboxId must not be blank");
        }
    }
}
