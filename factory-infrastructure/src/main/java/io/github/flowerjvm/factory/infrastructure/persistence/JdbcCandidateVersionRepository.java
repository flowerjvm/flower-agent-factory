package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Insert-only JDBC repository for immutable, tenant-owned candidate snapshots. */
public final class JdbcCandidateVersionRepository implements CandidateVersionRepository {
    private static final String INSERT = """
            INSERT INTO factory_candidate_version (
                candidate_id, tenant_id, build_session_id, parent_candidate_id,
                source_manifest_ref, source_hash, dependency_lock_ref, dependency_lock_hash,
                toolchain_lock_ref, toolchain_lock_hash, status, created_by_work_order_id,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND = """
            SELECT * FROM factory_candidate_version
            WHERE tenant_id = ? AND candidate_id = ?
            """;

    private static final String FIND_BY_SESSION_AND_WORK_ORDER = """
            SELECT * FROM factory_candidate_version
            WHERE tenant_id = ? AND build_session_id = ? AND created_by_work_order_id = ?
            """;

    private final DataSource dataSource;

    public JdbcCandidateVersionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void create(CandidateVersion candidateVersion) {
        Objects.requireNonNull(candidateVersion, "candidateVersion");
        withConnection(dataSource, "create CandidateVersion", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                bindInsert(statement, candidateVersion);
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<CandidateVersion> find(TenantId tenantId, CandidateId candidateId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(candidateId, "candidateId");
        return withConnection(dataSource, "find CandidateVersion", connection -> {
            return find(connection, tenantId, candidateId);
        });
    }

    Optional<CandidateVersion> find(Connection connection, TenantId tenantId, CandidateId candidateId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, candidateId.value());
            return findOne(statement);
        }
    }

    @Override
    public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            WorkOrderId createdByWorkOrderId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(createdByWorkOrderId, "createdByWorkOrderId");
        return withConnection(dataSource, "find CandidateVersion by BuildSession and WorkOrder", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_BY_SESSION_AND_WORK_ORDER)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, buildSessionId.value());
                statement.setString(3, createdByWorkOrderId.value());
                return findOne(statement);
            }
        });
    }

    private static void bindInsert(PreparedStatement statement, CandidateVersion candidateVersion)
            throws SQLException {
        int index = 1;
        statement.setString(index++, candidateVersion.candidateId().value());
        statement.setString(index++, candidateVersion.tenantId().value());
        statement.setString(index++, candidateVersion.buildSessionId().value());
        setOptionalText(statement, index++, candidateVersion.parentCandidateId().map(CandidateId::value));
        statement.setString(index++, candidateVersion.sourceManifestRef().value());
        statement.setString(index++, candidateVersion.sourceHash().sha256());
        statement.setString(index++, candidateVersion.dependencyLockRef().value());
        statement.setString(index++, candidateVersion.dependencyLockHash().sha256());
        statement.setString(index++, candidateVersion.toolchainLockRef().value());
        statement.setString(index++, candidateVersion.toolchainLockHash().sha256());
        statement.setString(index++, candidateVersion.status().name());
        statement.setString(index++, candidateVersion.createdByWorkOrderId().value());
        setInstant(statement, index, candidateVersion.createdAt());
    }

    private static Optional<CandidateVersion> findOne(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
        }
    }

    private static CandidateVersion map(ResultSet resultSet) throws SQLException {
        return new CandidateVersion(
                new CandidateId(resultSet.getString("candidate_id")),
                new TenantId(resultSet.getString("tenant_id")),
                new BuildSessionId(resultSet.getString("build_session_id")),
                getOptionalText(resultSet, "parent_candidate_id").map(CandidateId::new),
                new ArtifactReference(resultSet.getString("source_manifest_ref")),
                new ContentHash(resultSet.getString("source_hash")),
                new ArtifactReference(resultSet.getString("dependency_lock_ref")),
                new ContentHash(resultSet.getString("dependency_lock_hash")),
                new ArtifactReference(resultSet.getString("toolchain_lock_ref")),
                new ContentHash(resultSet.getString("toolchain_lock_hash")),
                CandidateVersionStatus.valueOf(resultSet.getString("status")),
                new WorkOrderId(resultSet.getString("created_by_work_order_id")),
                getInstant(resultSet, "created_at"));
    }
}
