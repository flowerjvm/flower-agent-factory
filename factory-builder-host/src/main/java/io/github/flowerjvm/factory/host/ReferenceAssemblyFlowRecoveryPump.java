package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.flow.FactoryReferenceAssemblyFlowRecovery;
import io.github.flowerjvm.flower.check.annotation.FlowerSchedulerApproved;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/** Periodically closes the durable gap before a Reference Assembly Flow's first checkpoint. */
@FlowerSchedulerApproved(
        reason = "The project owner approved a bounded host recovery scan for Reference Assembly "
                + "BuildSessions committed before their primary Flow checkpoint",
        approvedBy = "project owner",
        approvedAt = "2026-09-02",
        reference = "Reference Assembly composable ProductLine implementation")
final class ReferenceAssemblyFlowRecoveryPump implements SmartLifecycle {
    private final FactoryReferenceAssemblyFlowRecovery recovery;
    private final ScheduledExecutorService executor;
    private final Duration interval;
    private volatile ScheduledFuture<?> scheduled;

    ReferenceAssemblyFlowRecoveryPump(
            FactoryReferenceAssemblyFlowRecovery recovery,
            ScheduledExecutorService executor,
            Duration interval) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        scheduled = executor.scheduleWithFixedDelay(
                this::safeRecover, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void safeRecover() {
        try {
            recovery.recoverBatch();
        } catch (RuntimeException ignored) {
            // BuildSession remains the scan authority; one failure must not stop later scans.
        }
    }

    @Override
    public synchronized void stop() {
        ScheduledFuture<?> active = scheduled;
        if (active != null) {
            active.cancel(false);
            scheduled = null;
        }
    }

    @Override
    public boolean isRunning() {
        ScheduledFuture<?> active = scheduled;
        return active != null && !active.isCancelled() && !active.isDone();
    }

    @Override
    public int getPhase() {
        return 101;
    }
}
