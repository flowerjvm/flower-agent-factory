package io.github.flowerjvm.factory.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

final class JdbcPersistenceSupport {
    private JdbcPersistenceSupport() {}

    static <T> T withConnection(DataSource dataSource, String operation, SqlFunction<Connection, T> work) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } catch (SQLException exception) {
            throw translate(operation, exception);
        }
    }

    static RuntimeException translate(String operation, SQLException exception) {
        if ("23505".equals(exception.getSQLState())) {
            return new DuplicateLedgerRecordException(operation, exception);
        }
        return new FactoryPersistenceException(operation + " failed", exception);
    }

    static void requireCas(long expectedVersion, long nextVersion) {
        if (nextVersion != expectedVersion + 1) {
            throw new IllegalArgumentException("CAS next version must advance exactly once");
        }
    }

    static void requireSame(boolean condition, String field) {
        if (!condition) {
            throw new IllegalArgumentException("CAS must not change immutable " + field);
        }
    }

    static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setTimestamp(index, Timestamp.from(value));
    }

    static void setOptionalInstant(PreparedStatement statement, int index, Optional<Instant> value)
            throws SQLException {
        if (value.isPresent()) {
            setInstant(statement, index, value.orElseThrow());
        } else {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        }
    }

    static Instant getInstant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    static Optional<Instant> getOptionalInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? Optional.empty() : Optional.of(value.toInstant());
    }

    static void setOptionalText(PreparedStatement statement, int index, Optional<String> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.orElseThrow());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    static Optional<String> getOptionalText(ResultSet resultSet, String column) throws SQLException {
        return Optional.ofNullable(resultSet.getString(column));
    }

    @FunctionalInterface
    interface SqlFunction<I, O> {
        O apply(I input) throws SQLException;
    }
}
