package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Durable, hash-bound question or review target transitioned only through version CAS. */
public record DecisionPoint(
        DecisionPointId decisionPointId,
        TenantId tenantId,
        BuildSessionId buildSessionId,
        String type,
        DecisionPointStatus status,
        String subjectType,
        String subjectId,
        long subjectVersion,
        ContentHash subjectHash,
        ArtifactReference questionArtifactRef,
        String optionsSchemaId,
        Set<String> requiredPermissions,
        int minimumApprovers,
        ArtifactReference policySnapshotRef,
        Instant openedAt,
        Instant dueAt,
        Optional<Instant> decidedAt,
        Optional<DecisionId> terminalDecisionId,
        long version) {

    /** Exact current PR3 release-review decision type; other decision types grant no release authority. */
    public static final String RELEASE_REVIEW_TYPE = "RELEASE_REVIEW";

    public DecisionPoint {
        Objects.requireNonNull(decisionPointId, "decisionPointId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        type = requireText(type, "type");
        Objects.requireNonNull(status, "status");
        subjectType = requireText(subjectType, "subjectType");
        subjectId = requireText(subjectId, "subjectId");
        if (subjectVersion < 0) {
            throw new IllegalArgumentException("subjectVersion must not be negative");
        }
        Objects.requireNonNull(subjectHash, "subjectHash");
        Objects.requireNonNull(questionArtifactRef, "questionArtifactRef");
        optionsSchemaId = requireText(optionsSchemaId, "optionsSchemaId");
        requiredPermissions = copyTextSet(requiredPermissions, "requiredPermissions");
        if (minimumApprovers < 1) {
            throw new IllegalArgumentException("minimumApprovers must be at least one");
        }
        Objects.requireNonNull(policySnapshotRef, "policySnapshotRef");
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(dueAt, "dueAt");
        if (dueAt.isBefore(openedAt)) {
            throw new IllegalArgumentException("dueAt must not be before openedAt");
        }
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        terminalDecisionId = Objects.requireNonNull(terminalDecisionId, "terminalDecisionId");
        if (status == DecisionPointStatus.OPEN && (decidedAt.isPresent() || terminalDecisionId.isPresent())) {
            throw new IllegalArgumentException("open DecisionPoint must not have terminal decision fields");
        }
        if (status.requiresDecisionRecord() && (decidedAt.isEmpty() || terminalDecisionId.isEmpty())) {
            throw new IllegalArgumentException("decided DecisionPoint must reference its terminal Decision");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    /** Creates the single terminal snapshot for the exact hash-bound PR3 human decision. */
    public DecisionPoint decide(Decision decision, Instant decidedAt) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decidedAt, "decidedAt");
        if (status != DecisionPointStatus.OPEN) {
            throw new IllegalStateException("only an OPEN DecisionPoint can be decided");
        }
        if (!decision.tenantId().equals(tenantId)
                || !decision.decisionPointId().equals(decisionPointId)
                || !decision.subjectHash().equals(subjectHash)) {
            throw new IllegalArgumentException("DECISION_SUBJECT_CHANGED");
        }
        if (minimumApprovers != 1) {
            throw new IllegalStateException("PR3 supports exactly one required approver");
        }
        if (decidedAt.isBefore(openedAt) || !decidedAt.isBefore(dueAt)) {
            throw new IllegalArgumentException("DECISION_EXPIRED");
        }
        DecisionPointStatus nextStatus = switch (decision.decision()) {
            case APPROVE -> DecisionPointStatus.APPROVED;
            case REQUEST_CHANGES -> DecisionPointStatus.CHANGES_REQUESTED;
            case REJECT -> DecisionPointStatus.REJECTED;
            case CANCEL -> DecisionPointStatus.CANCELLED;
        };
        return new DecisionPoint(
                decisionPointId,
                tenantId,
                buildSessionId,
                type,
                nextStatus,
                subjectType,
                subjectId,
                subjectVersion,
                subjectHash,
                questionArtifactRef,
                optionsSchemaId,
                requiredPermissions,
                minimumApprovers,
                policySnapshotRef,
                openedAt,
                dueAt,
                Optional.of(decidedAt),
                Optional.of(decision.decisionId()),
                version + 1);
    }

    /** Expires an OPEN point at its persisted deadline without inventing a Decision record. */
    public DecisionPoint expire(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        if (status != DecisionPointStatus.OPEN) {
            throw new IllegalStateException("only an OPEN DecisionPoint can expire");
        }
        if (observedAt.isBefore(dueAt)) {
            throw new IllegalArgumentException("DecisionPoint cannot expire before dueAt");
        }
        return new DecisionPoint(
                decisionPointId,
                tenantId,
                buildSessionId,
                type,
                DecisionPointStatus.EXPIRED,
                subjectType,
                subjectId,
                subjectVersion,
                subjectHash,
                questionArtifactRef,
                optionsSchemaId,
                requiredPermissions,
                minimumApprovers,
                policySnapshotRef,
                openedAt,
                dueAt,
                Optional.of(dueAt),
                Optional.empty(),
                version + 1);
    }

    private static Set<String> copyTextSet(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        values.forEach(value -> requireText(value, name + " entry"));
        return Set.copyOf(values);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
