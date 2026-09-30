package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.Objects;

/** Tenant/binding identity established by the host before application callback processing. */
public record TrustedWorkerCallbackContext(
        TenantId tenantId,
        String workerBindingId,
        String authenticatedPrincipalRef) {

    public TrustedWorkerCallbackContext {
        Objects.requireNonNull(tenantId, "tenantId");
        workerBindingId = requireText(workerBindingId, "workerBindingId", 128);
        authenticatedPrincipalRef = requireText(authenticatedPrincipalRef, "authenticatedPrincipalRef", 256);
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }
}
