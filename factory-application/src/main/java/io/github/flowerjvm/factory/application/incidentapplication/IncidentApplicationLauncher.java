package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import java.util.*;

/** Host-owned canonical proposal mapping. Calling code authenticates the operator before selecting a tenant. */
public final class IncidentApplicationLauncher {
    private final ActionRuntime runtime;
    public IncidentApplicationLauncher(ActionRuntime runtime) { this.runtime = Objects.requireNonNull(runtime); }
    public ActionExecutionResult submit(TenantId tenant, String requestKey, Map<String,Object> intake) {
        IncidentApplicationActions.shape(IncidentApplicationActions.INTAKE, intake);
        return submit(IncidentApplicationActions.INTAKE, tenant, requestKey, intake);
    }
    public ActionExecutionResult stage(IncidentApplicationProduct product, IncidentApplicationIntent.Stage stage) {
        return submit(stage == IncidentApplicationIntent.Stage.RELEASE ? IncidentApplicationActions.RELEASE : IncidentApplicationActions.STAGE,
                product.order().tenantId(), IncidentApplicationActions.stageKey(product,stage,product.version()), IncidentApplicationActions.stageInput(product,stage));
    }
    private ActionExecutionResult submit(String action, TenantId tenant, String key, Map<String,Object> input) {
        var proposal = ActionProposal.builder(action).proposalId("incident-" + UUID.randomUUID())
                .requestChannel(ActionRequestChannel.INTERNAL).proposerType(ActionProposerType.SERVICE).requesterId(IncidentApplicationActions.OWNER)
                .idempotencyKey(key).input(input).reason("Run one exact incident application production operation").build();
        var context = new ExecutionContext(tenant.value(), IncidentApplicationActions.OWNER, UUID.randomUUID().toString(),
                "incident-" + IncidentApplicationArtifacts.hash(tenant.value() + "\n" + input.get("buildSessionId") + "\n" + key),
                Map.of("actor.permissions",Set.of(action),"resource.type",IncidentApplicationActions.RESOURCE,
                        "resource.id",input.get("buildSessionId"),"resource.projectId",input.get("projectId")));
        return runtime.handle(proposal,context);
    }
}
