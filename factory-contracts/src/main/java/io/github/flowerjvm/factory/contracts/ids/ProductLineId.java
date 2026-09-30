package io.github.flowerjvm.factory.contracts.ids;

/** Stable identity of one specialized Factory production line. */
public record ProductLineId(String value) {
    public static final ProductLineId AGENT_PACK = new ProductLineId("agent-pack");
    public static final ProductLineId REFERENCE_ASSEMBLY = new ProductLineId("reference-assembly");
    public static final ProductLineId INCIDENT_APPLICATION = new ProductLineId("incident-application");

    public ProductLineId {
        if (value == null
                || value.length() > 128
                || !value.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")) {
            throw new IllegalArgumentException("productLineId must be a bounded lowercase stable id");
        }
    }
}
