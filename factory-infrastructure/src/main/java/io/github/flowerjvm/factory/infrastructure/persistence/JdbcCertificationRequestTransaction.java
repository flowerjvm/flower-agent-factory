package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicy;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicyCatalog;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequestException;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationRequestOutcome;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationArtifactCodec;
import io.github.flowerjvm.factory.application.certification.CertificationRequestDisposition;
import io.github.flowerjvm.factory.application.certification.CertificationRequestTransaction;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.infrastructure.certification.JacksonCertificationArtifactCodec;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Atomic JDBC boundary for one deterministic Agent Pack certification request.
 *
 * <p>An existing Certification is always locked before its BuildSession, matching dispatch. The
 * missing-row create path locks BuildSession and performs a bounded restart if a Certification
 * appeared while waiting. Concurrent identical requests therefore converge without relying on a
 * duplicate-key exception (which would abort a PostgreSQL transaction). Both the REQUESTED insert
 * and exact candidate-ready to CERTIFYING/CERTIFY version CAS commit together.
 */
public final class JdbcCertificationRequestTransaction implements CertificationRequestTransaction {
    public static final String SESSION_CONFLICT = "CERTIFICATION_REQUEST_SESSION_CONFLICT";
    public static final String IDENTITY_CONFLICT = "CERTIFICATION_REQUEST_IDENTITY_CONFLICT";
    public static final String POLICY_CONFLICT = "CERTIFICATION_REQUEST_POLICY_IDENTITY_MISMATCH";

    private final DataSource dataSource;
    private final JdbcBuildSessionRepository sessions;
    private final JdbcCertificationRepository certifications;
    private final AgentPackCertificationPolicyCatalog policy;

    public JdbcCertificationRequestTransaction(
            DataSource dataSource,
            AgentPackCertificationPolicy policy) {
        this(
                dataSource,
                policy,
                new JacksonCertificationArtifactCodec(),
                new JacksonWorkerProtocolArtifactDecoder(new ObjectMapper()));
    }

    public JdbcCertificationRequestTransaction(
            DataSource dataSource,
            AgentPackCertificationPolicy policy,
            CertificationArtifactCodec codec,
            WorkerProtocolArtifactDecoder workerDecoder) {
        this(dataSource, AgentPackCertificationPolicyCatalog.singleton(policy), codec, workerDecoder);
    }

    public JdbcCertificationRequestTransaction(
            DataSource dataSource,
            AgentPackCertificationPolicyCatalog policy) {
        this(dataSource, policy, new JacksonCertificationArtifactCodec(),
                new JacksonWorkerProtocolArtifactDecoder(new ObjectMapper()));
    }

