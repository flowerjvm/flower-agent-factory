package io.github.flowerjvm.factory.infrastructure.persistence;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Native JDBC claim precision, not a live verifier invocation or actual production evidence. */
@Testcontainers
class JdbcVerificationClaimNativePostgresqlIT {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void nativeNanosecondFirstUncertainAndExpiredClaimsReturnExactStoredOwnership() throws Exception {
        JdbcVerificationActionAcceptanceTest.assertCanonicalClaimPrecision(database("claim_precision"));
    }

    @Test
    void nativeNanosecondCancelledStartReturnsExactStoredOrphanWithNoVerifierEffect() throws Exception {
        JdbcVerificationActionAcceptanceTest.assertCanonicalBlockedClaim(database("blocked_precision"));
    }

    private static DataSource database(String schema) throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        source.setCurrentSchema(schema);
        return source;
    }
}
