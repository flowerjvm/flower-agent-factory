package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.application.decision.Decision;
import io.github.flowerjvm.factory.application.decision.DecisionOutcome;
import io.github.flowerjvm.factory.application.decision.DecisionRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Insert-only JDBC repository for immutable, hash-bound human decisions. */
public final class JdbcDecisionRepository implements DecisionRepository {
    private static final String INSERT = """
            INSERT INTO factory_decision (
                decision_id, tenant_id, decision_point_id, request_idempotency_key, decision,
                selected_option, reason, decided_by, decider_authority_snapshot_ref,
                subject_hash, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND = """
            SELECT * FROM factory_decision
            WHERE tenant_id = ? AND decision_id = ?
            """;

    private static final String FIND_BY_REQUEST_KEY = """
            SELECT * FROM factory_decision
            WHERE tenant_id = ? AND decision_point_id = ? AND request_idempotency_key = ?
            """;

    private final DataSource dataSource;

    public JdbcDecisionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void create(Decision decision) {
        Objects.requireNonNull(decision, "decision");
        withConnection(dataSource, "create Decision", connection -> {
            create(connection, decision);
            return null;
        });
    }

    void create(Connection connection, Decision decision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            bindInsert(statement, decision);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<Decision> find(TenantId tenantId, DecisionId decisionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(decisionId, "decisionId");
        return withConnection(dataSource, "find Decision", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, decisionId.value());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<Decision> findByRequestIdempotencyKey(
            TenantId tenantId,
            DecisionPointId decisionPointId,
            String requestIdempotencyKey) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(decisionPointId, "decisionPointId");
        if (requestIdempotencyKey == null || requestIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("requestIdempotencyKey must not be blank");
        }
        return withConnection(dataSource, "find Decision by request key", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_BY_REQUEST_KEY)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, decisionPointId.value());
                statement.setString(3, requestIdempotencyKey);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
                }
            }
        });
    }

    private static void bindInsert(PreparedStatement statement, Decision decision) throws SQLException {
        int index = 1;
        statement.setString(index++, decision.decisionId().value());
        statement.setString(index++, decision.tenantId().value());
        statement.setString(index++, decision.decisionPointId().value());
        statement.setString(index++, decision.requestIdempotencyKey());
        statement.setString(index++, decision.decision().name());
        setOptionalText(statement, index++, decision.selectedOption());
        setOptionalText(statement, index++, decision.reason());
        statement.setString(index++, decision.decidedBy());
        statement.setString(index++, decision.deciderAuthoritySnapshotRef().value());
        statement.setString(index++, decision.subjectHash().sha256());
        setInstant(statement, index, decision.createdAt());
    }

    private static Decision map(ResultSet resultSet) throws SQLException {
        return new Decision(
                new DecisionId(resultSet.getString("decision_id")),
                new TenantId(resultSet.getString("tenant_id")),
                new DecisionPointId(resultSet.getString("decision_point_id")),
                resultSet.getString("request_idempotency_key"),
                DecisionOutcome.valueOf(resultSet.getString("decision")),
                getOptionalText(resultSet, "selected_option"),
                getOptionalText(resultSet, "reason"),
                resultSet.getString("decided_by"),
                new ArtifactReference(resultSet.getString("decider_authority_snapshot_ref")),
                new ContentHash(resultSet.getString("subject_hash")),
                getInstant(resultSet, "created_at"));
    }
}
