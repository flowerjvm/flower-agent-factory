package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.flow.*;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.run.*;
import java.time.Clock;
import java.util.*;

/** Does not claim a running external process was stopped; those attempts remain explicit uncertainty. */
public final class IncidentApplicationCancellationRequester implements FactoryProductLineCancellationRequester {
    private final IncidentApplicationLedger ledger; private final RunStore runs;
    private final CompletableActionRuntime runtime; private final Clock clock;
    public IncidentApplicationCancellationRequester(IncidentApplicationLedger ledger,RunStore runs,CompletableActionRuntime runtime,Clock clock) {
        this.ledger=Objects.requireNonNull(ledger); this.runs=Objects.requireNonNull(runs); this.runtime=Objects.requireNonNull(runtime); this.clock=Objects.requireNonNull(clock);
    }
    @Override public ProductLineId productLineId() { return IncidentApplicationOrder.PRODUCT_LINE_ID; }
    @Override public boolean canRequest(BuildSession session) {
        if(!session.productLineId().equals(productLineId())) return false;
        var product=ledger.find(session.tenantId(),session.buildSessionId()).orElse(null);
        if(product==null || product.status()==IncidentApplicationProduct.Status.RELEASED) return false;
        if(product.activeOperationId()!=null) {
            var intent=ledger.intent(product.activeOperationId()).orElse(null);
            if(intent==null || intent.status()==IncidentApplicationIntent.Status.EFFECT_COMMITTED) return false;
        }
        return true;
    }
    @Override public FactoryCancellationRequestDisposition request(BuildSession session) {
        if(!canRequest(session) || session.status()!=BuildSessionStatus.CANCELLING || session.cancellationRequestedAt().isEmpty()) return FactoryCancellationRequestDisposition.CONFLICT;
        var product=ledger.find(session.tenantId(),session.buildSessionId()).orElseThrow(IncidentApplicationOrder::invalid);
        if(product.activeOperationId()!=null) {
            var intent=ledger.intent(product.activeOperationId()).orElseThrow(IncidentApplicationOrder::invalid);
            if(intent.status()==IncidentApplicationIntent.Status.RUNNING) {
                // A bounded process may still exist. Preserve cancellation request and keep the
                // normal runner responsible for observing termination or quarantining its lease.
                return FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING;
            }
            var action=runs.find(intent.actionRunId()).orElse(null);
            if(action!=null && !action.status().isTerminal()) {
                runtime.cancel(action.runId(),"INCIDENT_APPLICATION_CANCEL_REQUESTED");
                return FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING;
            }
            if(intent.status()==IncidentApplicationIntent.Status.EFFECT_COMMITTED) return FactoryCancellationRequestDisposition.CONFLICT;
        }
        ledger.cancel(session.tenantId(),session.buildSessionId(),clock.instant());
        return FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT;
    }
}
