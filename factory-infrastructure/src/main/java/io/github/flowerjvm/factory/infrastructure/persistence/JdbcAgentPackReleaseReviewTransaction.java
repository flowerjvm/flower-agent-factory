package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewPolicy;
import io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewResult;
import io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewTransaction;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Session -> VerificationRun -> DecisionPoint lock order. The session lock serializes identical
 * missing-row inserts and follows DecisionRecordingTransaction's session-before-point order.
 * No human Decision row is inserted, and the session is not advanced or unconditionally updated.
 */
public final class JdbcAgentPackReleaseReviewTransaction implements AgentPackReleaseReviewTransaction {
    private final DataSource dataSource;
    private final JdbcBuildSessionRepository sessions;
    private final JdbcCandidateVersionRepository candidates;
    private final JdbcVerificationRunRepository verifications;
    private final JdbcDecisionPointRepository points;
    private final Clock clock;
    private final AgentPackReleaseReviewPolicy policy;

    public JdbcAgentPackReleaseReviewTransaction(
            DataSource dataSource, ObjectMapper mapper, Clock clock, Duration reviewWindow) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.sessions = new JdbcBuildSessionRepository(dataSource);
        this.candidates = new JdbcCandidateVersionRepository(dataSource);
        this.verifications = new JdbcVerificationRunRepository(dataSource);
        this.points = new JdbcDecisionPointRepository(dataSource, mapper);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = new AgentPackReleaseReviewPolicy(reviewWindow);
    }

    @Override
    public AgentPackReleaseReviewResult ensureOpen(
            BuildSession expectedSession, CandidateVersion expectedCandidate,
            VerificationRun expectedVerification, DecisionPoint requestedPoint) {
        policy.requireRequested(expectedSession, expectedCandidate, expectedVerification, requestedPoint, clock.instant());
        return withConnection(dataSource, "open Agent Pack release review", connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                AgentPackReleaseReviewResult result = openLocked(connection, expectedSession,
                        expectedCandidate, expectedVerification, requestedPoint);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    private AgentPackReleaseReviewResult openLocked(
            Connection connection, BuildSession expectedSession, CandidateVersion expectedCandidate,
            VerificationRun expectedVerification, DecisionPoint requested) throws SQLException {
        BuildSession current = sessions.find(connection, expectedSession.tenantId(),
                expectedSession.buildSessionId(), true).orElseThrow(JdbcAgentPackReleaseReviewTransaction::conflict);
        CandidateVersion candidate = candidates.find(connection, current.tenantId(), expectedCandidate.candidateId())
                .orElseThrow(JdbcAgentPackReleaseReviewTransaction::conflict);
        VerificationRun verification = verifications.findLatestForCandidate(connection, current.tenantId(),
                current.buildSessionId(), candidate.candidateId(), candidate.sourceHash(),
                expectedVerification.gateProfile(), true).orElseThrow(JdbcAgentPackReleaseReviewTransaction::conflict);
        if (!current.equals(expectedSession) || !candidate.equals(expectedCandidate)
                || !verification.equals(expectedVerification)) {
            throw conflict();
        }
        Instant now = clock.instant();
        policy.requireRequested(current, candidate, verification, requested, now);
        var existing = points.find(connection, current.tenantId(), requested.decisionPointId(), true);
        var latestSubject = points.findLatestByBuildSessionAndSubject(connection, current.tenantId(),
                current.buildSessionId(), requested.type(), requested.subjectType(), requested.subjectId(),
                requested.subjectHash());
        if (latestSubject.filter(point -> !point.decisionPointId().equals(requested.decisionPointId())).isPresent()) {
            throw conflict();
        }
        if (existing.isPresent()) {
            DecisionPoint stored = existing.orElseThrow();
            policy.requireExisting(current, candidate, requested, stored, now);
            return new AgentPackReleaseReviewResult(AgentPackReleaseReviewResult.Disposition.EXISTING_EXACT, stored);
        }
        // Recheck trusted time after all potentially contended locks, immediately before insert.
        policy.requireRequested(current, candidate, verification, requested, clock.instant());
        points.create(connection, requested);
        return new AgentPackReleaseReviewResult(AgentPackReleaseReviewResult.Disposition.CREATED, requested);
    }

    private static IllegalArgumentException conflict() {
        return new IllegalArgumentException(AgentPackReleaseReviewPolicy.CONFLICT);
    }
}
