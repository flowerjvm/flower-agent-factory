package io.github.flowerjvm.factory.contracts.ids;

/** Identity of an immutable instruction issued to a worker. */
public record WorkOrderId(String value) {
    public WorkOrderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("workOrderId must not be blank");
        }
    }
}
