package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationActions;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationArtifacts;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationReviewRenewalAction;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.flower.action.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exact opt-in human control, run before any Flower attach; never approval, build or shipment. */
public final class FactoryIncidentApplicationLocalReviewRenewal {
    private static final Logger LOG = LoggerFactory.getLogger(FactoryIncidentApplicationLocalReviewRenewal.class);
    private static final Set<String> BINDING_KEYS = Set.of("schemaVersion", "windowsSid", "tenantId", "projectId", "permissions", "requestHash");
    private final ActionRuntime runtime;
    private final Path bindingPath;
    private final Path requestPath;
    private final Supplier<String> nativeSid;
    private boolean completed;

    public FactoryIncidentApplicationLocalReviewRenewal(ActionRuntime runtime, Path bindingPath, Path requestPath) {
        this(runtime, bindingPath, requestPath, FactoryLocalDecisionOperator::currentWindowsSid);
    }

    FactoryIncidentApplicationLocalReviewRenewal(ActionRuntime runtime, Path bindingPath, Path requestPath, Supplier<String> nativeSid) {
        this.runtime = Objects.requireNonNull(runtime); this.bindingPath = Objects.requireNonNull(bindingPath);
        this.requestPath = Objects.requireNonNull(requestPath); this.nativeSid = Objects.requireNonNull(nativeSid);
    }

    public synchronized void execute() {
        if (completed) return;
        // Authenticate and validate the entire small batch before submitting its first effect.
        // Successful partial progress is durable; a subsequent explicit startup uses the same
        // exact keys and registered duplicate gates, never a compensating SQL edit or rebuild.
        var invocations = prepare(FactoryLocalDecisionDocuments.read(bindingPath),
                FactoryLocalDecisionDocuments.read(requestPath), nativeSid);
        for (var invocation : invocations) {
            var result = runtime.handle(invocation.proposal(), invocation.context());
            if (result == null || result.status() != ActionExecutionStatus.SUCCEEDED) {
                throw new IllegalStateException("LOCAL_INCIDENT_REVIEW_RENEWAL_NOT_RECORDED"
                        + (result == null ? "" : ":" + result.status()));
            }
        }
        completed = true;
        LOG.info("Exact operator review renewal recorded; separate human release decisions remain required");
    }

    static List<Invocation> prepare(byte[] bindingBytes, byte[] requestBytes, Supplier<String> nativeSid) {
        var binding = FactoryLocalDecisionDocuments.decode(bindingBytes, BINDING_KEYS);
        require("factory.local-incident-review-renewal-operator.v1".equals(binding.get("schemaVersion")));
        String sid = FactoryLocalDecisionDocuments.text(binding.get("windowsSid"));
        if (!sid.matches("S-1-5-21-[0-9]+-[0-9]+-[0-9]+-[0-9]+") || !sid.equals(nativeSid.get())) {
            throw new IllegalStateException("LOCAL_INCIDENT_REVIEW_RENEWAL_OPERATOR_UNAUTHENTICATED");
        }
        require(binding.get("permissions") instanceof List<?> permissions
                && permissions.size() == 1 && permissions.contains(IncidentApplicationReviewRenewalAction.ID));
        var tenant = new TenantId(FactoryLocalDecisionDocuments.text(binding.get("tenantId")));
        var project = new ProjectId(FactoryLocalDecisionDocuments.text(binding.get("projectId")));
        require(IncidentApplicationArtifacts.hash(requestBytes).equals(binding.get("requestHash")));
        var request = FactoryLocalDecisionDocuments.decode(requestBytes, Set.of("schemaVersion", "renewals"));
        require("factory.local-incident-review-renewal-request.v1".equals(request.get("schemaVersion")));
        require(request.get("renewals") instanceof List<?>);
        var renewals = (List<?>) request.get("renewals");
        require(!renewals.isEmpty() && renewals.size() <= 2);
        String principal = "windows-sid:" + sid;
        String authorityRef = "local-incident-review-renewal-binding-sha256:" + IncidentApplicationArtifacts.hash(bindingBytes);
        var sessions = new HashSet<String>();
        var invocations = new ArrayList<Invocation>();
        for (Object proposed : renewals) {
            require(proposed instanceof Map<?, ?>);
            @SuppressWarnings("unchecked") var input = Map.copyOf((Map<String, Object>) proposed);
            IncidentApplicationReviewRenewalAction.shape(input);
            require(project.value().equals(input.get("projectId")) && sessions.add((String) input.get("buildSessionId")));
            String key = IncidentApplicationReviewRenewalAction.key(tenant, input);
            var proposal = ActionProposal.builder(IncidentApplicationReviewRenewalAction.ID)
                    .proposalId("incident-renewal-" + UUID.randomUUID()).requestChannel(ActionRequestChannel.CLI)
                    .proposerType(ActionProposerType.USER).requesterId(principal).idempotencyKey(key).input(input)
                    .reason("Renew only the exact expired review; preserve its product and require a separate release decision").build();
            var context = new ExecutionContext(tenant.value(), principal, UUID.randomUUID().toString(), "incident-renewal-" + UUID.randomUUID(),
                    Map.of("actor.permissions", Set.of(IncidentApplicationReviewRenewalAction.ID), "actor.authoritySnapshotRef", authorityRef,
                            "resource.type", IncidentApplicationActions.RESOURCE, "resource.id", input.get("buildSessionId"),
                            "resource.projectId", project.value()));
            IncidentApplicationReviewRenewalAction.authority(proposal, context);
            invocations.add(new Invocation(proposal, context));
        }
        return List.copyOf(invocations);
    }

    private static void require(boolean condition) { if (!condition) throw FactoryLocalDecisionDocuments.invalid(); }
    record Invocation(ActionProposal proposal, ExecutionContext context) { }
}
