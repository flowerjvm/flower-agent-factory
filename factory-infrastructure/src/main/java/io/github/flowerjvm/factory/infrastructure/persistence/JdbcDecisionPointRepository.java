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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC DecisionPoint ledger that preserves immutable review subjects and uses version CAS. */
public final class JdbcDecisionPointRepository implements DecisionPointRepository {
    private static final String INSERT = """
            INSERT INTO factory_decision_point (
                decision_point_id, tenant_id, build_session_id, type, status, subject_type,
                subject_id, subject_version, subject_hash, question_artifact_ref,
                options_schema_id, required_permissions_json, minimum_approvers,
                policy_snapshot_ref, opened_at, due_at, decided_at, terminal_decision_id, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND = """
            SELECT * FROM factory_decision_point
            WHERE tenant_id = ? AND decision_point_id = ?
            """;

    private static final String FIND_LATEST_BY_SESSION_AND_TYPE = """
            SELECT * FROM factory_decision_point
            WHERE tenant_id = ? AND build_session_id = ? AND type = ?
            ORDER BY opened_at DESC, decision_point_id DESC
            FETCH FIRST 1 ROW ONLY
            """;

    private static final String FIND_LATEST_BY_SESSION_AND_SUBJECT = """
            SELECT * FROM factory_decision_point
            WHERE tenant_id = ? AND build_session_id = ? AND type = ? AND subject_type = ?
                AND subject_id = ? AND subject_hash = ?
            ORDER BY opened_at DESC, decision_point_id DESC
            FETCH FIRST 1 ROW ONLY
            """;

    private static final String CAS = """
            UPDATE factory_decision_point SET
                status = ?, decided_at = ?, terminal_decision_id = ?, version = ?
            WHERE tenant_id = ? AND decision_point_id = ? AND version = ?
            """;

    private final DataSource dataSource;
    private final JdbcJsonCodec json;

    public JdbcDecisionPointRepository(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcDecisionPointRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.json = new JdbcJsonCodec(Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    @Override
    public void create(DecisionPoint decisionPoint) {
        Objects.requireNonNull(decisionPoint, "decisionPoint");
        withConnection(dataSource, "create DecisionPoint", connection -> {
            create(connection, decisionPoint);
            return null;
        });
    }

    void create(Connection connection, DecisionPoint decisionPoint) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            bindInsert(statement, decisionPoint);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<DecisionPoint> find(TenantId tenantId, DecisionPointId decisionPointId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(decisionPointId, "decisionPointId");
        return withConnection(dataSource, "find DecisionPoint", connection -> {
            return find(connection, tenantId, decisionPointId, false);
        });
    }

    Optional<DecisionPoint> find(
            Connection connection,
            TenantId tenantId,
            DecisionPointId decisionPointId,
            boolean forUpdate) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(decisionPointId, "decisionPointId");
        String sql = forUpdate ? FIND + " FOR UPDATE" : FIND;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, decisionPointId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<DecisionPoint> findLatestByBuildSessionAndType(
            TenantId tenantId, BuildSessionId buildSessionId, String type) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        return withConnection(dataSource, "find latest DecisionPoint by BuildSession and type", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_LATEST_BY_SESSION_AND_TYPE)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, buildSessionId.value());
                statement.setString(3, type);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<DecisionPoint> findLatestByBuildSessionAndSubject(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            String type,
            String subjectType,
            String subjectId,
            ContentHash subjectHash) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        if (type == null || type.isBlank()
                || subjectType == null || subjectType.isBlank()
                || subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("type and subject type/id must not be blank");
        }
        Objects.requireNonNull(subjectHash, "subjectHash");
        return withConnection(dataSource, "find latest DecisionPoint by BuildSession and subject", connection -> {
            return findLatestByBuildSessionAndSubject(connection, tenantId, buildSessionId,
                    type, subjectType, subjectId, subjectHash);
        });
    }

    Optional<DecisionPoint> findLatestByBuildSessionAndSubject(
            Connection connection, TenantId tenantId, BuildSessionId buildSessionId, String type,
            String subjectType, String subjectId, ContentHash subjectHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_LATEST_BY_SESSION_AND_SUBJECT)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, buildSessionId.value());
            statement.setString(3, type);
            statement.setString(4, subjectType);
            statement.setString(5, subjectId);
            statement.setString(6, subjectHash.sha256());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public boolean compareAndSet(DecisionPoint expected, DecisionPoint next) {
        validateCas(expected, next);
        return withConnection(dataSource, "CAS DecisionPoint", connection -> compareAndSet(connection, expected, next));
    }

    boolean compareAndSet(Connection connection, DecisionPoint expected, DecisionPoint next)
            throws SQLException {
        validateCas(expected, next);
        try (PreparedStatement statement = connection.prepareStatement(CAS)) {
            int index = 1;
            statement.setString(index++, next.status().name());
            setOptionalInstant(statement, index++, next.decidedAt());
            setOptionalText(statement, index++, next.terminalDecisionId().map(DecisionId::value));
            statement.setLong(index++, next.version());
            statement.setString(index++, expected.tenantId().value());
            statement.setString(index++, expected.decisionPointId().value());
            statement.setLong(index, expected.version());
            return statement.executeUpdate() == 1;
        }
    }

    private static void validateCas(DecisionPoint expected, DecisionPoint next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        requireCas(expected.version(), next.version());
        requireSame(expected.decisionPointId().equals(next.decisionPointId()), "decisionPointId");
        requireSame(expected.tenantId().equals(next.tenantId()), "tenantId");
        requireSame(expected.buildSessionId().equals(next.buildSessionId()), "buildSessionId");
        requireSame(expected.type().equals(next.type()), "type");
        requireSame(expected.subjectType().equals(next.subjectType()), "subjectType");
        requireSame(expected.subjectId().equals(next.subjectId()), "subjectId");
        requireSame(expected.subjectVersion() == next.subjectVersion(), "subjectVersion");
        requireSame(expected.subjectHash().equals(next.subjectHash()), "subjectHash");
        requireSame(expected.questionArtifactRef().equals(next.questionArtifactRef()), "questionArtifactRef");
        requireSame(expected.optionsSchemaId().equals(next.optionsSchemaId()), "optionsSchemaId");
        requireSame(expected.requiredPermissions().equals(next.requiredPermissions()), "requiredPermissions");
        requireSame(expected.minimumApprovers() == next.minimumApprovers(), "minimumApprovers");
        requireSame(expected.policySnapshotRef().equals(next.policySnapshotRef()), "policySnapshotRef");
        requireSame(expected.openedAt().equals(next.openedAt()), "openedAt");
        requireSame(expected.dueAt().equals(next.dueAt()), "dueAt");
        if (expected.status().isTerminal()) {
            throw new IllegalArgumentException("terminal DecisionPoint must not be overwritten");
        }
    }

    private void bindInsert(PreparedStatement statement, DecisionPoint decisionPoint) throws SQLException {
        int index = 1;
        statement.setString(index++, decisionPoint.decisionPointId().value());
        statement.setString(index++, decisionPoint.tenantId().value());
        statement.setString(index++, decisionPoint.buildSessionId().value());
        statement.setString(index++, decisionPoint.type());
        statement.setString(index++, decisionPoint.status().name());
        statement.setString(index++, decisionPoint.subjectType());
        statement.setString(index++, decisionPoint.subjectId());
        statement.setLong(index++, decisionPoint.subjectVersion());
        statement.setString(index++, decisionPoint.subjectHash().sha256());
        statement.setString(index++, decisionPoint.questionArtifactRef().value());
        statement.setString(index++, decisionPoint.optionsSchemaId());
        statement.setString(index++, json.writeStrings(decisionPoint.requiredPermissions(), false));
        statement.setInt(index++, decisionPoint.minimumApprovers());
        statement.setString(index++, decisionPoint.policySnapshotRef().value());
        setInstant(statement, index++, decisionPoint.openedAt());
        setInstant(statement, index++, decisionPoint.dueAt());
        setOptionalInstant(statement, index++, decisionPoint.decidedAt());
        setOptionalText(statement, index++, decisionPoint.terminalDecisionId().map(DecisionId::value));
        statement.setLong(index, decisionPoint.version());
    }

    private DecisionPoint map(ResultSet resultSet) throws SQLException {
        return new DecisionPoint(
                new DecisionPointId(resultSet.getString("decision_point_id")),
                new TenantId(resultSet.getString("tenant_id")),
                new BuildSessionId(resultSet.getString("build_session_id")),
                resultSet.getString("type"),
                DecisionPointStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("subject_type"),
                resultSet.getString("subject_id"),
                resultSet.getLong("subject_version"),
                new ContentHash(resultSet.getString("subject_hash")),
                new ArtifactReference(resultSet.getString("question_artifact_ref")),
                resultSet.getString("options_schema_id"),
                json.readStringSet(resultSet.getString("required_permissions_json")),
                resultSet.getInt("minimum_approvers"),
                new ArtifactReference(resultSet.getString("policy_snapshot_ref")),
                getInstant(resultSet, "opened_at"),
                getInstant(resultSet, "due_at"),
                getOptionalInstant(resultSet, "decided_at"),
                getOptionalText(resultSet, "terminal_decision_id").map(DecisionId::new),
                resultSet.getLong("version"));
    }
}
