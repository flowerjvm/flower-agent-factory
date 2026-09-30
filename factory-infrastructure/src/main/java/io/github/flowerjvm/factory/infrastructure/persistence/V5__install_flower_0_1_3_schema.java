package io.github.flowerjvm.factory.infrastructure.persistence;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Applies Flower 0.1.3's packaged durable Flow checkpoint schema without copying its SQL.
 * A future Flower upgrade must add a new host migration rather than changing this migration.
 */
final class V5__install_flower_0_1_3_schema extends BaseJavaMigration {
    private static final String FLOWER_CHECKPOINT_RESOURCE =
            "flower/persistence/jdbc/%s/schema.sql";

    @Override
    public void migrate(Context context) throws Exception {
        String resourcePath = FLOWER_CHECKPOINT_RESOURCE.formatted(
                dialect(context.getConnection().getMetaData()));
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new FlywayException("Missing pinned Flower 0.1.3 schema resource: " + resourcePath);
        }
        ScriptUtils.executeSqlScript(context.getConnection(), resource);
    }

    private static String dialect(DatabaseMetaData metadata) throws SQLException {
        String product = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("postgresql")) {
            return "postgresql";
        }
        if (product.equals("h2")) {
            return "h2";
        }
        throw new FlywayException(
                "Factory PR3 supports PostgreSQL in production and H2 for fast tests; found "
                        + metadata.getDatabaseProductName());
    }
}

