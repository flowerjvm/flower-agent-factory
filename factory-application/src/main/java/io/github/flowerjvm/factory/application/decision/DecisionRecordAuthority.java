package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.Objects;
import java.util.Set;

/** Host-authenticated local operator grant. Never construct from decision command payload fields. */
public record DecisionRecordAuthority(TenantId tenantId, ProjectId projectId, String principal,
        Set<String> permissions, ArtifactReference authoritySnapshotRef) {
    public DecisionRecordAuthority {
        Objects.requireNonNull(tenantId, "tenantId"); DecisionRecordInput.text(tenantId.value(), 255);
        Objects.requireNonNull(projectId, "projectId"); DecisionRecordInput.text(projectId.value(), 255);
        principal = DecisionRecordInput.text(principal, 255);
        Objects.requireNonNull(permissions, "permissions");
        if (permissions.size() > 16) throw new IllegalArgumentException("DECISION_AUTHORITY_INVALID");
        permissions.forEach(value -> DecisionRecordInput.text(value, 255)); permissions = Set.copyOf(permissions);
        Objects.requireNonNull(authoritySnapshotRef, "authoritySnapshotRef"); DecisionRecordInput.text(authoritySnapshotRef.value(), 1024);
    }
}
