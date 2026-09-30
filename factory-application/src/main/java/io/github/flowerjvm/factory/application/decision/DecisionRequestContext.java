package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Trusted host facts used to authorize and timestamp one human-decision request. */
public record DecisionRequestContext(
        TenantId tenantId,
        Instant currentTime,
        String authenticatedPrincipal,
        Set<String> permissions,
        ArtifactReference authoritySnapshotRef) {

    public DecisionRequestContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(currentTime, "currentTime");
        authenticatedPrincipal = requireText(authenticatedPrincipal, "authenticatedPrincipal");
        Objects.requireNonNull(permissions, "permissions");
        permissions.forEach(permission -> requireText(permission, "permissions entry"));
        permissions = Set.copyOf(permissions);
        Objects.requireNonNull(authoritySnapshotRef, "authoritySnapshotRef");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
