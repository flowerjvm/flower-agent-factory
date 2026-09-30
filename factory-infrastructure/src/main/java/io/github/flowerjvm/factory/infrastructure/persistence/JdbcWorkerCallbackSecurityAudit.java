package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.application.work.WorkerCallbackAuditEvent;
import io.github.flowerjvm.factory.application.work.WorkerCallbackSecurityAudit;
import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Append-only, payload-light JDBC audit for authenticated callback decisions. */
public final class JdbcWorkerCallbackSecurityAudit implements WorkerCallbackSecurityAudit {
    private static final String INSERT = """
            INSERT INTO factory_worker_callback_audit (
                audit_id, trusted_tenant_id, worker_binding_id, authenticated_principal_ref,
                event_id, worker_run_id, code, accepted, observed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DataSource dataSource;

    public JdbcWorkerCallbackSecurityAudit(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void record(WorkerCallbackAuditEvent event) {
        Objects.requireNonNull(event, "event");
        withConnection(dataSource, "append Worker callback security audit", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                int index = 1;
                statement.setString(index++, UUID.randomUUID().toString());
                setOptionalText(statement, index++, event.trustedTenantId()
                        .map(io.github.flowerjvm.factory.contracts.ids.TenantId::value));
                statement.setString(index++, event.workerBindingId());
                setOptionalText(statement, index++, event.authenticatedPrincipalRef());
                setOptionalText(statement, index++, event.eventId());
                setOptionalText(statement, index++, event.workerRunId());
                statement.setString(index++, event.code());
                statement.setBoolean(index++, event.accepted());
                setInstant(statement, index, event.observedAt());
                statement.executeUpdate();
                return null;
            }
        });
    }
}
