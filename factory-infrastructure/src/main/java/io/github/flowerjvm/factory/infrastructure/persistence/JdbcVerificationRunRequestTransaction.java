package io.github.flowerjvm.factory.infrastructure.persistence;

import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunRequestTransaction;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * JDBC create-or-return-canonical boundary for a deterministic VerificationRun request.
 *
 * <p>The database primary key and active-candidate key are the atomic decision. A retry may
 * observe a later lifecycle snapshot for the same immutable request, but a reused deterministic id
 * or a second id for the same active candidate is rejected.
 */
public final class JdbcVerificationRunRequestTransaction
        implements VerificationRunRequestTransaction {
    private final JdbcVerificationRunRepository verificationRuns;

    public JdbcVerificationRunRequestTransaction(DataSource dataSource) {
        this.verificationRuns = new JdbcVerificationRunRepository(
                Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public VerificationRun request(VerificationRun requested) {
        validateNewRequest(requested);
        try {
            verificationRuns.create(requested);
            return requested;
        } catch (DuplicateLedgerRecordException duplicate) {
            return verificationRuns
                    .find(requested.tenantId(), requested.verificationRunId())
                    .filter(stored -> hasSameRequestIdentity(stored, requested))
                    .orElseThrow(() -> new FactoryPersistenceException(
                            "verification request conflicts with an existing deterministic identity",
                            duplicate));
        }
    }

    private static void validateNewRequest(VerificationRun requested) {
        Objects.requireNonNull(requested, "requested");
        if (requested.status() != VerificationRunStatus.REQUESTED
                || requested.version() != 0
                || !requested.createdAt().equals(requested.updatedAt())) {
            throw new IllegalArgumentException(
                    "verification request must be a newly created REQUESTED snapshot");
        }
    }

    private static boolean hasSameRequestIdentity(
            VerificationRun stored, VerificationRun requested) {
        return stored.verificationRunId().equals(requested.verificationRunId())
                && stored.tenantId().equals(requested.tenantId())
                && stored.buildSessionId().equals(requested.buildSessionId())
                && stored.candidateId().equals(requested.candidateId())
                && stored.candidateHash().equals(requested.candidateHash())
                && stored.gateProfile().equals(requested.gateProfile())
                && stored.toolchainLockHash().equals(requested.toolchainLockHash())
                && stored.fixtureSetHash().equals(requested.fixtureSetHash());
    }
}
