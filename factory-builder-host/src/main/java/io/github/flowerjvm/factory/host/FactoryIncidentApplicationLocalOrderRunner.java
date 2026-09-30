package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.*;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/** Explicit local operator transport. It grants production intake, never human release approval. */
public final class FactoryIncidentApplicationLocalOrderRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(FactoryIncidentApplicationLocalOrderRunner.class);
    private final ActionRuntime runtime;
    private final IncidentApplicationIntake intake;
    private final IncidentApplicationLedger ledger;
    private final FactoryProductLineFlowLauncher flows;
    private final TenantId tenant;
    private final String requestKey;
    private final Map<String,Object> input;

    public FactoryIncidentApplicationLocalOrderRunner(ActionRuntime runtime, IncidentApplicationIntake intake,
            IncidentApplicationLedger ledger, FactoryProductLineFlowLauncher flows, TenantId tenant,
            String requestKey, Map<String,Object> input) {
        this.runtime = Objects.requireNonNull(runtime); this.intake = Objects.requireNonNull(intake);
        this.ledger = Objects.requireNonNull(ledger); this.flows = Objects.requireNonNull(flows);
        this.tenant = Objects.requireNonNull(tenant); this.requestKey = bounded(requestKey);
        bounded(tenant.value()); this.input = Map.copyOf(input);
        IncidentApplicationActions.shape(IncidentApplicationActions.INTAKE, this.input);
    }

    @Override public void run(ApplicationArguments ignored) {
        var expected = intake.requireSelection(tenant, requestKey, input, true);
        var proposal = ActionProposal.builder(IncidentApplicationActions.INTAKE)
                .proposalId("incident-intake-" + UUID.randomUUID())
                .requestChannel(ActionRequestChannel.INTERNAL).proposerType(ActionProposerType.SERVICE)
                .requesterId(IncidentApplicationActions.OWNER).idempotencyKey(requestKey).input(input)
                .reason("Produce one explicitly selected local incident application; release requires a separate exact human decision")
                .build();
        var context = new ExecutionContext(tenant.value(), IncidentApplicationActions.OWNER,
                UUID.randomUUID().toString(), "incident-intake-" + UUID.randomUUID(),
                Map.of("actor.permissions", Set.of(IncidentApplicationActions.INTAKE),
                        "resource.type", IncidentApplicationActions.RESOURCE,
                        "resource.id", expected.buildSessionId().value(), "resource.projectId", expected.projectId().value()));
        var result = runtime.handle(proposal, context);
        if (result.status() != ActionExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException("Incident application intake did not succeed: " + result.status());
        }
        // Never interpret an Action result payload or duplicate replay as canonical product authority.
        var product = ledger.find(tenant, expected.buildSessionId()).orElseThrow();
        if (!product.order().equals(expected)) throw new IllegalStateException("Accepted incident order differs from trusted input");
        if (product.status() != IncidentApplicationProduct.Status.ACCEPTED) {
            LOG.info("Incident application order already progressed; durable recovery owns continuation");
            return;
        }
        String identity = IncidentApplicationArtifacts.hash(tenant.value().length() + ":" + tenant.value()
                + expected.buildSessionId().value().length() + ":" + expected.buildSessionId().value());
        flows.launch(tenant, expected.buildSessionId(), "incident-initial-" + identity,
                "incident-initial-trace-" + identity);
        LOG.info("Incident application order accepted and Flow submitted; no release approval implied");
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Explicit bounded local order identity required");
        return value;
    }
}
