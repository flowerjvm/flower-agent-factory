package io.github.flowerjvm.factory.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Native locks and commit evidence for preparation, not successful Worker production. */
@Testcontainers
class JdbcAgentPackWorkPreparationNativePostgresqlIT {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void identicalPreparationsUseSeparateConnectionsAndCommitOneOrderAndRequestedAttempt() throws Exception {
        JdbcAgentPackWorkPreparationTransactionTest.assertConcurrentPreparation(fixture("preparation_race"));
    }

    @Test
    void cancellationBeforeTheSessionLockPreventsAnyOrderOrAttempt() throws Exception {
        JdbcAgentPackWorkPreparationTransactionTest.assertCancellationBeforeLock(fixture("preparation_cancel"));
    }

    private static JdbcAgentPackWorkPreparationTransactionTest.Fixture fixture(String schema) throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute("CREATE SCHEMA " + schema); }
        source.setCurrentSchema(schema);
        return JdbcAgentPackWorkPreparationTransactionTest.Fixture.create(source, schema);
    }
}
