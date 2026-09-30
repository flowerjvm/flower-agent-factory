package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationRecovery;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationRunner;
import io.github.flowerjvm.factory.application.flow.FactoryFlowCancellationRecovery;
import io.github.flowerjvm.flower.check.annotation.FlowerSchedulerApproved;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Separate bounded control lane; Docker waits never occupy a Flower Worker tick. */
@FlowerSchedulerApproved(reason="User approved the incident-application line's durable production runner and recovery scan",
        approvedBy="project owner",approvedAt="2026-09-12",reference="docs/35-incident-application-product-line.md section 4")
final class IncidentApplicationPump implements SmartLifecycle {
    private static final Logger LOG=LoggerFactory.getLogger(IncidentApplicationPump.class);
    private final IncidentApplicationRunner runner;
    private final IncidentApplicationRecovery recovery;
    private final ScheduledExecutorService executor;
    private final Duration interval;
    private final FactoryFlowCancellationRecovery cancellations;
    private volatile ScheduledFuture<?> scheduled;
    IncidentApplicationPump(IncidentApplicationRunner runner,IncidentApplicationRecovery recovery,ScheduledExecutorService executor,Duration interval) {
        this(runner,recovery,executor,interval,null);
    }
    IncidentApplicationPump(IncidentApplicationRunner runner,IncidentApplicationRecovery recovery,ScheduledExecutorService executor,Duration interval,
            FactoryFlowCancellationRecovery cancellations) {
        this.runner=Objects.requireNonNull(runner); this.recovery=Objects.requireNonNull(recovery);
        this.executor=Objects.requireNonNull(executor); this.interval=Objects.requireNonNull(interval);
        this.cancellations=cancellations;
        if(interval.toMillis()<1) throw new IllegalArgumentException("positive polling interval required");
    }
    @Override public synchronized void start() {
        if(isRunning()) return;
        scheduled=executor.scheduleWithFixedDelay(this::tick,0,interval.toMillis(),TimeUnit.MILLISECONDS);
    }
    private void tick() {
        try { if(cancellations!=null) cancellations.tickOnce(); recovery.recoverBatch(32); runner.drain(2); }
        catch(RuntimeException failed) {
            // Keep credentials, SQL and product input out of logs; durable state remains authoritative.
            LOG.warn("Incident application control scan failed; durable recovery remains required");
        }
    }
    @Override public synchronized void stop() { if(scheduled!=null) { scheduled.cancel(false); scheduled=null; } }
    @Override public boolean isRunning() { var current=scheduled; return current!=null && !current.isCancelled() && !current.isDone(); }
    @Override public int getPhase() { return 102; }
}
