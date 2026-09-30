package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.certification.CertificationDispatchRunner;
import io.github.flowerjvm.flower.check.annotation.FlowerSchedulerApproved;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/** Bounded host lane that resumes persisted certification issuance intents. */
@FlowerSchedulerApproved(
        reason = "The project owner approved the PR6 host-owned bounded consumer for durable "
                + "certification intents; Flower Steps only observe durable ledger truth",
        approvedBy = "project owner",
        approvedAt = "2026-09-02",
        reference = "docs/27 and docs/28 PR6-A2")
final class CertificationDispatchPump implements SmartLifecycle {
    private static final int MAXIMUM_PER_TICK = 8;

    private final CertificationDispatchRunner runner;
    private final ScheduledExecutorService executor;
    private final Duration interval;
    private volatile ScheduledFuture<?> scheduled;

    CertificationDispatchPump(
            CertificationDispatchRunner runner,
            ScheduledExecutorService executor,
            Duration interval) {
        this.runner = Objects.requireNonNull(runner, "runner");
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
                this::safeTick, 0L, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void safeTick() {
        try {
            runner.drain(MAXIMUM_PER_TICK);
        } catch (RuntimeException ignored) {
            // The persisted lease owns recovery; one failed tick must not cancel later ticks.
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
        return 100;
    }
}
