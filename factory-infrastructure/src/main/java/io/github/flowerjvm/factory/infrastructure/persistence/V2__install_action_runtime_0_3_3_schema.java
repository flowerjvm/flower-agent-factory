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
 * Applies the schemas shipped by Action Runtime 0.3.3 without copying their SQL into Factory.
 * A future runtime upgrade must add a new host migration rather than changing this migration.
 */
final class V2__install_action_runtime_0_3_3_schema extends BaseJavaMigration {
    private static final String ACTION_RUN_RESOURCE = "db/action_run/%s.sql";
    private static final String ACTION_DUPLICATE_RESOURCE = "db/action_duplicate/%s.sql";

    @Override
    public void migrate(Context context) throws Exception {
        String dialect = dialect(context.getConnection().getMetaData());
        execute(context, ACTION_RUN_RESOURCE.formatted(dialect));
        execute(context, ACTION_DUPLICATE_RESOURCE.formatted(dialect));
    }

    private static void execute(Context context, String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new FlywayException("Missing pinned Action Runtime schema resource: " + resourcePath);
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
                "Factory PR2 supports PostgreSQL in production and H2 for fast tests; found "
                        + metadata.getDatabaseProductName());
    }
}
