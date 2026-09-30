package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Insert-only JDBC repository for immutable WorkOrders. */
public final class JdbcWorkOrderRepository implements WorkOrderRepository {
    private static final String INSERT = """
            INSERT INTO factory_work_order (
                work_order_id, tenant_id, build_session_id, phase, purpose, revision,
                supersedes_work_order_id, candidate_id, base_revision, instruction_artifact_ref,
                instruction_hash, input_artifact_manifest_ref, input_manifest_hash, workspace_ref,
                allowed_read_paths_json, allowed_write_paths_json, required_capabilities_json,
                expected_output_schema_id, expected_output_schema_version, policy_snapshot_ref,
                deadline_at, max_attempts, logical_idempotency_key, created_by_type,
                created_by_ref, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND = """
            SELECT * FROM factory_work_order
            WHERE tenant_id = ? AND work_order_id = ?
            """;

    private static final String FIND_LATEST_BY_SESSION_AND_PHASE = """
            SELECT * FROM factory_work_order
            WHERE tenant_id = ? AND build_session_id = ? AND phase = ?
            ORDER BY revision DESC, created_at DESC, work_order_id DESC
            FETCH FIRST 1 ROW ONLY
            """;

    private final DataSource dataSource;
    private final JdbcJsonCodec json;

    public JdbcWorkOrderRepository(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcWorkOrderRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.json = new JdbcJsonCodec(Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    @Override
    public void create(WorkOrder workOrder) {
        Objects.requireNonNull(workOrder, "workOrder");
        withConnection(dataSource, "create WorkOrder", connection -> {
            create(connection, workOrder);
            return null;
        });
    }

    void create(Connection connection, WorkOrder workOrder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            bindInsert(statement, workOrder);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<WorkOrder> find(TenantId tenantId, WorkOrderId workOrderId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workOrderId, "workOrderId");
        return withConnection(dataSource, "find WorkOrder", connection -> find(connection, tenantId, workOrderId));
    }

    Optional<WorkOrder> find(Connection connection, TenantId tenantId, WorkOrderId workOrderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, workOrderId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<WorkOrder> findLatestByBuildSessionAndPhase(
            TenantId tenantId, BuildSessionId buildSessionId, String phase) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("phase must not be blank");
        }
        return withConnection(dataSource, "find latest WorkOrder by BuildSession and phase", connection ->
                findLatestByBuildSessionAndPhase(connection, tenantId, buildSessionId, phase));
    }

    Optional<WorkOrder> findLatestByBuildSessionAndPhase(
            Connection connection, TenantId tenantId, BuildSessionId buildSessionId, String phase) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_LATEST_BY_SESSION_AND_PHASE)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, buildSessionId.value());
            statement.setString(3, phase);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private void bindInsert(PreparedStatement statement, WorkOrder workOrder) throws SQLException {
        int index = 1;
        statement.setString(index++, workOrder.workOrderId().value());
        statement.setString(index++, workOrder.tenantId().value());
        statement.setString(index++, workOrder.buildSessionId().value());
        statement.setString(index++, workOrder.phase());
        statement.setString(index++, workOrder.purpose());
        statement.setInt(index++, workOrder.revision());
        setOptionalText(statement, index++, workOrder.supersedesWorkOrderId().map(WorkOrderId::value));
        setOptionalText(statement, index++, workOrder.candidateId().map(CandidateId::value));
        setOptionalText(statement, index++, workOrder.baseRevision());
        statement.setString(index++, workOrder.instructionArtifactRef().value());
        statement.setString(index++, workOrder.instructionHash().sha256());
        statement.setString(index++, workOrder.inputArtifactManifestRef().value());
        statement.setString(index++, workOrder.inputManifestHash().sha256());
        statement.setString(index++, workOrder.workspaceRef());
        statement.setString(index++, json.writeStrings(workOrder.allowedReadPaths(), true));
        statement.setString(index++, json.writeStrings(workOrder.allowedWritePaths(), true));
        statement.setString(index++, json.writeCapabilities(workOrder.requiredCapabilities()));
        statement.setString(index++, workOrder.expectedOutputSchemaId());
        statement.setString(index++, workOrder.expectedOutputSchemaVersion());
        statement.setString(index++, workOrder.policySnapshotRef().value());
        setInstant(statement, index++, workOrder.deadlineAt());
        statement.setInt(index++, workOrder.maxAttempts());
        statement.setString(index++, workOrder.logicalIdempotencyKey());
        statement.setString(index++, workOrder.createdByType().name());
        statement.setString(index++, workOrder.createdByRef());
        setInstant(statement, index, workOrder.createdAt());
    }

    private WorkOrder map(ResultSet resultSet) throws SQLException {
        return new WorkOrder(
                new WorkOrderId(resultSet.getString("work_order_id")),
                new TenantId(resultSet.getString("tenant_id")),
                new BuildSessionId(resultSet.getString("build_session_id")),
                resultSet.getString("phase"),
                resultSet.getString("purpose"),
                resultSet.getInt("revision"),
                getOptionalText(resultSet, "supersedes_work_order_id").map(WorkOrderId::new),
                getOptionalText(resultSet, "candidate_id").map(CandidateId::new),
                getOptionalText(resultSet, "base_revision"),
                new ArtifactReference(resultSet.getString("instruction_artifact_ref")),
                new ContentHash(resultSet.getString("instruction_hash")),
                new ArtifactReference(resultSet.getString("input_artifact_manifest_ref")),
                new ContentHash(resultSet.getString("input_manifest_hash")),
                resultSet.getString("workspace_ref"),
                json.readStringList(resultSet.getString("allowed_read_paths_json")),
                json.readStringList(resultSet.getString("allowed_write_paths_json")),
                json.readCapabilities(resultSet.getString("required_capabilities_json")),
                resultSet.getString("expected_output_schema_id"),
                resultSet.getString("expected_output_schema_version"),
                new ArtifactReference(resultSet.getString("policy_snapshot_ref")),
                getInstant(resultSet, "deadline_at"),
                resultSet.getInt("max_attempts"),
                resultSet.getString("logical_idempotency_key"),
                WorkOrderCreatorType.valueOf(resultSet.getString("created_by_type")),
                resultSet.getString("created_by_ref"),
                getInstant(resultSet, "created_at"));
    }
}
