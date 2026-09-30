package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.application.work.WorkerCancellationOutcomeTransaction;
import io.github.flowerjvm.factory.application.work.WorkerDispatchPublisher;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import javax.sql.DataSource;

/** Atomically closes a claimed Worker cancel across outbox, WorkerRun, and BuildSession truth. */
public final class JdbcWorkerCancellationOutcomeTransaction
        implements WorkerCancellationOutcomeTransaction {
    private static final String CONFIRMED_MESSAGE = "Remote Worker cancellation was confirmed";
    private static final String MANUAL_REVIEW_MESSAGE =
            "Remote Worker cancellation could not be confirmed";
    private static final String SUPERSEDED_MESSAGE =
            "Canonical Worker completion superseded the remote cancellation request";

    private final DataSource dataSource;
    private final JdbcBuildSessionRepository buildSessions;
    private final JdbcWorkerRunRepository workerRuns;
    private final JdbcDispatchOutboxRepository outboxes;

    public JdbcWorkerCancellationOutcomeTransaction(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcWorkerCancellationOutcomeTransaction(
            DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.buildSessions = new JdbcBuildSessionRepository(dataSource);
        this.workerRuns = new JdbcWorkerRunRepository(dataSource, mapper);
        this.outboxes = new JdbcDispatchOutboxRepository(dataSource);
    }

    @Override
    public boolean confirm(
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant confirmedAt) {
        return apply(
                tenantId,
                workerRunId,
                outboxId,
                operationId,
                claimToken,
                stableCode,
                confirmedAt,
                Outcome.CONFIRMED);
    }

    @Override
    public boolean confirmAbsentAfterProvenNoDispatch(
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId cancelOutboxId,
            DispatchOutboxId dispatchOutboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant confirmedAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        Objects.requireNonNull(cancelOutboxId, "cancelOutboxId");
        Objects.requireNonNull(dispatchOutboxId, "dispatchOutboxId");
        String exactOperationId = requireText(operationId, "operationId");
        String exactClaimToken = requireText(claimToken, "claimToken");
        String exactStableCode = requireStableCode(stableCode);
        Instant canonicalConfirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt")
                .truncatedTo(ChronoUnit.MICROS);
        return withConnection(dataSource, "confirm absent Worker after proven no dispatch", connection ->
                confirmAbsentInTransaction(
                        connection,
                        tenantId,
                        workerRunId,
                        cancelOutboxId,
                        dispatchOutboxId,
                        exactOperationId,
                        exactClaimToken,
                        exactStableCode,
                        canonicalConfirmedAt));
    }

    @Override
    public boolean manualReview(
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant observedAt) {
        return apply(
                tenantId,
                workerRunId,
                outboxId,
                operationId,
                claimToken,
                stableCode,
                observedAt,
                Outcome.MANUAL_REVIEW);
    }

    @Override
    public boolean supersedeAfterTerminalCompletion(
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant observedAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        Objects.requireNonNull(outboxId, "outboxId");
        String exactOperationId = requireText(operationId, "operationId");
        String exactClaimToken = requireText(claimToken, "claimToken");
        String exactStableCode = requireStableCode(stableCode);
        Instant canonicalObservedAt = Objects.requireNonNull(observedAt, "observedAt")
                .truncatedTo(ChronoUnit.MICROS);
        return withConnection(dataSource, "supersede Worker cancellation after completion", connection ->
                supersedeInTransaction(
                        connection,
                        tenantId,
                        workerRunId,
                        outboxId,
                        exactOperationId,
                        exactClaimToken,
                        exactStableCode,
                        canonicalObservedAt));
    }

    private boolean apply(
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant observedAt,
            Outcome outcome) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        Objects.requireNonNull(outboxId, "outboxId");
        String exactOperationId = requireText(operationId, "operationId");
        String exactClaimToken = requireText(claimToken, "claimToken");
        String exactStableCode = requireStableCode(stableCode);
        Instant canonicalObservedAt = Objects.requireNonNull(observedAt, "observedAt")
                .truncatedTo(ChronoUnit.MICROS);
        return withConnection(dataSource, "record Worker cancellation outcome", connection ->
                inTransaction(
                        connection,
                        tenantId,
                        workerRunId,
                        outboxId,
                        exactOperationId,
                        exactClaimToken,
                        exactStableCode,
                        canonicalObservedAt,
                        outcome));
    }

    private boolean inTransaction(
            Connection connection,
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant observedAt,
            Outcome outcome) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            WorkerRunRecord observedRun = workerRuns.find(connection, tenantId, workerRunId)
                    .orElse(null);
            if (observedRun == null
                    || observedRun.status() != WorkerRunStatus.CANCEL_REQUESTED
                    || !operationId.equals(observedRun.operationId())) {
                connection.rollback();
                return false;
            }

            BuildSession session = buildSessions.find(
                            connection, tenantId, observedRun.buildSessionId(), true)
                    .orElse(null);
            WorkerRunRecord activeRun = workerRuns.findActiveByBuildSession(
                            connection, tenantId, observedRun.buildSessionId(), true)
                    .orElse(null);
            DispatchOutbox claimed = outboxes.find(connection, tenantId, outboxId).orElse(null);
            if (!observedRun.equals(activeRun)
                    || !exactBinding(session, observedRun, claimed, outboxId, operationId, claimToken)
                    || observedAt.isBefore(observedRun.updatedAt())
                    || observedAt.isBefore(session.updatedAt())
                    || observedAt.isBefore(claimed.updatedAt())) {
                connection.rollback();
                return false;
            }

            String message = outcome == Outcome.CONFIRMED
                    ? CONFIRMED_MESSAGE
                    : MANUAL_REVIEW_MESSAGE;
            WorkerRunRecord nextRun = outcome == Outcome.CONFIRMED
                    ? observedRun.confirmCancellation(stableCode, message, observedAt)
                    : observedRun.manualReview(stableCode, message, observedAt);
            DispatchOutbox nextOutbox = outcome == Outcome.CONFIRMED
                    ? claimed.confirmed(claimToken, stableCode, observedAt)
                    : claimed.manualReview(claimToken, stableCode, observedAt);
            BuildSession nextSession = outcome == Outcome.CONFIRMED
                    ? session.confirmCancellation(stableCode, message, observedAt)
                    : session.manualReviewCancellation(stableCode, message, observedAt);

            if (!workerRuns.compareAndSet(connection, observedRun, nextRun)
                    || !outboxes.compareAndSet(connection, claimed, nextOutbox)
                    || !buildSessions.compareAndSet(connection, session, nextSession)) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private boolean supersedeInTransaction(
            Connection connection,
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant observedAt) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            WorkerRunRecord terminalRun = workerRuns.find(connection, tenantId, workerRunId)
                    .orElse(null);
            if (terminalRun == null
                    || !terminalRun.status().isTerminal()
                    || terminalRun.status() == WorkerRunStatus.CANCELLED
                    || !operationId.equals(terminalRun.operationId())) {
                connection.rollback();
                return false;
            }

            BuildSession session = buildSessions.find(
                            connection, tenantId, terminalRun.buildSessionId(), true)
                    .orElse(null);
            WorkerRunRecord activeRun = workerRuns.findActiveByBuildSession(
                            connection, tenantId, terminalRun.buildSessionId(), true)
                    .orElse(null);
            DispatchOutbox claimed = outboxes.find(connection, tenantId, outboxId).orElse(null);
            if (activeRun != null
                    || !exactBinding(session, terminalRun, claimed, outboxId, operationId, claimToken)
                    || observedAt.isBefore(terminalRun.updatedAt())
                    || observedAt.isBefore(session.updatedAt())
                    || observedAt.isBefore(claimed.updatedAt())) {
                connection.rollback();
                return false;
            }

            DispatchOutbox nextOutbox = claimed.superseded(claimToken, stableCode, observedAt);
            BuildSession nextSession = session.confirmCancellation(
                    stableCode, SUPERSEDED_MESSAGE, observedAt);
            if (!outboxes.compareAndSet(connection, claimed, nextOutbox)
                    || !buildSessions.compareAndSet(connection, session, nextSession)) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private boolean confirmAbsentInTransaction(
            Connection connection,
            TenantId tenantId,
            WorkerRunId workerRunId,
            DispatchOutboxId cancelOutboxId,
            DispatchOutboxId dispatchOutboxId,
            String operationId,
            String claimToken,
            String stableCode,
            Instant confirmedAt) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            WorkerRunRecord observedRun = workerRuns.find(connection, tenantId, workerRunId)
                    .orElse(null);
            if (observedRun == null
                    || observedRun.status() != WorkerRunStatus.CANCEL_REQUESTED
                    || !operationId.equals(observedRun.operationId())
                    || observedRun.dispatchOutboxId().filter(dispatchOutboxId::equals).isEmpty()) {
                connection.rollback();
                return false;
            }

            BuildSession session = buildSessions.find(
                            connection, tenantId, observedRun.buildSessionId(), true)
                    .orElse(null);
            WorkerRunRecord activeRun = workerRuns.findActiveByBuildSession(
                            connection, tenantId, observedRun.buildSessionId(), true)
                    .orElse(null);
            DispatchOutbox cancelOutbox;
            DispatchOutbox dispatchOutbox;
            if (cancelOutboxId.value().compareTo(dispatchOutboxId.value()) <= 0) {
                cancelOutbox = outboxes.find(connection, tenantId, cancelOutboxId, true).orElse(null);
                dispatchOutbox = outboxes.find(connection, tenantId, dispatchOutboxId, true).orElse(null);
            } else {
                dispatchOutbox = outboxes.find(connection, tenantId, dispatchOutboxId, true).orElse(null);
                cancelOutbox = outboxes.find(connection, tenantId, cancelOutboxId, true).orElse(null);
            }

            if (!observedRun.equals(activeRun)
                    || !exactBinding(
                            session,
                            observedRun,
                            cancelOutbox,
                            cancelOutboxId,
                            operationId,
                            claimToken)
                    || !exactDispatchBinding(observedRun, dispatchOutbox, dispatchOutboxId, operationId)
                    || !provesNoDispatchEffect(dispatchOutbox)
                    || confirmedAt.isBefore(observedRun.updatedAt())
                    || confirmedAt.isBefore(session.updatedAt())
                    || confirmedAt.isBefore(cancelOutbox.updatedAt())
                    || confirmedAt.isBefore(dispatchOutbox.updatedAt())) {
                connection.rollback();
                return false;
            }

            WorkerRunRecord nextRun = observedRun.confirmCancellation(
                    stableCode, CONFIRMED_MESSAGE, confirmedAt);
            DispatchOutbox nextCancelOutbox = cancelOutbox.confirmed(
                    claimToken, stableCode, confirmedAt);
            BuildSession nextSession = session.confirmCancellation(
                    stableCode, CONFIRMED_MESSAGE, confirmedAt);
            if (!workerRuns.compareAndSet(connection, observedRun, nextRun)
                    || !outboxes.compareAndSet(connection, cancelOutbox, nextCancelOutbox)
                    || !buildSessions.compareAndSet(connection, session, nextSession)) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static boolean exactDispatchBinding(
            WorkerRunRecord run,
            DispatchOutbox outbox,
            DispatchOutboxId outboxId,
            String operationId) {
        return outbox != null
                && WorkerOutboxOperations.DISPATCH.equals(outbox.operationType())
                && "WORKER_RUN".equals(outbox.aggregateType())
                && run.workerRunId().value().equals(outbox.aggregateId())
                && operationId.equals(outbox.operationId())
                && outboxId.equals(outbox.outboxId())
                && run.tenantId().equals(outbox.tenantId());
    }

    private static boolean provesNoDispatchEffect(DispatchOutbox dispatchOutbox) {
        if (dispatchOutbox.status() == DispatchOutboxStatus.PENDING) {
            return dispatchOutbox.attemptCount() == 0
                    && dispatchOutbox.claimToken().isEmpty()
                    && dispatchOutbox.claimPurpose().isEmpty()
                    && dispatchOutbox.claimedAt().isEmpty()
                    && dispatchOutbox.leaseUntil().isEmpty()
                    && dispatchOutbox.lastCode().isEmpty();
        }
        return dispatchOutbox.status() == DispatchOutboxStatus.MANUAL_REVIEW
                && dispatchOutbox.lastCode()
                        .filter(WorkerDispatchPublisher.CANCELLED_BEFORE_SUBMIT::equals)
                        .isPresent();
    }

    private static boolean exactBinding(
            BuildSession session,
            WorkerRunRecord run,
            DispatchOutbox outbox,
            DispatchOutboxId outboxId,
            String operationId,
            String claimToken) {
        return session != null
                && outbox != null
                && session.tenantId().equals(run.tenantId())
                && session.buildSessionId().equals(run.buildSessionId())
                && session.status() == BuildSessionStatus.CANCELLING
                && session.cancellationRequestedAt().isPresent()
                && session.selectedCodingWorkerBinding()
                        .filter(run.workerBindingId()::equals)
                        .isPresent()
                && outbox.status() == DispatchOutboxStatus.DISPATCHING
                && outbox.claimToken().filter(claimToken::equals).isPresent()
                && WorkerOutboxOperations.CANCEL.equals(outbox.operationType())
                && "WORKER_RUN".equals(outbox.aggregateType())
                && run.workerRunId().value().equals(outbox.aggregateId())
                && operationId.equals(outbox.operationId())
                && outboxId.equals(outbox.outboxId())
                && run.tenantId().equals(outbox.tenantId());
    }

    private static String requireStableCode(String code) {
        code = requireText(code, "stableCode");
        if (!code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("stableCode must be a bounded stable identifier");
        }
        return code;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private enum Outcome {
        CONFIRMED,
        MANUAL_REVIEW
    }
}
