package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.decision.Decision;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionRecordingDisposition;
import io.github.flowerjvm.factory.application.decision.DecisionRecordingTransaction;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/** Atomically inserts the immutable Decision and wins the DecisionPoint terminal CAS. */
public final class JdbcDecisionRecordingTransaction implements DecisionRecordingTransaction {
    private final DataSource dataSource;
    private final JdbcBuildSessionRepository buildSessions;
    private final JdbcDecisionPointRepository decisionPoints;
    private final JdbcDecisionRepository decisions;

    public JdbcDecisionRecordingTransaction(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.buildSessions = new JdbcBuildSessionRepository(dataSource);
        this.decisionPoints = new JdbcDecisionPointRepository(dataSource, objectMapper);
        this.decisions = new JdbcDecisionRepository(dataSource);
    }

    @Override
    public DecisionRecordingDisposition record(
            BuildSession expectedBuildSession,
            DecisionPoint expectedOpen,
            Decision decision,
            DecisionPoint decided) {
        validate(expectedBuildSession, expectedOpen, decision, decided);
        return withConnection(dataSource, "record Decision", connection ->
                inTransaction(connection, expectedBuildSession, expectedOpen, decision, decided));
    }

    private DecisionRecordingDisposition inTransaction(
            Connection connection,
            BuildSession expectedBuildSession,
            DecisionPoint expectedOpen,
            Decision decision,
            DecisionPoint decided) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            BuildSession lockedBuildSession = buildSessions.find(
                            connection,
                            expectedBuildSession.tenantId(),
                            expectedBuildSession.buildSessionId(),
                            true)
                    .orElse(null);
            if (!expectedBuildSession.equals(lockedBuildSession)) {
                connection.rollback();
                return DecisionRecordingDisposition.CONFLICT;
            }
            decisions.create(connection, decision);
            if (!decisionPoints.compareAndSet(connection, expectedOpen, decided)) {
                connection.rollback();
                return classify(decision);
            }
            connection.commit();
            return DecisionRecordingDisposition.APPLIED;
        } catch (SQLException exception) {
            rollback(connection, exception);
            if ("23505".equals(exception.getSQLState())) {
                return classify(decision);
            }
            throw exception;
        } catch (RuntimeException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private DecisionRecordingDisposition classify(Decision decision) {
        return decisions.findByRequestIdempotencyKey(
                        decision.tenantId(), decision.decisionPointId(), decision.requestIdempotencyKey())
                        .filter(decision::hasSameRequestPayload)
                        .isPresent()
                ? DecisionRecordingDisposition.DUPLICATE
                : DecisionRecordingDisposition.CONFLICT;
    }

    private static void validate(
            BuildSession expectedBuildSession,
            DecisionPoint expectedOpen,
            Decision decision,
            DecisionPoint decided) {
        Objects.requireNonNull(expectedBuildSession, "expectedBuildSession");
        Objects.requireNonNull(expectedOpen, "expectedOpen");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decided, "decided");
        if (!expectedBuildSession.tenantId().equals(expectedOpen.tenantId())
                || !expectedBuildSession.buildSessionId().equals(expectedOpen.buildSessionId())) {
            throw new IllegalArgumentException("DecisionPoint must belong to expected BuildSession");
        }
        DecisionPoint derived = expectedOpen.decide(decision, decision.createdAt());
        if (!derived.equals(decided)) {
            throw new IllegalArgumentException("decided snapshot must be derived from the exact Decision");
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
