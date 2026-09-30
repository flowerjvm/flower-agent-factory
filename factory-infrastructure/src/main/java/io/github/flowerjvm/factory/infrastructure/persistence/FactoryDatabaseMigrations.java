package io.github.flowerjvm.factory.infrastructure.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** Host-owned Flyway boundary for Factory tables and pinned upstream runtime schemas. */
public final class FactoryDatabaseMigrations {
    public static final String FACTORY_MIGRATION_LOCATION = "classpath:db/factory/migration";

    private FactoryDatabaseMigrations() {}

    /**
     * Creates the configured migrator without applying it. The host remains responsible for
     * choosing when migrations run.
     */
    public static Flyway configured(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(FACTORY_MIGRATION_LOCATION)
                .javaMigrations(
                        new V2__install_action_runtime_0_3_3_schema(),
                        new V5__install_flower_0_1_3_schema())
                .validateMigrationNaming(true)
                .load();
    }

    public static MigrateResult migrate(DataSource dataSource) {
        return configured(dataSource).migrate();
    }
}
