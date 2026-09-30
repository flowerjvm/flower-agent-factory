package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.work.WorkerCallbackProcessor;
import io.github.flowerjvm.factory.application.work.WorkerCancelPublisher;
import io.github.flowerjvm.factory.application.work.WorkerCancellationIntentRecovery;
import io.github.flowerjvm.factory.application.work.WorkerDispatchPublisher;
import io.github.flowerjvm.factory.application.flow.FactoryFlowCancellationRecovery;
import io.github.flowerjvm.flower.check.annotation.FlowerSchedulerApproved;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/**
 * One bounded host lane for the persisted Coding Worker transport bridge.
 *
 * <p>This is not a workflow scheduler: Flower Steps only observe durable Worker/Candidate truth.
 */
@FlowerSchedulerApproved(
        reason = "Project owner approved PR5's bounded consumer for durable Worker dispatch, "
                + "cancellation and authenticated callback inbox rows",
        approvedBy = "project owner",
        approvedAt = "2026-08-20",
        reference = "docs/16 PR5")
final class WorkerTransportPump implements SmartLifecycle {
    private static final int MAX_CALLBACKS_PER_TICK = 8;
    private static final int MAX_CANCELS_PER_TICK = 4;
    private static final int MAX_DISPATCHES_PER_TICK = 4;
    private static final int MAX_STATUS_POLLS_PER_TICK = 8;

    private final WorkerCallbackProcessor callbacks;
    private final FactoryFlowCancellationRecovery flowCancellationRecovery;
    private final WorkerCancellationIntentRecovery cancellationIntentRecovery;
    private final WorkerCancelPublisher cancellations;
    private final WorkerDispatchPublisher dispatches;
    private final ScheduledExecutorService executor;
    private final Duration interval;
    private final Duration statusPollInterval;
    private volatile long nextStatusPollNanos;
    private volatile boolean statusPollScheduled;
    private volatile ScheduledFuture<?> scheduled;

    WorkerTransportPump(
            WorkerCallbackProcessor callbacks,
            FactoryFlowCancellationRecovery flowCancellationRecovery,
            WorkerCancellationIntentRecovery cancellationIntentRecovery,
            WorkerCancelPublisher cancellations,
            WorkerDispatchPublisher dispatches,
            ScheduledExecutorService executor,
            Duration interval) {
        this(
                callbacks,
                flowCancellationRecovery,
                cancellationIntentRecovery,
                cancellations,
                dispatches,
                executor,
                interval,
                Duration.ofSeconds(30));
    }

    WorkerTransportPump(
            WorkerCallbackProcessor callbacks,
            FactoryFlowCancellationRecovery flowCancellationRecovery,
            WorkerCancellationIntentRecovery cancellationIntentRecovery,
            WorkerCancelPublisher cancellations,
            WorkerDispatchPublisher dispatches,
            ScheduledExecutorService executor,
            Duration interval,
            Duration statusPollInterval) {
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.flowCancellationRecovery =
                Objects.requireNonNull(flowCancellationRecovery, "flowCancellationRecovery");
        this.cancellationIntentRecovery =
                Objects.requireNonNull(cancellationIntentRecovery, "cancellationIntentRecovery");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
        this.dispatches = Objects.requireNonNull(dispatches, "dispatches");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.statusPollInterval = Objects.requireNonNull(statusPollInterval, "statusPollInterval");
        if (interval.isZero() || interval.isNegative()
                || statusPollInterval.isZero() || statusPollInterval.isNegative()) {
            throw new IllegalArgumentException("pump intervals must be positive");
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
        runSafely(flowCancellationRecovery::tickOnce);
        runSafely(cancellationIntentRecovery::tickOnce);
        runSafely(() -> callbacks.drain(MAX_CALLBACKS_PER_TICK));
        runSafely(() -> drainCancellations(MAX_CANCELS_PER_TICK));
        runSafely(() -> drainDispatches(MAX_DISPATCHES_PER_TICK));
        long now = System.nanoTime();
        if (!statusPollScheduled || now - nextStatusPollNanos >= 0L) {
            statusPollScheduled = true;
            nextStatusPollNanos = now + statusPollInterval.toNanos();
            runSafely(() -> dispatches.pollAccepted(MAX_STATUS_POLLS_PER_TICK));
        }
    }

    private void drainCancellations(int maximum) {
        for (int index = 0; index < maximum && cancellations.tickOnce(); index++) {
            // bounded loop
        }
    }

    private void drainDispatches(int maximum) {
        for (int index = 0; index < maximum && dispatches.tickOnce(); index++) {
            // bounded loop
        }
    }

    private static void runSafely(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException ignored) {
            // Durable claims and immutable inbox rows carry restart/retry truth.
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
        return 110;
    }
}
