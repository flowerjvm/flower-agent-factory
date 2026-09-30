package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import java.util.Objects;

/** Startup/checkpoint-gap scan; ordinary stage transitions remain inside the Flower Flow. */
public final class IncidentApplicationRecovery {
    private final IncidentApplicationLedger ledger;
    private final BuildSessionRepository sessions;
    private final FactoryProductLineFlowLauncher launcher;
    public IncidentApplicationRecovery(IncidentApplicationLedger ledger,BuildSessionRepository sessions,FactoryProductLineFlowLauncher launcher) {
        this.ledger=Objects.requireNonNull(ledger); this.sessions=Objects.requireNonNull(sessions); this.launcher=Objects.requireNonNull(launcher);
    }
    public int recoverBatch(int limit) {
        int launched=0;
        for(var product:ledger.active(limit)) {
            var order=product.order(); var session=sessions.find(order.tenantId(),order.buildSessionId()).orElseThrow(IncidentApplicationOrder::invalid);
            if(session.status().isTerminal() || session.cancellationRequestedAt().isPresent()) continue;
            String identity=IncidentApplicationArtifacts.hash(order.tenantId().value()+"\n"+order.buildSessionId().value());
            launcher.launch(order.tenantId(),order.buildSessionId(),"incident-flow-"+identity,"incident-trace-"+identity); launched++;
        }
        return launched;
    }
}