    public JdbcCertificationRequestTransaction(
            DataSource dataSource,
            AgentPackCertificationPolicyCatalog policy,
            CertificationArtifactCodec codec,
            WorkerProtocolArtifactDecoder workerDecoder) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sessions = new JdbcBuildSessionRepository(dataSource);
        this.certifications = new JdbcCertificationRepository(
                dataSource,
                Objects.requireNonNull(codec, "codec"),
                Objects.requireNonNull(workerDecoder, "workerDecoder"));
    }

    @Override
    public AgentPackCertificationRequestOutcome ensureRequested(
            BuildSession expectedSession,
            BuildSession certifyingSession,
            Certification requestedCertification) {
        validateArguments(expectedSession, certifyingSession, requestedCertification);
        return withConnection(dataSource, "request Certification", connection -> inTransaction(
                connection, expectedSession, certifyingSession, requestedCertification));
    }

    private AgentPackCertificationRequestOutcome inTransaction(
            Connection connection,
            BuildSession expectedSession,
            BuildSession certifyingSession,
            Certification requestedCertification) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    return attemptOnce(
                            connection,
                            expectedSession,
                            certifyingSession,
                            requestedCertification);
                } catch (RestartWithCertificationLock restart) {
                    connection.rollback();
                    if (attempt == 1) {
                        throw fail(
                                SESSION_CONFLICT,
                                "Certification appeared repeatedly while locking BuildSession");
                    }
                }
            }
            throw fail(SESSION_CONFLICT, "Certification request lock restart was exhausted");
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private AgentPackCertificationRequestOutcome attemptOnce(
            Connection connection,
            BuildSession expectedSession,
            BuildSession certifyingSession,
            Certification requestedCertification) throws SQLException {
        // Revalidate immutable generation provenance on this transaction's connection, including
        // exact retries. Do not perform an out-of-transaction catalog repository read under locks.
        certifications.validatePolicyProvenance(connection, requestedCertification.inputLock(), policy);
        Optional<Certification> sameId = certifications.find(
                connection,
                requestedCertification.inputLock().tenantId(),
                requestedCertification.certificationId(),
                true);
        Optional<Certification> latestCandidate = certifications.findLatestForCandidate(
                connection,
                requestedCertification.inputLock().tenantId(),
                requestedCertification.inputLock().buildSessionId(),
                requestedCertification.inputLock().candidateId(),
                requestedCertification.inputLock().candidateHash(),
                true);
        BuildSession current = sessions.find(
                        connection,
                        expectedSession.tenantId(),
                        expectedSession.buildSessionId(),
                        true)
                .orElseThrow(() -> fail(SESSION_CONFLICT, "trusted BuildSession is missing"));

            // A missing-row SELECT does not lock the key range in READ COMMITTED. If a
            // Certification appeared while this call waited for BuildSession, release that
            // session lock and restart. The next attempt locks Certification first, preserving
            // the global Certification -> BuildSession order shared with dispatch.
            if (sameId.isEmpty() && certifications.find(
                        connection,
                        requestedCertification.inputLock().tenantId(),
                        requestedCertification.certificationId(),
                        false).isPresent()) {
                throw RestartWithCertificationLock.INSTANCE;
            }
            if (latestCandidate.isEmpty() && certifications.findLatestForCandidate(
                        connection,
                        requestedCertification.inputLock().tenantId(),
                        requestedCertification.inputLock().buildSessionId(),
                        requestedCertification.inputLock().candidateId(),
                        requestedCertification.inputLock().candidateHash(),
                        false).isPresent()) {
                throw RestartWithCertificationLock.INSTANCE;
            }
            if (sameId.isPresent()) {
                Certification canonical = sameId.orElseThrow();
                if (sameRequestIdentity(canonical, requestedCertification)
                        && canonical.status() == CertificationStatus.REQUESTED) {
                    requireExactCertifyingAuthority(
                            current,
                            requestedCertification.inputLock(),
                            canonical.createdAt(),
                            requestedCertification.createdAt());
                    connection.commit();
                    return new AgentPackCertificationRequestOutcome(
                            CertificationRequestDisposition.EXISTING_EXACT, canonical, current);
                }
                AgentPackCertificationRequestOutcome conflict = conflict(canonical, current);
                connection.commit();
                return conflict;
            }

            if (latestCandidate.filter(certification ->
                            certification.status() == CertificationStatus.REQUESTED)
                    .isPresent()) {
                AgentPackCertificationRequestOutcome conflict =
                        conflict(latestCandidate.orElseThrow(), current);
                connection.commit();
                return conflict;
            }

            if (!current.equals(expectedSession)) {
                throw fail(
                        SESSION_CONFLICT,
                        "BuildSession changed before the certification request could commit");
            }
            requireCandidateReadyAuthority(
                    current,
                    requestedCertification.inputLock(),
                    requestedCertification.createdAt());
            if (!current.beginAgentPackCertification(requestedCertification.createdAt())
                    .equals(certifyingSession)) {
                throw fail(
                        SESSION_CONFLICT,
                        "requested BuildSession transition is not the exact certification authority");
            }

            certifications.create(connection, requestedCertification);
            if (!sessions.compareAndSet(connection, current, certifyingSession)) {
                throw fail(
                        SESSION_CONFLICT,
                        "BuildSession certification transition lost its version CAS");
            }
            connection.commit();
            return new AgentPackCertificationRequestOutcome(
                    CertificationRequestDisposition.CREATED,
                    requestedCertification,
                    certifyingSession);
    }

    private void validateArguments(
            BuildSession expectedSession,
            BuildSession certifyingSession,
            Certification requestedCertification) {
        Objects.requireNonNull(expectedSession, "expectedSession");
        Objects.requireNonNull(certifyingSession, "certifyingSession");
        Objects.requireNonNull(requestedCertification, "requestedCertification");
        CertificationInputLock lock = requestedCertification.inputLock();
        if (requestedCertification.status() != CertificationStatus.REQUESTED
                || requestedCertification.version() != 0
                || !requestedCertification.createdAt().equals(requestedCertification.updatedAt())
                || !ProductLineId.AGENT_PACK.equals(lock.productLineId())
                || lock.artifactType() != CertifiedArtifactType.AGENT_PACK
                || !expectedSession.tenantId().equals(lock.tenantId())
                || !expectedSession.buildSessionId().equals(lock.buildSessionId())
                || !expectedSession.productLineId().equals(lock.productLineId())
                || !expectedSession.currentCandidateId().filter(lock.candidateId()::equals).isPresent()
                || !expectedSession.currentCandidateHash().filter(lock.candidateHash()::equals).isPresent()) {
            throw fail(
                    IDENTITY_CONFLICT,
                    "Certification request does not match the exact Agent Pack session/candidate identity");
        }
        if (!policy.admits(lock)) {
            throw fail(
                    POLICY_CONFLICT,
                    "Certification input lock does not match the trusted Agent Pack policy");
        }
        if (expectedSession.status() == BuildSessionStatus.CERTIFYING
                && expectedSession.currentPhase() == BuildSessionPhase.CERTIFY) {
            if (!expectedSession.equals(certifyingSession)) {
                throw fail(
                        SESSION_CONFLICT,
                        "an exact certification retry must not advance BuildSession again");
            }
            requireLiveAuthority(expectedSession, lock, requestedCertification.createdAt());
            return;
        }
        requireCandidateReadyAuthority(expectedSession, lock, requestedCertification.createdAt());
        BuildSession exact;
        try {
            exact = expectedSession.beginAgentPackCertification(requestedCertification.createdAt());
        } catch (RuntimeException invalid) {
            throw fail(SESSION_CONFLICT, "BuildSession cannot begin Agent Pack certification");
        }
        if (!exact.equals(certifyingSession)) {
            throw fail(
                    SESSION_CONFLICT,
                    "certifying BuildSession is not the exact one-version transition");
        }
    }

    private static void requireCandidateReadyAuthority(
            BuildSession session,
            CertificationInputLock lock,
            java.time.Instant requestedAt) {
        requireLiveAuthority(session, lock, requestedAt);
        if (session.status() != BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE
                || session.currentPhase() != BuildSessionPhase.HUMAN_RELEASE_REVIEW) {
            throw fail(
                    SESSION_CONFLICT,
                    "BuildSession is not at the exact candidate-ready certification boundary");
        }
    }

    private static void requireLiveAuthority(
            BuildSession session,
            CertificationInputLock lock,
            java.time.Instant requestedAt) {
        if (!ProductLineId.AGENT_PACK.equals(session.productLineId())
                || !session.tenantId().equals(lock.tenantId())
                || !session.buildSessionId().equals(lock.buildSessionId())
                || !session.currentCandidateId().filter(lock.candidateId()::equals).isPresent()
                || !session.currentCandidateHash().filter(lock.candidateHash()::equals).isPresent()
                || session.currentCertificationId().isPresent()
                || session.cancellationRequestedAt().isPresent()
                || requestedAt.isBefore(session.updatedAt())
                || !requestedAt.isBefore(session.deadlineAt())) {
            throw fail(
                    SESSION_CONFLICT,
                    "BuildSession is not a live exact Agent Pack certification authority");
        }
    }

    private static void requireExactCertifyingAuthority(
            BuildSession session,
            CertificationInputLock lock,
            java.time.Instant canonicalRequestedAt,
            java.time.Instant observedAt) {
        requireLiveAuthority(session, lock, canonicalRequestedAt);
        if (session.status() != BuildSessionStatus.CERTIFYING
                || session.currentPhase() != BuildSessionPhase.CERTIFY
                || !observedAt.isBefore(session.deadlineAt())) {
            throw fail(
                    SESSION_CONFLICT,
                    "stored Certification has no exact CERTIFYING/CERTIFY BuildSession authority");
        }
    }

    private static boolean sameRequestIdentity(Certification stored, Certification requested) {
        return stored.certificationId().equals(requested.certificationId())
                && stored.inputLock().equals(requested.inputLock())
                && stored.inputLockArtifact().equals(requested.inputLockArtifact());
    }

    private static AgentPackCertificationRequestOutcome conflict(
            Certification canonical,
            BuildSession current) {
        if (!canonical.inputLock().tenantId().equals(current.tenantId())
                || !canonical.inputLock().buildSessionId().equals(current.buildSessionId())) {
            throw fail(
                    IDENTITY_CONFLICT,
                    "conflicting Certification is not owned by the locked BuildSession");
        }
        return new AgentPackCertificationRequestOutcome(
                CertificationRequestDisposition.CONFLICT, canonical, current);
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static AgentPackCertificationRequestException fail(String code, String message) {
        return new AgentPackCertificationRequestException(code, message);
    }

    private static final class RestartWithCertificationLock extends RuntimeException {
        private static final RestartWithCertificationLock INSTANCE =
                new RestartWithCertificationLock();

        private RestartWithCertificationLock() {
            super(null, null, false, false);
        }
    }
}
