package io.github.flowerjvm.factory.contracts.worker;

/** Describes who proposed a WorkOrder; it does not grant execution authority. */
public enum WorkOrderCreatorType {
    HUMAN,
    AI,
    SYSTEM,
    SERVICE
}
