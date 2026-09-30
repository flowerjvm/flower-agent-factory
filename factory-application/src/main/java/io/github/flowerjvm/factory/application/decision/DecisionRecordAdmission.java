package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationDecisionAuthority;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationOrder;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Current admission for policy/guard/executor and a separate immutable duplicate-owner identity. */
public final class DecisionRecordAdmission {
    private static final Set<String> CONTEXT_KEYS = Set.of("actor.permissions", "actor.authoritySnapshotRef", "resource.type", "resource.id", "resource.projectId");
    private final BuildSessionRepository sessions;
    private final DecisionPointRepository points;
    private final DecisionRepository decisions;
    private final Map<String, DecisionSubjectAuthority> authorities;
    private final Clock clock;
    public DecisionRecordAdmission(BuildSessionRepository sessions, DecisionPointRepository points, DecisionRepository decisions,
            Collection<? extends DecisionSubjectAuthority> authorities, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions); this.points = Objects.requireNonNull(points);
        this.decisions = Objects.requireNonNull(decisions); this.clock = Objects.requireNonNull(clock);
        Map<String, DecisionSubjectAuthority> indexed = new HashMap<>();
        for (var authority : Objects.requireNonNull(authorities)) {
            if (indexed.putIfAbsent(authority.decisionType(), authority) != null) throw invalid();
        }
        this.authorities = Map.copyOf(indexed);
    }
    public Entry current(ActionProposal proposal, ExecutionContext context) {
        var scope = scopedIdentity(proposal, context);
        var input = scope.input(); var authority = scope.authority(); var permissions = authority.permissions();
        var point = points.find(authority.tenantId(), input.decisionPointId()).orElseThrow(DecisionRecordAdmission::invalid);
        var session = sessions.find(authority.tenantId(), point.buildSessionId()).orElseThrow(DecisionRecordAdmission::invalid);
        require(point.tenantId().equals(authority.tenantId()) && point.decisionPointId().equals(input.decisionPointId())
                && session.tenantId().equals(authority.tenantId()) && session.buildSessionId().equals(point.buildSessionId())
                && session.projectId().equals(authority.projectId()) && point.subjectHash().equals(input.subjectHash()));
        var subject = authorities.get(point.type()); require(subject != null);
        Instant decisionDeadline=subject.decisionDeadline(session,point);
        requireShape(session, point);
        require(!point.dueAt().isAfter(decisionDeadline));
        require(permissions.containsAll(point.requiredPermissions()));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var entry = new Entry(authority, input, point, session, now, proposal.idempotencyKey());
        var prior = decisions.findByRequestIdempotencyKey(authority.tenantId(), point.decisionPointId(), proposal.idempotencyKey());
        if (prior.isPresent()) {
            var decision = prior.orElseThrow();
            require(decision.hasSameRequestPayload(entry.request(new DecisionId("payload-check"))));
            require(point.status().isTerminal() && point.terminalDecisionId().filter(decision.decisionId()::equals).isPresent()
                    && point.version() == input.expectedDecisionPointVersion() + 1
                    && point.decidedAt().filter(decision.createdAt()::equals).isPresent()
                    && point.status() == terminalStatus(input.outcome()));
            return entry; // A completed, identical transport retry survives normal Flow advancement/deadlines.
        }
        require(point.status() == DecisionPointStatus.OPEN && point.version() == input.expectedDecisionPointVersion()
                && session.cancellationRequestedAt().isEmpty() && !session.status().isTerminal()
                && !now.isBefore(session.updatedAt()) && !now.isBefore(point.openedAt())
                && now.isBefore(decisionDeadline) && now.isBefore(point.dueAt()));
        subject.validateCurrentSubject(authority.tenantId(), session, point);
        return entry;
    }

    /** No repository or clock reads: reservation completion must retain its original trusted scope. */
    Scope scopedIdentity(ActionProposal proposal, ExecutionContext context) {
        require(DecisionRecordAction.ACTION_ID.equals(proposal.actionId()) && proposal.requestChannel() == ActionRequestChannel.CLI
                && proposal.proposerType() == ActionProposerType.USER && Objects.equals(proposal.requesterId(), context.userId())
                && context.metadata().keySet().equals(CONTEXT_KEYS));
        DecisionRecordInput.text(context.runId(), 64); DecisionRecordInput.text(proposal.idempotencyKey(), 255);
        var input = DecisionRecordInput.from(proposal.input());
        require(DecisionRecordAction.RESOURCE_TYPE.equals(context.metadata().get("resource.type"))
                && input.decisionPointId().value().equals(context.metadata().get("resource.id")));
        Object rawPermissions = context.metadata().get("actor.permissions");
        require(rawPermissions instanceof Collection<?>);
        require(((Collection<?>) rawPermissions).size() <= 16);
        Set<String> permissions = new HashSet<>();
        for (Object permission : (Collection<?>) rawPermissions) { require(permission instanceof String); require(permissions.add((String) permission)); }
        var authority = new DecisionRecordAuthority(new TenantId(context.tenantId()),
                new ProjectId(text(context.metadata().get("resource.projectId"))), context.userId(), permissions,
                new ArtifactReference(text(context.metadata().get("actor.authoritySnapshotRef"))));
        require(permissions.contains(DecisionRecordAction.PERMISSION));
        return new Scope(authority, input);
    }
    record Scope(DecisionRecordAuthority authority, DecisionRecordInput input) {}
    private static void requireShape(BuildSession session, DecisionPoint point) {
        if (session.productLineId().equals(ProductLineId.AGENT_PACK)) {
            require(point.type().equals(DecisionPoint.RELEASE_REVIEW_TYPE)
                    && point.subjectType().equals(AgentPackReleaseReviewDecisionSubjectAuthority.SUBJECT_TYPE)
                    && point.subjectVersion() == 0
                    && point.optionsSchemaId().equals(AgentPackReleaseReviewPolicy.OPTIONS_SCHEMA_ID)
                    && point.requiredPermissions().equals(Set.of(AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION)));
        } else if (session.productLineId().equals(ProductLineId.REFERENCE_ASSEMBLY)) {
            require(point.type().equals(ReferenceAssemblyReleaseReviewService.DECISION_TYPE)
                    && point.subjectType().equals(ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE)
                    && point.optionsSchemaId().equals(ReferenceAssemblyReleaseReviewService.OPTIONS_SCHEMA_ID)
                    && point.requiredPermissions().equals(Set.of(ReferenceAssemblyReleaseReviewService.REQUIRED_PERMISSION)));
        } else if (session.productLineId().equals(IncidentApplicationOrder.PRODUCT_LINE_ID)) {
            require(point.type().equals(IncidentApplicationDecisionAuthority.DECISION_TYPE)
                    && point.subjectType().equals(IncidentApplicationDecisionAuthority.SUBJECT_TYPE)
                    && point.subjectId().equals(session.buildSessionId().value())
                    && point.optionsSchemaId().equals(IncidentApplicationDecisionAuthority.OPTIONS_SCHEMA_ID)
                    && point.requiredPermissions().equals(Set.of(IncidentApplicationDecisionAuthority.REQUIRED_PERMISSION)));
        } else throw invalid();
        require(point.minimumApprovers() == 1);
    }
    static DecisionPointStatus terminalStatus(DecisionOutcome outcome) {
        return switch (outcome) { case APPROVE -> DecisionPointStatus.APPROVED; case REJECT -> DecisionPointStatus.REJECTED;
            case REQUEST_CHANGES -> DecisionPointStatus.CHANGES_REQUESTED; case CANCEL -> DecisionPointStatus.CANCELLED; };
    }
    static String hash(String... fields) {
        try { var hash = MessageDigest.getInstance("SHA-256");
            for (String field : fields) { byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                hash.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII)); hash.update(bytes); }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static String text(Object value) { require(value instanceof String); return (String) value; }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("DECISION_RECORD_NOT_AUTHORIZED_OR_STALE"); }
    public record Entry(DecisionRecordAuthority authority, DecisionRecordInput input, DecisionPoint point,
            BuildSession session, Instant now, String requestKey) {
        Decision request(DecisionId id) { return new Decision(id, authority.tenantId(), point.decisionPointId(), requestKey,
                input.outcome(), Optional.empty(), input.reason(), authority.principal(), authority.authoritySnapshotRef(), input.subjectHash(), now); }
        DecisionRequestContext requestContext() { return new DecisionRequestContext(authority.tenantId(), now,
                authority.principal(), authority.permissions(), authority.authoritySnapshotRef()); }
    }
}
