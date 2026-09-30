package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationArtifactCodec;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntent;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntentStatus;
import io.github.flowerjvm.factory.application.certification.CertificationIssuanceTransaction;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Serializes the final Certification transition with its owning BuildSession authority.
 *
 * <p>The global order is Certification then BuildSession, matching dispatch and retry paths.
 */
public final class JdbcCertificationIssuanceTransaction
        implements CertificationIssuanceTransaction {
    private final DataSource dataSource;
    private final JdbcCertificationRepository certifications;
    private final JdbcBuildSessionRepository sessions;

    public JdbcCertificationIssuanceTransaction(
            DataSource dataSource,
            CertificationArtifactCodec codec,
            WorkerProtocolArtifactDecoder workerDecoder) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.certifications = new JdbcCertificationRepository(dataSource, codec, workerDecoder);
        this.sessions = new JdbcBuildSessionRepository(dataSource);
    }

    @Override
    public CertificationIssuanceCommit commit(
            CertificationDispatchIntent intent,
            Certification expected,
            Certification proposed) {
        validateProposal(intent, expected, proposed);
        return withConnection(dataSource, "commit Certification issuance", connection ->
                inTransaction(connection, intent, expected, proposed));
    }

    private CertificationIssuanceCommit inTransaction(
            Connection connection,
            CertificationDispatchIntent intent,
            Certification expected,
            Certification proposed) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            // Lock order is intentional and shared by every Certification multi-ledger path.
            Certification current = certifications
                    .find(connection, intent.tenantId(), intent.certificationId(), true)
                    .orElseThrow(() -> new IllegalStateException(
                            "Certification is not visible in trusted tenant scope"));
            if (current.equals(proposed)) {
                connection.commit();
                return new CertificationIssuanceCommit(current, false);
            }
            if (!current.equals(expected)) {
                throw new IllegalStateException(
                        "Certification is not the exact REQUESTED issuance version");
            }

            BuildSession session = sessions
                    .find(
                            connection,
                            intent.tenantId(),
                            expected.inputLock().buildSessionId(),
                            true)
                    .orElseThrow(() -> new IllegalStateException(
                            "BuildSession is not visible in trusted tenant scope"));
            validateSession(intent, expected, proposed, session);

            if (!certifications.compareAndSet(connection, expected, proposed)) {
                throw new IllegalStateException(
                        "locked Certification issuance transition was not persisted");
            }
            Certification canonical = certifications
                    .find(connection, intent.tenantId(), intent.certificationId(), false)
                    .orElseThrow(() -> new IllegalStateException(
                            "committed Certification disappeared before transaction completion"));
            if (!canonical.equals(proposed)) {
                throw new IllegalStateException(
                        "Certification issuance transaction produced a non-canonical result");
            }
            connection.commit();
            return new CertificationIssuanceCommit(canonical, true);
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void validateProposal(
            CertificationDispatchIntent intent,
            Certification expected,
            Certification proposed) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(proposed, "proposed");
        if (intent.status() != CertificationDispatchIntentStatus.RUNNING
                || intent.claimToken().isEmpty()
                || intent.leaseUntil().isEmpty()
                || !expected.certificationId().equals(intent.certificationId())
                || !expected.inputLock().tenantId().equals(intent.tenantId())
                || !expected.inputLockArtifact().hash().equals(intent.inputLockManifestHash())
                || expected.version() != intent.expectedCertificationVersion()
                || expected.status() != CertificationStatus.REQUESTED
                || !proposed.certificationId().equals(expected.certificationId())
                || !proposed.inputLock().equals(expected.inputLock())
                || !proposed.inputLockArtifact().equals(expected.inputLockArtifact())
                || !proposed.createdAt().equals(expected.createdAt())
                || proposed.status() != CertificationStatus.CERTIFIED
                || proposed.version() != expected.version() + 1
                || proposed.actionRunId().filter(intent.actionRunId()::equals).isEmpty()
                || !proposed.updatedAt().isBefore(intent.deadlineAt())) {
            throw new IllegalArgumentException(
                    "Certification issuance proposal is not bound to the exact active intent");
        }
    }

    private static void validateSession(
            CertificationDispatchIntent intent,
            Certification expected,
            Certification proposed,
            BuildSession session) {
        var lock = expected.inputLock();
        boolean exact = session.tenantId().equals(intent.tenantId())
                && session.buildSessionId().equals(lock.buildSessionId())
                && session.productLineId().equals(ProductLineId.AGENT_PACK)
                && session.status() == BuildSessionStatus.CERTIFYING
                && session.currentPhase() == BuildSessionPhase.CERTIFY
                && session.currentCandidateId().filter(lock.candidateId()::equals).isPresent()
                && session.currentCandidateHash().filter(lock.candidateHash()::equals).isPresent()
                && session.currentCertificationId().isEmpty()
                && session.cancellationRequestedAt().isEmpty()
                && session.deadlineAt().equals(intent.deadlineAt())
                && !proposed.updatedAt().isBefore(session.updatedAt())
                && proposed.updatedAt().isBefore(session.deadlineAt());
        if (!exact) {
            throw new IllegalStateException(
                    "BuildSession lost live Agent Pack certification authority before issuance commit");
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
