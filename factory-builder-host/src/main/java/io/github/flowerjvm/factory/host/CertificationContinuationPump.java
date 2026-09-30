package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.flow.FactoryCertificationContinuationRecovery;
import io.github.flowerjvm.flower.check.annotation.FlowerSchedulerApproved;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/** Periodically closes the durable gap before a certification continuation's first checkpoint. */
@FlowerSchedulerApproved(
        reason = "The project owner approved a bounded host recovery scan for certification "
                + "continuations that committed BuildSession state before their first Flower checkpoint",
        approvedBy = "project owner",
        approvedAt = "2026-09-02",
        reference = "docs/28 PR6-A2 continuation recovery")
final class CertificationContinuationPump implements SmartLifecycle {
    private final FactoryCertificationContinuationRecovery recovery;
    private final ScheduledExecutorService executor;
    private final Duration interval;
    private volatile ScheduledFuture<?> scheduled;

    CertificationContinuationPump(
            FactoryCertificationContinuationRecovery recovery,
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
            // The BuildSession ledger remains scan authority; one failure must not kill the lane.
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
