package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.ids.*;
import java.time.Instant;
import java.util.Objects;

/** One dispatch-once bounded stage. An expired RUNNING claim is quarantined, never automatically stolen. */
public record IncidentApplicationIntent(String operationId, TenantId tenantId, BuildSessionId buildSessionId,
        Stage stage, long subjectVersion, String actionRunId, String attemptTokenHash,
        Status status, Instant createdAt, Instant deadlineAt, Instant leaseUntil,
        String claimToken, String stableCode, long version) {
    public enum Stage { BUILD, VERIFY, RELEASE }
    public enum Status { PENDING, RUNNING, EFFECT_COMMITTED, COMPLETED, FAILED, MANUAL_REVIEW, CANCELLED }
    public IncidentApplicationIntent {
        IncidentApplicationOrder.text(operationId, 128); Objects.requireNonNull(tenantId); Objects.requireNonNull(buildSessionId);
        Objects.requireNonNull(stage); IncidentApplicationOrder.text(actionRunId, 64);
        if (attemptTokenHash == null || !attemptTokenHash.matches("[0-9a-f]{64}") || subjectVersion < 0 || version < 0) throw IncidentApplicationOrder.invalid();
        Objects.requireNonNull(status); Objects.requireNonNull(createdAt); Objects.requireNonNull(deadlineAt);
        if ((claimToken == null) != (leaseUntil == null) || deadlineAt.isBefore(createdAt)) throw IncidentApplicationOrder.invalid();
    }
    public boolean terminal() { return status == Status.COMPLETED || status == Status.FAILED || status == Status.MANUAL_REVIEW || status == Status.CANCELLED; }
    public IncidentApplicationIntent change(Status next, String claim, Instant lease, String code) {
        if (terminal()) throw IncidentApplicationOrder.invalid();
        return new IncidentApplicationIntent(operationId, tenantId, buildSessionId, stage, subjectVersion, actionRunId,
                attemptTokenHash, next, createdAt, deadlineAt, lease, claim, code, version + 1);
    }
}
