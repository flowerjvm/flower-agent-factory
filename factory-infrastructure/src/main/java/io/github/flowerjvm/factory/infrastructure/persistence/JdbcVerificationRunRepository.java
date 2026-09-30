package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.requireCas;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.requireSame;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC VerificationRun ledger with immutable lock identity and atomic version CAS. */
public final class JdbcVerificationRunRepository implements VerificationRunRepository {
    private static final String INSERT = """
            INSERT INTO factory_verification_run (
                verification_run_id, tenant_id, build_session_id, candidate_id, candidate_hash,
                gate_profile, toolchain_lock_hash, fixture_set_hash, status, result_manifest_ref,
                result_manifest_hash, terminal_code, disposition, active_key,
                started_at, completed_at, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND = """
            SELECT * FROM factory_verification_run
            WHERE tenant_id = ? AND verification_run_id = ?
            """;

    private static final String FIND_LATEST_FOR_CANDIDATE = """
            SELECT * FROM factory_verification_run
            WHERE tenant_id = ? AND build_session_id = ? AND candidate_id = ?
                AND candidate_hash = ? AND gate_profile = ?
            ORDER BY created_at DESC, verification_run_id DESC
            LIMIT 1
            """;

    private static final String FIND_LATEST_FOR_EXACT_CANDIDATE = """
            SELECT * FROM factory_verification_run
            WHERE tenant_id = ? AND build_session_id = ? AND candidate_id = ? AND candidate_hash = ?
            ORDER BY created_at DESC, verification_run_id DESC
            FETCH FIRST 1 ROW ONLY
            """;

    private static final String CAS = """
            UPDATE factory_verification_run SET
                status = ?, result_manifest_ref = ?, result_manifest_hash = ?, terminal_code = ?,
                disposition = ?, active_key = ?, started_at = ?, completed_at = ?,
                version = ?, updated_at = ?
            WHERE tenant_id = ? AND verification_run_id = ? AND version = ?
            """;

    private final DataSource dataSource;

    public JdbcVerificationRunRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void create(VerificationRun verificationRun) {
        Objects.requireNonNull(verificationRun, "verificationRun");
        withConnection(dataSource, "create VerificationRun", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                bindInsert(statement, verificationRun);
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<VerificationRun> find(TenantId tenantId, VerificationRunId verificationRunId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(verificationRunId, "verificationRunId");
        return withConnection(dataSource, "find VerificationRun", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, verificationRunId.value());
                return findOne(statement);
            }
        });
    }

    @Override
    public Optional<VerificationRun> findLatestForCandidate(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            CandidateId candidateId,
            ContentHash candidateHash,
            String gateProfile) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(candidateHash, "candidateHash");
        if (gateProfile == null || gateProfile.isBlank()) {
            throw new IllegalArgumentException("gateProfile must not be blank");
        }
        return withConnection(dataSource, "find latest VerificationRun for candidate", connection -> {
            return findLatestForCandidate(connection, tenantId, buildSessionId, candidateId,
                    candidateHash, gateProfile, false);
        });
    }

    Optional<VerificationRun> findLatestForCandidate(
            Connection connection, TenantId tenantId, BuildSessionId buildSessionId, CandidateId candidateId,
            ContentHash candidateHash, String gateProfile, boolean forUpdate) throws SQLException {
        String sql = FIND_LATEST_FOR_CANDIDATE + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, buildSessionId.value());
            statement.setString(3, candidateId.value());
            statement.setString(4, candidateHash.sha256());
            statement.setString(5, gateProfile);
            return findOne(statement);
        }
    }

    @Override
    public Optional<VerificationRun> findLatestForCandidate(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            CandidateId candidateId,
            ContentHash candidateHash) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(candidateHash, "candidateHash");
        return withConnection(dataSource, "find latest VerificationRun for exact candidate", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_LATEST_FOR_EXACT_CANDIDATE)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, buildSessionId.value());
                statement.setString(3, candidateId.value());
                statement.setString(4, candidateHash.sha256());
                return findOne(statement);
            }
        });
    }

    @Override
    public boolean compareAndSet(VerificationRun expected, VerificationRun next) {
        validateCas(expected, next);
        return withConnection(dataSource, "CAS VerificationRun", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(CAS)) {
                int index = 1;
                statement.setString(index++, next.status().name());
                setOptionalText(statement, index++, next.resultManifestRef().map(ArtifactReference::value));
                setOptionalText(statement, index++, next.resultManifestHash().map(ContentHash::sha256));
                setOptionalText(statement, index++, next.terminalCode());
                setOptionalText(statement, index++, next.disposition().map(VerificationDisposition::name));
                setOptionalText(statement, index++, activeKey(next));
                setOptionalInstant(statement, index++, next.startedAt());
                setOptionalInstant(statement, index++, next.completedAt());
                statement.setLong(index++, next.version());
                setInstant(statement, index++, next.updatedAt());
                statement.setString(index++, expected.tenantId().value());
                statement.setString(index++, expected.verificationRunId().value());
                statement.setLong(index, expected.version());
                return statement.executeUpdate() == 1;
            }
        });
    }

    private static void validateCas(VerificationRun expected, VerificationRun next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        requireCas(expected.version(), next.version());
        requireSame(expected.verificationRunId().equals(next.verificationRunId()), "verificationRunId");
        requireSame(expected.tenantId().equals(next.tenantId()), "tenantId");
        requireSame(expected.buildSessionId().equals(next.buildSessionId()), "buildSessionId");
        requireSame(expected.candidateId().equals(next.candidateId()), "candidateId");
        requireSame(expected.candidateHash().equals(next.candidateHash()), "candidateHash");
        requireSame(expected.gateProfile().equals(next.gateProfile()), "gateProfile");
        requireSame(expected.toolchainLockHash().equals(next.toolchainLockHash()), "toolchainLockHash");
        requireSame(expected.fixtureSetHash().equals(next.fixtureSetHash()), "fixtureSetHash");
        requireSame(expected.createdAt().equals(next.createdAt()), "createdAt");
        if (expected.status().isTerminal()) {
            throw new IllegalArgumentException("terminal VerificationRun must not be overwritten");
        }
        boolean starts = expected.status() == VerificationRunStatus.REQUESTED
                && next.status() == VerificationRunStatus.RUNNING;
        boolean completes = expected.status() == VerificationRunStatus.RUNNING
                && next.status().isTerminal();
        if (!starts && !completes) {
            throw new IllegalArgumentException("illegal VerificationRun lifecycle transition");
        }
    }

    private static void bindInsert(PreparedStatement statement, VerificationRun verificationRun)
            throws SQLException {
        int index = 1;
        statement.setString(index++, verificationRun.verificationRunId().value());
        statement.setString(index++, verificationRun.tenantId().value());
        statement.setString(index++, verificationRun.buildSessionId().value());
        statement.setString(index++, verificationRun.candidateId().value());
        statement.setString(index++, verificationRun.candidateHash().sha256());
        statement.setString(index++, verificationRun.gateProfile());
        statement.setString(index++, verificationRun.toolchainLockHash().sha256());
        statement.setString(index++, verificationRun.fixtureSetHash().sha256());
        statement.setString(index++, verificationRun.status().name());
        setOptionalText(statement, index++, verificationRun.resultManifestRef().map(ArtifactReference::value));
        setOptionalText(statement, index++, verificationRun.resultManifestHash().map(ContentHash::sha256));
        setOptionalText(statement, index++, verificationRun.terminalCode());
        setOptionalText(statement, index++, verificationRun.disposition().map(VerificationDisposition::name));
        setOptionalText(statement, index++, activeKey(verificationRun));
        setOptionalInstant(statement, index++, verificationRun.startedAt());
        setOptionalInstant(statement, index++, verificationRun.completedAt());
        statement.setLong(index++, verificationRun.version());
        setInstant(statement, index++, verificationRun.createdAt());
        setInstant(statement, index, verificationRun.updatedAt());
    }

    private static Optional<VerificationRun> findOne(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
        }
    }

    private static VerificationRun map(ResultSet resultSet) throws SQLException {
        return new VerificationRun(
                new VerificationRunId(resultSet.getString("verification_run_id")),
                new TenantId(resultSet.getString("tenant_id")),
                new BuildSessionId(resultSet.getString("build_session_id")),
                new CandidateId(resultSet.getString("candidate_id")),
                new ContentHash(resultSet.getString("candidate_hash")),
                resultSet.getString("gate_profile"),
                new ContentHash(resultSet.getString("toolchain_lock_hash")),
                new ContentHash(resultSet.getString("fixture_set_hash")),
                VerificationRunStatus.valueOf(resultSet.getString("status")),
                getOptionalText(resultSet, "result_manifest_ref").map(ArtifactReference::new),
                getOptionalText(resultSet, "result_manifest_hash").map(ContentHash::new),
                getOptionalText(resultSet, "terminal_code"),
                getOptionalText(resultSet, "disposition").map(VerificationDisposition::valueOf),
                getOptionalInstant(resultSet, "started_at"),
                getOptionalInstant(resultSet, "completed_at"),
                resultSet.getLong("version"),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "updated_at"));
    }

    private static Optional<String> activeKey(VerificationRun run) {
        if (run.status().isTerminal()) {
            return Optional.empty();
        }
        return Optional.of(segment(run.tenantId().value())
                + segment(run.buildSessionId().value())
                + segment(run.candidateId().value())
                + segment(run.candidateHash().sha256())
                + segment(run.gateProfile()));
    }

    private static String segment(String value) {
        return value.codePointCount(0, value.length()) + ":" + value;
    }
}
