package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.application.verification.VerificationActionDuplicateOwnerLookup;
import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Tenant/action/key lookup for verification owners that failed before durable intent creation. */
public final class JdbcVerificationActionDuplicateOwnerLookup
        implements VerificationActionDuplicateOwnerLookup {
    private static final String FIND = """
            SELECT owner_run_id FROM action_duplicate
            WHERE tenant_id = ? AND action_id = ? AND idempotency_key = ?
            """;
    private final DataSource dataSource;

    public JdbcVerificationActionDuplicateOwnerLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<String> findOwnerRunId(String tenantId, String actionId, String key) {
        return withConnection(dataSource, "find verification Action duplicate owner", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND)) {
                statement.setString(1, requireText(tenantId, "tenantId"));
                statement.setString(2, requireText(actionId, "actionId"));
                statement.setString(3, requireText(key, "idempotencyKey"));
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    String owner = requireText(resultSet.getString("owner_run_id"), "ownerRunId");
                    if (resultSet.next()) {
                        throw new IllegalStateException("multiple verification duplicate owners");
                    }
                    return Optional.of(owner);
                }
            }
        });
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
