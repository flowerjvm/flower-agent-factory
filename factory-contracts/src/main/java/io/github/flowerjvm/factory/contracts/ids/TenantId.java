package io.github.flowerjvm.factory.contracts.ids;

/** Trusted tenant isolation identity supplied by the host. */
public record TenantId(String value) {
    public TenantId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }
}
