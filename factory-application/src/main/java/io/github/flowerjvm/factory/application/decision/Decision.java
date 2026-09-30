package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Insert-only human decision bound to the exact subject hash that was reviewed. */
public record Decision(
        DecisionId decisionId,
        TenantId tenantId,
        DecisionPointId decisionPointId,
        String requestIdempotencyKey,
        DecisionOutcome decision,
        Optional<String> selectedOption,
        Optional<String> reason,
        String decidedBy,
        ArtifactReference deciderAuthoritySnapshotRef,
        ContentHash subjectHash,
        Instant createdAt) {

    public Decision {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(decisionPointId, "decisionPointId");
        requestIdempotencyKey = requireText(requestIdempotencyKey, "requestIdempotencyKey");
        Objects.requireNonNull(decision, "decision");
        selectedOption = requireOptionalText(selectedOption, "selectedOption");
        reason = requireOptionalText(reason, "reason");
        decidedBy = requireText(decidedBy, "decidedBy");
        Objects.requireNonNull(deciderAuthoritySnapshotRef, "deciderAuthoritySnapshotRef");
        Objects.requireNonNull(subjectHash, "subjectHash");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Compares the governed payload of one idempotent request. Transport-generated identity,
     * trusted persistence time and host-owned authority evidence are deliberately excluded.
     */
    public boolean hasSameRequestPayload(Decision other) {
        Objects.requireNonNull(other, "other");
        return tenantId.equals(other.tenantId)
                && decisionPointId.equals(other.decisionPointId)
                && requestIdempotencyKey.equals(other.requestIdempotencyKey)
                && decision == other.decision
                && selectedOption.equals(other.selectedOption)
                && reason.equals(other.reason)
                && decidedBy.equals(other.decidedBy)
                && subjectHash.equals(other.subjectHash);
    }

    private static Optional<String> requireOptionalText(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(text -> requireText(text, name));
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
