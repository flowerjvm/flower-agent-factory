package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.action.*;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import io.github.flowerjvm.flower.action.runtime.guard.*;
import io.github.flowerjvm.flower.action.runtime.policy.*;
import io.github.flowerjvm.flower.action.runtime.run.*;
import io.github.flowerjvm.flower.action.runtime.validation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationActions.require;
import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationArtifacts.*;

/**
 * One bounded review-window renewal, not a Decision, product rebuild, certification or release.
 * The protected host entry authenticates its human operator and supplies the authority snapshot.
 * JDBC owns the one-shot version fence and rechecks all immutable product and current component evidence.
 */
public final class IncidentApplicationReviewRenewalAction
        implements ActionInputValidator, PolicyGate, PreExecutionGuard, DuplicateVisibilityScopeResolver {
    public static final String ID = "factory.incident-application.review.renew";
    public static final String RESOURCE = IncidentApplicationActions.RESOURCE;
    public static final String SUCCESS_CODE = "INCIDENT_APPLICATION_REVIEW_RENEWED";
    private static final Set<String> INPUT_KEYS = Set.of("buildSessionId", "projectId", "expectedVersion",
            "previousDecisionPointId", "subjectHash", "deadlineAt");
    private static final Set<String> CONTEXT_KEYS = Set.of("actor.permissions", "actor.authoritySnapshotRef", "resource.type", "resource.id", "resource.projectId");
    private final IncidentApplicationLedger ledger;
    private final RunStore runs;
    private final Clock clock;

    public IncidentApplicationReviewRenewalAction(IncidentApplicationLedger ledger, RunStore runs, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger); this.runs = Objects.requireNonNull(runs); this.clock = Objects.requireNonNull(clock);
    }

    public static ActionDefinition definition() {
        return new ActionDefinition(ID, "Renew exact incident application review", "Open one bounded review window for the unchanged inspected product",
                ActionEffect.WRITE, ActionRiskLevel.MEDIUM, Set.of(ActionRequestChannel.CLI), Set.of(ActionProposerType.USER),
                Set.of(ID), false, false, true, ID + ".input.v1", ID + ".output.v1", Map.of("resourceType", RESOURCE));
    }

    @Override public ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        try {
            require(ID.equals(proposal.actionId()) && definition().equals(definition)); shape(proposal.input());
            return ValidationResult.ok();
        } catch (RuntimeException invalid) { return ValidationResult.invalid("INCIDENT_APPLICATION_RENEWAL_INPUT_INVALID"); }
    }

    @Override public PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        try {
            require(definition().equals(definition)); authority(proposal, context); current(proposal, context, false);
            return PolicyDecision.allow();
        } catch (RuntimeException denied) { return PolicyDecision.deny("INCIDENT_APPLICATION_RENEWAL_NOT_AUTHORIZED_OR_STALE"); }
    }

    @Override public PreExecutionDecision check(ActionProposal proposal, ActionDefinition definition, ExecutionContext context, PolicyDecision policyDecision) {
        try {
            require(definition().equals(definition)); authority(proposal, context); current(proposal, context, true);
            return PreExecutionDecision.allow();
        } catch (RuntimeException denied) {
            return PreExecutionDecision.deny("INCIDENT_APPLICATION_RENEWAL_STALE", "Review renewal authority is missing or stale");
        }
    }

    @Override public String resolve(ActionProposal proposal, ExecutionContext context) {
        authority(proposal, context);
        // Policy already checked this exact trusted resource. Keep scope stable after v4 -> v5.
        return RESOURCE + ":review-renewal:" + hash(canonical(Map.of("principal",context.userId(),"project", context.metadata().get("resource.projectId"),
                "session", context.metadata().get("resource.id"))));
    }

    private void current(ActionProposal proposal, ExecutionContext context, boolean executing) {
        ledger.requireRenewal(new TenantId(context.tenantId()),
                new BuildSessionId((String)proposal.input().get("buildSessionId")), proposal.input(), executing);
    }

    public static void authority(ActionProposal proposal, ExecutionContext context) {
        require(proposal != null && context != null && ID.equals(proposal.actionId())
                && proposal.requestChannel() == ActionRequestChannel.CLI && proposal.proposerType() == ActionProposerType.USER
                && Objects.equals(proposal.requesterId(),context.userId()) && !IncidentApplicationActions.OWNER.equals(context.userId()));
        text(context.userId(),255);
        shape(proposal.input()); text(context.tenantId(), 128); text(context.runId(), 64); text(context.traceId(), 255);
        metadata(context.metadata(), proposal.input());
        require(key(new TenantId(context.tenantId()), proposal.input()).equals(proposal.idempotencyKey()));
    }

    private static void metadata(Map<String,Object> metadata, Map<String,Object> input) {
        require(metadata != null && metadata.keySet().equals(CONTEXT_KEYS) && RESOURCE.equals(metadata.get("resource.type"))
                && input.get("buildSessionId").equals(metadata.get("resource.id")) && input.get("projectId").equals(metadata.get("resource.projectId"))
                && metadata.get("actor.permissions") instanceof Collection<?> permissions && permissions.size() == 1 && permissions.contains(ID));
        new ArtifactReference(text(metadata.get("actor.authoritySnapshotRef"),512));
    }

    public SynchronousActionExecutor executor() {
        return new SynchronousActionExecutor() {
            @Override public ActionDefinition definition() { return IncidentApplicationReviewRenewalAction.definition(); }
            @Override public ActionExecutionResult execute(ActionExecutionContext context) {
                try {
                    require(definition().equals(context.definition())); authority(context.proposal(), context.executionContext());
                    require(Arrays.equals(canonical(context.input()), canonical(context.proposal().input())));
                    var owner = runs.find(context.executionContext().runId()).orElseThrow(IncidentApplicationOrder::invalid);
                    requireExecutingOwner(owner, context);
                    var tenant = new TenantId(context.executionContext().tenantId());
                    var session = new BuildSessionId((String)context.input().get("buildSessionId"));
                    // Short bounded domain transaction, invoked on the host control thread, never a Flower tick.
                    var renewed = ledger.renewReview(tenant, session, context.input(), owner, clock.instant().truncatedTo(ChronoUnit.MILLIS));
                    require(renewed.tenantId().equals(tenant) && renewed.buildSessionId().equals(session)
                            && renewed.actionRunId().equals(owner.runId()) && renewed.requestKey().equals(owner.duplicateKey())
                            && renewed.attemptTokenHash().equals(hash(owner.attemptToken()))
                            && Arrays.equals(canonical(context.input()), canonical(renewalInput(renewed, (String)context.input().get("projectId")))));
                    return result(renewed);
                } catch (RuntimeException uncertain) {
                    return ActionExecutionResult.manualReviewFailure("INCIDENT_APPLICATION_RENEWAL_UNCERTAIN",
                            "Review renewal requires reconciliation; no approval or release authority was granted");
                }
            }
        };
    }

    private static void requireExecutingOwner(ActionRun owner, ActionExecutionContext context) {
        var execution = context.executionContext(); var proposal = context.proposal();
        require(owner != null && owner.status() == ActionRunStatus.RUNNING && owner.result() == null
                && owner.runId().equals(execution.runId()) && owner.tenantId().equals(execution.tenantId())
                && owner.userId().equals(execution.userId()) && owner.traceId().equals(execution.traceId())
                && owner.actionId().equals(ID) && owner.proposalId().equals(proposal.proposalId())
                && owner.requesterId().equals(proposal.requesterId()) && owner.requestChannel() == ActionRequestChannel.CLI
                && owner.proposerType() == ActionProposerType.USER && owner.duplicateKey().equals(proposal.idempotencyKey())
                && owner.attemptToken() != null && owner.attemptToken().equals(context.attemptToken())
                && owner.approvalId().isEmpty() && owner.dueAt() == null && owner.externalOperationId().isEmpty()
                && owner.externalOperationMetadata().isEmpty()
                && Arrays.equals(canonical(owner.input()), canonical(context.input()))
                && sameMetadata(owner.contextMetadata(), execution.metadata(), context.input()));
        text(owner.attemptToken(), 255);
    }

    private static boolean sameMetadata(Map<String,Object> stored, Map<String,Object> submitted, Map<String,Object> input) {
        metadata(stored,input); metadata(submitted,input);
        // JDBC JSON restores Set permissions as List; their exact singleton membership is already proven.
        return CONTEXT_KEYS.stream().filter(key -> !key.equals("actor.permissions"))
                .allMatch(key -> Objects.equals(stored.get(key),submitted.get(key)));
    }

    /** Immutable owner proof only: status/result are checked by the ledger at its RUNNING or SUCCEEDED boundary. */
    public static void requireOwner(ActionRun run, IncidentApplicationProduct product, IncidentApplicationReviewRenewal renewal) {
        Objects.requireNonNull(product); Objects.requireNonNull(renewal);
        var order = product.order(); var expectedInput = renewalInput(renewal, order.projectId().value());
        require(run != null && order.tenantId().equals(renewal.tenantId()) && order.buildSessionId().equals(renewal.buildSessionId())
                && order.deadlineAt().equals(renewal.originalDeadlineAt()) && Objects.equals(product.releaseSubject(), renewal.subject())
                && renewal.inspectedVersion() == 4 && renewal.reviewedProductVersion() == 5
                && run.runId().equals(renewal.actionRunId()) && run.tenantId().equals(renewal.tenantId().value())
                && ID.equals(run.actionId()) && Objects.equals(run.userId(),run.requesterId()) && !IncidentApplicationActions.OWNER.equals(run.userId())
                && run.requestChannel() == ActionRequestChannel.CLI && run.proposerType() == ActionProposerType.USER
                && run.attemptToken() != null && hash(run.attemptToken()).equals(renewal.attemptTokenHash())
                && key(renewal.tenantId(), expectedInput).equals(renewal.requestKey()) && renewal.requestKey().equals(run.duplicateKey())
                && run.approvalId().isEmpty() && run.dueAt() == null && run.externalOperationId().isEmpty() && run.externalOperationMetadata().isEmpty()
                && Arrays.equals(canonical(run.input()), canonical(expectedInput)));
        text(run.userId(),255); text(run.runId(),64); text(run.traceId(),255); text(run.attemptToken(),255); metadata(run.contextMetadata(), expectedInput);
    }

    public static ActionExecutionResult result(IncidentApplicationReviewRenewal renewal) {
        Objects.requireNonNull(renewal);
        return new ActionExecutionResult(ActionExecutionStatus.SUCCEEDED, SUCCESS_CODE,
                "A new review window was opened for the same inspected subject; approval remains separate",
                Map.of("decisionPointId", renewal.decisionPointId().value(), "subjectHash", renewal.subject().hash().sha256()), RetryDisposition.NEVER);
    }

    public static Map<String,Object> input(IncidentApplicationProduct product, Instant newDeadline) {
        require(product != null && product.status() == IncidentApplicationProduct.Status.REVIEW && product.version() == 4
                && product.decisionPointId() != null && product.releaseSubject() != null);
        var input = Map.<String,Object>of("buildSessionId", product.order().buildSessionId().value(), "projectId", product.order().projectId().value(),
                "expectedVersion", product.version(), "previousDecisionPointId", product.decisionPointId().value(),
                "subjectHash", product.releaseSubject().hash().sha256(), "deadlineAt", Objects.requireNonNull(newDeadline).toString());
        shape(input); return input;
    }

    private static Map<String,Object> renewalInput(IncidentApplicationReviewRenewal renewal, String projectId) {
        var input = Map.<String,Object>of("buildSessionId", renewal.buildSessionId().value(), "projectId", projectId,
                "expectedVersion", renewal.inspectedVersion(), "previousDecisionPointId", renewal.previousDecisionPointId().value(),
                "subjectHash", renewal.subject().hash().sha256(), "deadlineAt", renewal.deadlineAt().toString());
        shape(input); return input;
    }

    /** Stable request identity binds tenant and every immutable field, including the exact new deadline. */
    public static String key(TenantId tenant, Map<String,Object> input) {
        Objects.requireNonNull(tenant); text(tenant.value(),128); shape(input);
        return "incident-review-renew-" + hash(canonical(Map.of("tenantId", tenant.value(), "input", input)));
    }

    public static void shape(Map<String,Object> input) {
        require(input != null && input.keySet().equals(INPUT_KEYS));
        text(input.get("buildSessionId"),128); text(input.get("projectId"),128); text(input.get("previousDecisionPointId"),255);
        require(IncidentApplicationActions.version(input.get("expectedVersion")) == 4);
        require(text(input.get("subjectHash"),64).matches("[0-9a-f]{64}"));
        String raw = text(input.get("deadlineAt"),64); Instant deadline = Instant.parse(raw);
        require(raw.equals(deadline.toString()) && deadline.equals(deadline.truncatedTo(ChronoUnit.MILLIS)));
    }

    private static String text(Object value, int maximum) {
        require(value instanceof String); return IncidentApplicationOrder.text((String)value, maximum);
    }
}
