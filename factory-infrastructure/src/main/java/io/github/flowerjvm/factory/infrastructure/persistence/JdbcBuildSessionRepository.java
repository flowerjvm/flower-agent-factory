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

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** PostgreSQL/H2 JDBC implementation whose only mutation is an atomic version CAS. */
public final class JdbcBuildSessionRepository implements BuildSessionRepository {
    private static final String INSERT = """
            INSERT INTO factory_build_session (
                build_session_id, tenant_id, project_id, product_line_id, request_idempotency_key,
                active_request_key, created_by,
                status, current_phase, requirements_artifact_ref, requirements_hash,
                selected_manager_worker_binding, selected_coding_worker_binding,
                current_blueprint_ref, current_candidate_id, current_candidate_hash,
                current_certification_id, repair_round, max_repair_rounds, started_at,
                deadline_at, cancellation_requested_at, terminal_code, terminal_message,
                version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND = """
            SELECT * FROM factory_build_session
            WHERE tenant_id = ? AND build_session_id = ?
            """;

    private static final String FIND_CANCELLING = """
            SELECT * FROM factory_build_session
            WHERE status = 'CANCELLING'
              AND updated_at <= ?
            ORDER BY updated_at, deadline_at, tenant_id, build_session_id
            FETCH FIRST ? ROWS ONLY
            """;

    private static final String FIND_CERTIFICATION_CONTINUATION_CANDIDATES = """
            SELECT * FROM factory_build_session
            WHERE product_line_id = 'agent-pack'
              AND current_certification_id IS NULL
              AND cancellation_requested_at IS NULL
              AND status NOT IN ('SUCCEEDED', 'CANCELLED', 'FAILED')
              AND updated_at <= ?
              AND (
                    (status = 'CANDIDATE_READY_FOR_RELEASE'
                     AND current_phase = 'human-release-review')
                 OR (status = 'CERTIFYING'
                     AND current_phase = 'certify')
              )
            ORDER BY updated_at, deadline_at, tenant_id, build_session_id
            FETCH FIRST ? ROWS ONLY
            """;

    private static final String FIND_REFERENCE_ASSEMBLY_FLOW_CANDIDATES = """
            SELECT * FROM factory_build_session
            WHERE product_line_id = 'reference-assembly'
              AND cancellation_requested_at IS NULL
              AND updated_at <= ?
              AND (
                    (status = 'RUNNING' AND current_phase IN (
                        'understand-customer', 'resolve-reuse-strategy',
                        'assemble-candidate', 'test', 'package-release'))
                 OR (status = 'WAITING_RELEASE_REVIEW'
                     AND current_phase = 'human-release-review')
              )
            ORDER BY updated_at, deadline_at, tenant_id, build_session_id
            FETCH FIRST ? ROWS ONLY
            """;

    private static final String CAS = """
            UPDATE factory_build_session SET
                status = ?, active_request_key = ?, current_phase = ?, selected_manager_worker_binding = ?,
                selected_coding_worker_binding = ?, current_blueprint_ref = ?,
                current_candidate_id = ?, current_candidate_hash = ?, current_certification_id = ?,
                repair_round = ?, deadline_at = ?, cancellation_requested_at = ?,
                terminal_code = ?, terminal_message = ?, version = ?, updated_at = ?
            WHERE tenant_id = ? AND build_session_id = ? AND product_line_id = ? AND version = ?
            """;

    private final DataSource dataSource;

    public JdbcBuildSessionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void create(BuildSession session) {
        Objects.requireNonNull(session, "session");
        withConnection(dataSource, "create BuildSession", connection -> {
            create(connection, session);
            return null;
        });
    }

    void create(Connection connection, BuildSession session) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            bindInsert(statement, session);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<BuildSession> find(TenantId tenantId, BuildSessionId buildSessionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        return withConnection(dataSource, "find BuildSession", connection -> find(connection, tenantId, buildSessionId, false));
    }

    @Override
    public List<BuildSession> findCancelling(Instant updatedBefore, int limit) {
        Objects.requireNonNull(updatedBefore, "updatedBefore");
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("cancellation scan limit must be between 1 and 1000");
        }
        return withConnection(dataSource, "scan cancelling BuildSessions", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_CANCELLING)) {
                setInstant(statement, 1, updatedBefore);
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<BuildSession> sessions = new ArrayList<>(Math.min(limit, 64));
                    while (resultSet.next()) {
                        sessions.add(map(resultSet));
                    }
                    return List.copyOf(sessions);
                }
            }
        });
    }

    @Override
    public List<BuildSession> findCertificationContinuationCandidates(
            Instant updatedBefore, int limit) {
        Objects.requireNonNull(updatedBefore, "updatedBefore");
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException(
                    "certification continuation scan limit must be between 1 and 1000");
        }
        return withConnection(dataSource, "scan certification continuation BuildSessions", connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement(FIND_CERTIFICATION_CONTINUATION_CANDIDATES)) {
                setInstant(statement, 1, updatedBefore);
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<BuildSession> sessions = new ArrayList<>(Math.min(limit, 64));
                    while (resultSet.next()) {
                        sessions.add(map(resultSet));
                    }
                    return List.copyOf(sessions);
                }
            }
        });
    }

    @Override
    public List<BuildSession> findReferenceAssemblyFlowCandidates(
            Instant updatedBefore, int limit) {
        Objects.requireNonNull(updatedBefore, "updatedBefore");
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException(
                    "Reference Assembly Flow scan limit must be between 1 and 1000");
        }
        return withConnection(dataSource, "scan Reference Assembly Flow BuildSessions", connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement(FIND_REFERENCE_ASSEMBLY_FLOW_CANDIDATES)) {
                setInstant(statement, 1, updatedBefore);
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<BuildSession> sessions = new ArrayList<>(Math.min(limit, 64));
                    while (resultSet.next()) {
                        sessions.add(map(resultSet));
                    }
                    return List.copyOf(sessions);
                }
            }
        });
    }

    Optional<BuildSession> find(
            Connection connection,
            TenantId tenantId,
            BuildSessionId buildSessionId,
            boolean forUpdate) throws SQLException {
        String sql = forUpdate ? FIND + " FOR UPDATE" : FIND;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, buildSessionId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public boolean compareAndSet(BuildSession expected, BuildSession next) {
        validateCas(expected, next);
        return withConnection(dataSource, "CAS BuildSession", connection -> compareAndSet(connection, expected, next));
    }

    boolean compareAndSet(Connection connection, BuildSession expected, BuildSession next) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CAS)) {
            int index = 1;
            statement.setString(index++, next.status().name());
            setOptionalText(statement, index++, activeRequestKey(next));
            statement.setString(index++, next.currentPhase().id());
            setOptionalText(statement, index++, next.selectedManagerWorkerBinding());
            setOptionalText(statement, index++, next.selectedCodingWorkerBinding());
            setOptionalArtifact(statement, index++, next.currentBlueprintRef());
            setOptionalId(statement, index++, next.currentCandidateId().map(CandidateId::value));
            setOptionalId(statement, index++, next.currentCandidateHash().map(ContentHash::sha256));
            setOptionalId(statement, index++, next.currentCertificationId().map(CertificationId::value));
            statement.setInt(index++, next.repairRound());
            setInstant(statement, index++, next.deadlineAt());
            setOptionalInstant(statement, index++, next.cancellationRequestedAt());
            setOptionalText(statement, index++, next.terminalCode());
            setOptionalText(statement, index++, next.terminalMessage());
            statement.setLong(index++, next.version());
            setInstant(statement, index++, next.updatedAt());
            statement.setString(index++, expected.tenantId().value());
            statement.setString(index++, expected.buildSessionId().value());
            statement.setString(index++, expected.productLineId().value());
            statement.setLong(index, expected.version());
            return statement.executeUpdate() == 1;
        }
    }

    private static void validateCas(BuildSession expected, BuildSession next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        requireCas(expected.version(), next.version());
        requireSame(expected.buildSessionId().equals(next.buildSessionId()), "buildSessionId");
        requireSame(expected.tenantId().equals(next.tenantId()), "tenantId");
        requireSame(expected.projectId().equals(next.projectId()), "projectId");
        requireSame(expected.productLineId().equals(next.productLineId()), "productLineId");
        requireSame(expected.requestIdempotencyKey().equals(next.requestIdempotencyKey()), "requestIdempotencyKey");
        requireSame(expected.createdBy().equals(next.createdBy()), "createdBy");
        requireSame(expected.requirementsArtifactRef().equals(next.requirementsArtifactRef()), "requirementsArtifactRef");
        requireSame(expected.requirementsHash().equals(next.requirementsHash()), "requirementsHash");
        requireSame(expected.maxRepairRounds() == next.maxRepairRounds(), "maxRepairRounds");
        requireSame(expected.startedAt().equals(next.startedAt()), "startedAt");
        requireSame(expected.createdAt().equals(next.createdAt()), "createdAt");
        if (expected.status().isTerminal()) {
            throw new IllegalArgumentException("terminal BuildSession must not be overwritten");
        }
    }

    private static void bindInsert(PreparedStatement statement, BuildSession session) throws SQLException {
        int index = 1;
        statement.setString(index++, session.buildSessionId().value());
        statement.setString(index++, session.tenantId().value());
        statement.setString(index++, session.projectId().value());
        statement.setString(index++, session.productLineId().value());
        statement.setString(index++, session.requestIdempotencyKey());
        setOptionalText(statement, index++, activeRequestKey(session));
        statement.setString(index++, session.createdBy());
        statement.setString(index++, session.status().name());
        statement.setString(index++, session.currentPhase().id());
        statement.setString(index++, session.requirementsArtifactRef().value());
        statement.setString(index++, session.requirementsHash().sha256());
        setOptionalText(statement, index++, session.selectedManagerWorkerBinding());
        setOptionalText(statement, index++, session.selectedCodingWorkerBinding());
        setOptionalArtifact(statement, index++, session.currentBlueprintRef());
        setOptionalId(statement, index++, session.currentCandidateId().map(CandidateId::value));
        setOptionalId(statement, index++, session.currentCandidateHash().map(ContentHash::sha256));
        setOptionalId(statement, index++, session.currentCertificationId().map(CertificationId::value));
        statement.setInt(index++, session.repairRound());
        statement.setInt(index++, session.maxRepairRounds());
        setInstant(statement, index++, session.startedAt());
        setInstant(statement, index++, session.deadlineAt());
        setOptionalInstant(statement, index++, session.cancellationRequestedAt());
        setOptionalText(statement, index++, session.terminalCode());
        setOptionalText(statement, index++, session.terminalMessage());
        statement.setLong(index++, session.version());
        setInstant(statement, index++, session.createdAt());
        setInstant(statement, index, session.updatedAt());
    }

    private static Optional<String> activeRequestKey(BuildSession session) {
        return session.status().isTerminal()
                ? Optional.empty()
                : Optional.of(session.requestIdempotencyKey());
    }

    private static BuildSession map(ResultSet resultSet) throws SQLException {
        return new BuildSession(
                new BuildSessionId(resultSet.getString("build_session_id")),
                new TenantId(resultSet.getString("tenant_id")),
                new ProjectId(resultSet.getString("project_id")),
                new ProductLineId(resultSet.getString("product_line_id")),
                resultSet.getString("request_idempotency_key"),
                resultSet.getString("created_by"),
                BuildSessionStatus.valueOf(resultSet.getString("status")),
                BuildSessionPhase.fromId(resultSet.getString("current_phase")),
                new ArtifactReference(resultSet.getString("requirements_artifact_ref")),
                new ContentHash(resultSet.getString("requirements_hash")),
                getOptionalText(resultSet, "selected_manager_worker_binding"),
                getOptionalText(resultSet, "selected_coding_worker_binding"),
                getOptionalText(resultSet, "current_blueprint_ref").map(ArtifactReference::new),
                getOptionalText(resultSet, "current_candidate_id").map(CandidateId::new),
                getOptionalText(resultSet, "current_candidate_hash").map(ContentHash::new),
                getOptionalText(resultSet, "current_certification_id").map(CertificationId::new),
                resultSet.getInt("repair_round"),
                resultSet.getInt("max_repair_rounds"),
                getInstant(resultSet, "started_at"),
                getInstant(resultSet, "deadline_at"),
                getOptionalInstant(resultSet, "cancellation_requested_at"),
                getOptionalText(resultSet, "terminal_code"),
                getOptionalText(resultSet, "terminal_message"),
                resultSet.getLong("version"),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "updated_at"));
    }

    private static void setOptionalArtifact(
            PreparedStatement statement, int index, Optional<ArtifactReference> value) throws SQLException {
        setOptionalId(statement, index, value.map(ArtifactReference::value));
    }

    private static void setOptionalId(PreparedStatement statement, int index, Optional<String> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.orElseThrow());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }
}
