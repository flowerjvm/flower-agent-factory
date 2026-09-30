package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.application.work.WorkerActionDuplicateOwnerLookup;
import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Resolves the canonical owner retained by Action Runtime 0.3.3's duplicate ledger. */
public final class JdbcWorkerActionDuplicateOwnerLookup implements WorkerActionDuplicateOwnerLookup {
    private static final String FIND_OWNER = """
            SELECT owner_run_id
            FROM action_duplicate
            WHERE tenant_id = ?
              AND action_id = ?
              AND idempotency_key = ?
            """;

    private final DataSource dataSource;

    public JdbcWorkerActionDuplicateOwnerLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<String> findOwnerRunId(String tenantId, String actionId, String idempotencyKey) {
        tenantId = requireText(tenantId, "tenantId");
        actionId = requireText(actionId, "actionId");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        String scopedTenantId = tenantId;
        String scopedActionId = actionId;
        String scopedIdempotencyKey = idempotencyKey;
        return withConnection(dataSource, "find Action duplicate owner", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_OWNER)) {
                statement.setString(1, scopedTenantId);
                statement.setString(2, scopedActionId);
                statement.setString(3, scopedIdempotencyKey);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    String ownerRunId = requireText(resultSet.getString("owner_run_id"), "ownerRunId");
                    if (resultSet.next()) {
                        throw new IllegalStateException("Multiple Action duplicate owners match one Factory key");
                    }
                    return Optional.of(ownerRunId);
                }
            }
        });
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
