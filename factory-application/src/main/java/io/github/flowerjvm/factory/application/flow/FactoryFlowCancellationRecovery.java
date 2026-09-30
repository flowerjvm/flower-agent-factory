package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Resumes a trusted BuildSession cancellation that crashed after persisting {@code CANCELLING} but
 * before invoking the governed Worker or ProductLine effect cancellation and Flower control
 * command.
 *
 * <p>The bounded BuildSession scan starts from the durable cancellation authority and therefore
 * also covers the pre-dispatch/no-active-owner crash window. Recovery delegates to {@link
 * FactoryFlowCancellationService}; it never patches ActionRun or calls the Coding Worker directly.
 */
public final class FactoryFlowCancellationRecovery {
    public static final int DEFAULT_SCAN_LIMIT = 64;

    private final BuildSessionRepository sessions;
    private final WorkerRunRepository workerRuns;
    private final FactoryFlowCancellationService cancellations;
    private final Clock clock;
    private final int scanLimit;

    public FactoryFlowCancellationRecovery(
            BuildSessionRepository sessions,
            WorkerRunRepository workerRuns,
            FactoryFlowCancellationService cancellations,
            Clock clock) {
        this(sessions, workerRuns, cancellations, clock, DEFAULT_SCAN_LIMIT);
    }

    public FactoryFlowCancellationRecovery(
            BuildSessionRepository sessions,
            WorkerRunRepository workerRuns,
            FactoryFlowCancellationService cancellations,
            Clock clock,
            int scanLimit) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (scanLimit < 1 || scanLimit > 1_000) {
            throw new IllegalArgumentException("scanLimit must be between 1 and 1000");
        }
        this.scanLimit = scanLimit;
    }

    /** Resumes at most one exact CANCELLING session through its registered effect owner. */
    public boolean tickOnce() {
        Instant now = clock.instant();
        for (BuildSession session : sessions.findCancelling(now, scanLimit)) {
            if (session.status() != BuildSessionStatus.CANCELLING
                    || session.cancellationRequestedAt().isEmpty()
                    || session.updatedAt().isAfter(now)) {
                continue;
            }
            FlowCancellationDisposition disposition = cancellations.cancel(
                    session.tenantId(), session.buildSessionId());
            BuildSession canonicalSession = sessions
                    .find(session.tenantId(), session.buildSessionId())
                    .orElse(null);
            if (canonicalSession != null
                    && (canonicalSession.status() == BuildSessionStatus.CANCELLED
                            || canonicalSession.status() == BuildSessionStatus.MANUAL_REVIEW)) {
                return true;
            }
            WorkerRunRecord canonical = workerRuns
                    .findActiveByBuildSession(session.tenantId(), session.buildSessionId())
                    .orElse(null);
            if (canonical != null && canonical.status() == WorkerRunStatus.CANCEL_REQUESTED) {
                return true;
            }
            if (disposition != FlowCancellationDisposition.CONFLICT) {
                return true;
            }
        }
        return false;
    }

}
