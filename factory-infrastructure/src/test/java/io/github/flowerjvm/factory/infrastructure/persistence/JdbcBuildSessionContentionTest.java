package io.github.flowerjvm.factory.infrastructure.persistence;

import org.junit.jupiter.api.Test;

class JdbcBuildSessionContentionTest {
    @Test
    void realThreadsStartingFromOneVersionProduceOneWinner() throws Exception {
        var dataSource = FactoryDatabaseMigrationsTest.h2("contention");
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcBuildSessionContention.assertOneCasWinner(dataSource, "h2", 12);
    }
}
