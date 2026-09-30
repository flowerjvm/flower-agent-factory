package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Records durable truth before a caller optionally uses a Flower signal as a wake-up hint. */
public final class DecisionRecordingService {
    private final BuildSessionRepository buildSessions;
    private final DecisionPointRepository decisionPoints;
    private final DecisionRepository decisions;
    private final DecisionRecordingTransaction transaction;
    private final Map<String, DecisionSubjectAuthority> subjectAuthorities;

    public DecisionRecordingService(
            BuildSessionRepository buildSessions,
            DecisionPointRepository decisionPoints,
            DecisionRepository decisions,
            DecisionRecordingTransaction transaction) {
        this(
                buildSessions,
                decisionPoints,
                decisions,
                transaction,
                List.of(new AgentPackReleaseReviewDecisionSubjectAuthority()));
    }

    public DecisionRecordingService(
            BuildSessionRepository buildSessions,
            DecisionPointRepository decisionPoints,
            DecisionRepository decisions,
            DecisionRecordingTransaction transaction,
            Collection<? extends DecisionSubjectAuthority> subjectAuthorities) {
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.decisionPoints = Objects.requireNonNull(decisionPoints, "decisionPoints");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.subjectAuthorities = indexAuthorities(subjectAuthorities);
    }

    public DecisionRecordingResult record(Decision requested, DecisionRequestContext requestContext) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(requestContext, "requestContext");
        if (!requested.tenantId().equals(requestContext.tenantId())) {
            throw new IllegalArgumentException("DECISION_UNAUTHORIZED");
        }
        DecisionPoint current = decisionPoints
                .find(requestContext.tenantId(), requested.decisionPointId())
                .orElseThrow(() -> new IllegalArgumentException("DecisionPoint not found in tenant scope"));
        authorize(current, requested, requestContext);
        DecisionSubjectAuthority subjectAuthority = subjectAuthority(current.type());
        Decision canonical = withTrustedRequestFacts(requested, requestContext);

        // A transport retry must remain idempotent after the Flow has consumed the first Decision
        // and advanced the mutable BuildSession. Authorization and tenant scope are still checked
        // above, while a changed payload never receives the stored canonical result.
        Optional<Decision> existing = decisions.findByRequestIdempotencyKey(
                requestContext.tenantId(), requested.decisionPointId(), requested.requestIdempotencyKey());
        if (existing.isPresent()) {
            return existing.get().hasSameRequestPayload(canonical)
                    ? DecisionRecordingResult.duplicate(existing.get())
                    : DecisionRecordingResult.conflict();
        }

        BuildSession currentSession = buildSessions
                .find(requestContext.tenantId(), current.buildSessionId())
                .orElseThrow(() -> new IllegalArgumentException("BuildSession not found in tenant scope"));
        subjectAuthority.validateCurrentSubject(
                requestContext.tenantId(), currentSession, current);
        if (!requestContext.currentTime().isBefore(subjectAuthority.decisionDeadline(currentSession,current))
                || current.dueAt().isAfter(subjectAuthority.decisionDeadline(currentSession,current))) {
            throw new IllegalArgumentException("DECISION_REVIEW_WINDOW_EXPIRED_OR_INVALID");
        }
        if (current.status().isTerminal()) {
            return DecisionRecordingResult.conflict();
        }

        DecisionPoint decided = current.decide(canonical, requestContext.currentTime());
        DecisionRecordingDisposition disposition = transaction.record(currentSession, current, canonical, decided);
        if (disposition == DecisionRecordingDisposition.APPLIED) {
            return DecisionRecordingResult.applied(canonical);
        }
        if (disposition == DecisionRecordingDisposition.CONFLICT) {
            return DecisionRecordingResult.conflict();
        }
        return decisions.findByRequestIdempotencyKey(
                        canonical.tenantId(), canonical.decisionPointId(), canonical.requestIdempotencyKey())
                .filter(canonical::hasSameRequestPayload)
                .map(DecisionRecordingResult::duplicate)
                .orElseGet(DecisionRecordingResult::conflict);
    }

    private static void authorize(
            DecisionPoint decisionPoint,
            Decision requested,
            DecisionRequestContext requestContext) {
        if (!requested.decidedBy().equals(requestContext.authenticatedPrincipal())
                || !requestContext.permissions().containsAll(decisionPoint.requiredPermissions())) {
            throw new IllegalArgumentException("DECISION_UNAUTHORIZED");
        }
    }

    private static Decision withTrustedRequestFacts(
            Decision requested,
            DecisionRequestContext requestContext) {
        return new Decision(
                requested.decisionId(),
                requested.tenantId(),
                requested.decisionPointId(),
                requested.requestIdempotencyKey(),
                requested.decision(),
                requested.selectedOption(),
                requested.reason(),
                requestContext.authenticatedPrincipal(),
                requestContext.authoritySnapshotRef(),
                requested.subjectHash(),
                requestContext.currentTime());
    }

    private DecisionSubjectAuthority subjectAuthority(String decisionType) {
        DecisionSubjectAuthority authority = subjectAuthorities.get(decisionType);
        if (authority == null) {
            throw new IllegalArgumentException("DECISION_SUBJECT_AUTHORITY_UNAVAILABLE");
        }
        return authority;
    }

    private static Map<String, DecisionSubjectAuthority> indexAuthorities(
            Collection<? extends DecisionSubjectAuthority> authorities) {
        Objects.requireNonNull(authorities, "subjectAuthorities");
        Map<String, DecisionSubjectAuthority> indexed = new LinkedHashMap<>();
        for (DecisionSubjectAuthority authority : authorities) {
            Objects.requireNonNull(authority, "subjectAuthorities entry");
            String decisionType = authority.decisionType();
            if (decisionType == null || decisionType.isBlank()) {
                throw new IllegalArgumentException(
                        "DecisionSubjectAuthority decisionType must not be blank");
            }
            if (indexed.putIfAbsent(decisionType, authority) != null) {
                throw new IllegalArgumentException(
                        "DECISION_SUBJECT_AUTHORITY_DUPLICATE: " + decisionType);
            }
        }
        return Map.copyOf(indexed);
    }
}
