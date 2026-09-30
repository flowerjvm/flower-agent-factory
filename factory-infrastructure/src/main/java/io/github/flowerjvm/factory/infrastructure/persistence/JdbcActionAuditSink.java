package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.flower.action.runtime.audit.AuditEvent;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/** Append-only durable adapter for Action Runtime audit events. */
public final class JdbcActionAuditSink implements AuditSink {
    private static final String INSERT = """
            INSERT INTO action_audit (
                event_id, event_type, proposal_id, action_id, run_id, trace_id,
                tenant_id, user_id, occurred_at, payload_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcActionAuditSink(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcActionAuditSink(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    @Override
    public void record(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        String payload = writePayload(event);
        withConnection(dataSource, "append Action audit event", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, event.eventId());
                statement.setString(2, event.type().name());
                statement.setString(3, event.proposalId());
                statement.setString(4, event.actionId());
                statement.setString(5, event.runId());
                statement.setString(6, event.traceId());
                statement.setString(7, event.tenantId());
                statement.setString(8, event.userId());
                setInstant(statement, 9, event.occurredAt());
                statement.setString(10, payload);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Action audit insert affected an unexpected row count");
                }
                return null;
            }
        });
    }

    private String writePayload(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event.payload());
        } catch (JsonProcessingException exception) {
            throw new FactoryPersistenceException("Action audit JSON serialization failed", exception);
        }
    }
}
