package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.action.*;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import io.github.flowerjvm.flower.action.runtime.guard.*;
import io.github.flowerjvm.flower.action.runtime.policy.*;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.validation.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Concrete line controls reused by its three registered actions, never a global policy fallback. */
public final class IncidentApplicationActions implements ActionInputValidator, PolicyGate, PreExecutionGuard, DuplicateVisibilityScopeResolver {
    public static final String INTAKE = "factory.incident-application.order.submit";
    public static final String STAGE = "factory.incident-application.stage.run";
    public static final String RELEASE = "factory.incident-application.release";
    public static final String OWNER = "factory-incident-application";
    public static final String RESOURCE = "incident-application";
    public static final Set<String> IDS = Set.of(INTAKE, STAGE, RELEASE);
    private static final Set<String> CONTEXT_KEYS = Set.of("actor.permissions", "resource.type", "resource.id", "resource.projectId");
    private static final Set<String> INTAKE_KEYS = Set.of("buildSessionId", "projectId", "variant", "certificationId", "candidateHash",
            "certificationManifestRef", "certificationManifestHash", "deadlineAt");
    private static final Set<String> STAGE_KEYS = Set.of("buildSessionId", "projectId", "stage", "expectedVersion", "subjectHash");
    private final IncidentApplicationLedger ledger;
    private final IncidentApplicationIntake intake;
    private final RunStore runs;
    private final Clock clock;
    public IncidentApplicationActions(IncidentApplicationLedger ledger, IncidentApplicationIntake intake, RunStore runs, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger); this.intake = Objects.requireNonNull(intake);
        this.runs = Objects.requireNonNull(runs); this.clock = Objects.requireNonNull(clock);
    }
    public static ActionDefinition definition(String id) {
        if (!IDS.contains(id)) throw IncidentApplicationOrder.invalid();
        return new ActionDefinition(id, "Incident application production", "Produce or release the exact bounded application order",
                ActionEffect.WRITE, ActionRiskLevel.MEDIUM, Set.of(ActionRequestChannel.INTERNAL), Set.of(ActionProposerType.SERVICE),
                Set.of(id), false, false, true, id + ".input.v1", id + ".output.v1", Map.of("resourceType", RESOURCE));
    }
    @Override public ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        try { require(IDS.contains(proposal.actionId()) && proposal.actionId().equals(definition.actionId())); shape(proposal.actionId(), proposal.input()); return ValidationResult.ok(); }
        catch (RuntimeException invalid) { return ValidationResult.invalid("INCIDENT_APPLICATION_INPUT_INVALID"); }
    }
    @Override public PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        try { require(proposal.actionId().equals(definition.actionId())); authority(proposal, context); current(proposal, context, false); return PolicyDecision.allow(); }
        catch (RuntimeException denied) { return PolicyDecision.deny("INCIDENT_APPLICATION_NOT_AUTHORIZED_OR_STALE"); }
    }
    @Override public PreExecutionDecision check(ActionProposal proposal, ActionDefinition definition, ExecutionContext context, PolicyDecision policyDecision) {
        try { require(proposal.actionId().equals(definition.actionId())); authority(proposal, context); current(proposal, context, true); return PreExecutionDecision.allow(); }
        catch (RuntimeException denied) { return PreExecutionDecision.deny("INCIDENT_APPLICATION_STALE", "Production authority is missing or stale"); }
    }
    @Override public String resolve(ActionProposal proposal, ExecutionContext context) {
        authority(proposal, context);
        // Immutable trusted metadata survives domain advancement and reservation completion.
        return RESOURCE + ":" + IncidentApplicationArtifacts.hash(IncidentApplicationArtifacts.canonical(Map.of(
                "project",context.metadata().get("resource.projectId"),"session",context.metadata().get("resource.id"))));
    }
    private void current(ActionProposal proposal, ExecutionContext context, boolean executing) {
        var tenant = new TenantId(context.tenantId()); var input = proposal.input();
        if (INTAKE.equals(proposal.actionId())) { intake.requireSelection(tenant, proposal.idempotencyKey(), input, executing); return; }
        var product = ledger.find(tenant, new BuildSessionId(text(input.get("buildSessionId")))).orElseThrow(IncidentApplicationOrder::invalid);
        require(product.order().projectId().value().equals(input.get("projectId")));
        var stage = IncidentApplicationIntent.Stage.valueOf(text(input.get("stage")));
        // Revocation/expiry applies before cached duplicate visibility, not only before a new effect.
        ledger.requireCurrentComponent(product.order(),now());
        ledger.requirePriorStages(tenant,product.order().buildSessionId(),stage);
        if(stage==IncidentApplicationIntent.Stage.RELEASE) ledger.requireReleaseApproval(tenant,product.order().buildSessionId());
        String subject = stage == IncidentApplicationIntent.Stage.RELEASE
                ? Objects.requireNonNull(product.releaseSubject()).hash().sha256() : product.workOrder().workOrder().hash().sha256();
        require(subject.equals(input.get("subjectHash")) && version(input.get("expectedVersion")) <= product.version());
        require(proposal.idempotencyKey().equals(stageKey(product, stage, version(input.get("expectedVersion")))));
        if (executing) {
            require(product.version() == version(input.get("expectedVersion")) && !product.terminal() && now().isBefore(ledger.executionDeadline(product)));
            if(stage!=IncidentApplicationIntent.Stage.RELEASE) require(now().isBefore(product.order().deadlineAt()));
            require(stage == IncidentApplicationIntent.Stage.BUILD && product.status() == IncidentApplicationProduct.Status.ACCEPTED
                    || stage == IncidentApplicationIntent.Stage.VERIFY && product.status() == IncidentApplicationProduct.Status.BUILT
                    || stage == IncidentApplicationIntent.Stage.RELEASE && product.status() == IncidentApplicationProduct.Status.REVIEW);
        }
    }
    public static void authority(ActionProposal proposal, ExecutionContext context) {
        require(IDS.contains(proposal.actionId()) && proposal.requestChannel() == ActionRequestChannel.INTERNAL
                && proposal.proposerType() == ActionProposerType.SERVICE && OWNER.equals(proposal.requesterId()) && OWNER.equals(context.userId())
                && context.metadata().keySet().equals(CONTEXT_KEYS) && RESOURCE.equals(context.metadata().get("resource.type"))
                // JDBC restores immutable set metadata as a JSON collection. Compare its exact
                // membership, not the Java collection implementation, including completion replay.
                && context.metadata().get("actor.permissions") instanceof Collection<?> permissions
                && permissions.size()==1 && permissions.contains(proposal.actionId())
                && proposal.input().get("buildSessionId").equals(context.metadata().get("resource.id"))
                && proposal.input().get("projectId").equals(context.metadata().get("resource.projectId")));
        IncidentApplicationOrder.text(context.tenantId(),128); IncidentApplicationOrder.text(context.runId(),64);
        IncidentApplicationOrder.text(context.traceId(),255); IncidentApplicationOrder.text(proposal.idempotencyKey(),255);
        shape(proposal.actionId(), proposal.input());
    }
    public SynchronousActionExecutor intakeExecutor() {
        return new SynchronousActionExecutor() {
            @Override public ActionDefinition definition() { return IncidentApplicationActions.definition(INTAKE); }
            @Override public ActionExecutionResult execute(ActionExecutionContext context) {
                try {
                    authority(context.proposal(), context.executionContext());
                    var owner = runs.find(context.executionContext().runId()).orElseThrow(IncidentApplicationOrder::invalid);
                    var product = intake.accept(new TenantId(context.executionContext().tenantId()), context.proposal().idempotencyKey(), context.input(), owner);
                    return ActionExecutionResult.succeeded(Map.of("buildSessionId", product.order().buildSessionId().value(), "status", product.status().name()));
                } catch (RuntimeException uncertain) { return ActionExecutionResult.manualReviewFailure("INCIDENT_APPLICATION_INTAKE_UNCERTAIN", "Intake requires reconciliation"); }
            }
        };
    }
    public DeferredActionExecutor stageExecutor() { return deferred(STAGE); }
    public DeferredActionExecutor releaseExecutor() { return deferred(RELEASE); }
    private DeferredActionExecutor deferred(String id) {
        return new DeferredActionExecutor() {
            @Override public ActionDefinition definition() { return IncidentApplicationActions.definition(id); }
            @Override public ActionDispatch.Awaiting dispatchDeferred(ActionExecutionContext context) {
                authority(context.proposal(), context.executionContext());
                var owner = runs.find(context.executionContext().runId()).orElseThrow(IncidentApplicationOrder::invalid);
                var operation = ledger.prepare(new TenantId(context.executionContext().tenantId()),
                        new BuildSessionId(text(context.input().get("buildSessionId"))),
                        IncidentApplicationIntent.Stage.valueOf(text(context.input().get("stage"))), version(context.input().get("expectedVersion")), owner, now());
                return ActionDispatch.awaiting(operation.operationId(), operation.deadlineAt(), Map.of("productLineId", RESOURCE));
            }
        };
    }
    public static Map<String,Object> stageInput(IncidentApplicationProduct product, IncidentApplicationIntent.Stage stage) {
        return Map.of("buildSessionId", product.order().buildSessionId().value(), "projectId", product.order().projectId().value(), "stage", stage.name(),
                "expectedVersion", product.version(), "subjectHash", stage == IncidentApplicationIntent.Stage.RELEASE
                        ? Objects.requireNonNull(product.releaseSubject()).hash().sha256() : product.workOrder().workOrder().hash().sha256());
    }
    public static String stageKey(IncidentApplicationProduct product, IncidentApplicationIntent.Stage stage, long version) {
        return "incident-stage-" + IncidentApplicationArtifacts.hash(product.order().tenantId().value() + "\n" + product.order().buildSessionId().value() + "\n" + stage + "\n" + version);
    }
    /** Immutable persisted owner proof, shared by completion, JDBC commit and exported-product reads. */
    public static void requireStageOwner(ActionRun run,IncidentApplicationProduct product,IncidentApplicationIntent intent) {
        var order=product.order(); String action=intent.stage()==IncidentApplicationIntent.Stage.RELEASE?RELEASE:STAGE;
        require(run!=null && run.runId().equals(intent.actionRunId()) && run.tenantId().equals(order.tenantId().value())
                && run.actionId().equals(action) && OWNER.equals(run.userId()) && OWNER.equals(run.requesterId())
                && run.requestChannel()==ActionRequestChannel.INTERNAL && run.proposerType()==ActionProposerType.SERVICE
                && run.traceId()!=null && !run.traceId().isBlank() && run.attemptToken()!=null
                && IncidentApplicationArtifacts.hash(run.attemptToken()).equals(intent.attemptTokenHash()));
        var metadata=run.contextMetadata();
        require(metadata.keySet().equals(CONTEXT_KEYS) && RESOURCE.equals(metadata.get("resource.type"))
                && order.buildSessionId().value().equals(metadata.get("resource.id")) && order.projectId().value().equals(metadata.get("resource.projectId"))
                && metadata.get("actor.permissions") instanceof Collection<?> permissions && permissions.size()==1 && permissions.contains(action));
        var input=new LinkedHashMap<>(stageInput(product,intent.stage())); input.put("expectedVersion",intent.subjectVersion());
        require(run.duplicateKey().equals(stageKey(product,intent.stage(),intent.subjectVersion()))
                && Arrays.equals(IncidentApplicationArtifacts.canonical(run.input()),IncidentApplicationArtifacts.canonical(input)));
    }
    public static void requireDispatchedOwner(ActionRun run,IncidentApplicationProduct product,IncidentApplicationIntent intent) {
        requireStageOwner(run,product,intent);
        require(intent.operationId().equals(run.externalOperationId()) && intent.deadlineAt().equals(run.dueAt())
                && Map.of("productLineId",RESOURCE).equals(run.externalOperationMetadata()));
    }
    public static void shape(String id, Map<String,Object> input) {
        require(input != null && input.keySet().equals(INTAKE.equals(id) ? INTAKE_KEYS : STAGE_KEYS));
        new BuildSessionId(text(input.get("buildSessionId"))); new ProjectId(text(input.get("projectId")));
        if (INTAKE.equals(id)) {
            IncidentApplicationOrder.Variant.valueOf(text(input.get("variant"))); new CertificationId(text(input.get("certificationId")));
            hash(input.get("candidateHash")); hash(input.get("certificationManifestHash")); text(input.get("certificationManifestRef")); Instant.parse(text(input.get("deadlineAt")));
        } else {
            require(IDS.contains(id)); var stage = IncidentApplicationIntent.Stage.valueOf(text(input.get("stage"))); version(input.get("expectedVersion")); hash(input.get("subjectHash"));
            require(RELEASE.equals(id) == (stage == IncidentApplicationIntent.Stage.RELEASE));
        }
    }
    public static String text(Object value) { require(value instanceof String); return IncidentApplicationOrder.text((String)value,1024); }
    public static long version(Object value) { require(value instanceof Integer || value instanceof Long); long n = ((Number)value).longValue(); require(n >= 0); return n; }
    private static void hash(Object value) { require(text(value).matches("[0-9a-f]{64}")); }
    public static void require(boolean condition) { if (!condition) throw IncidentApplicationOrder.invalid(); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}
