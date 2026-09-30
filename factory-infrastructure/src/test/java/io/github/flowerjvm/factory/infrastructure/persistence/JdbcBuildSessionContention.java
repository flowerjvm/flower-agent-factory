package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

final class JdbcBuildSessionContention {
    private JdbcBuildSessionContention() {}

    static void assertOneCasWinner(DataSource dataSource, String prefix, int repetitions) throws Exception {
        for (int iteration = 0; iteration < repetitions; iteration++) {
            String suffix = prefix + "-" + iteration;
            var expected = PersistenceFixtures.buildSession(suffix);
            var contenderA = PersistenceFixtures.terminal(
                    expected,
                    BuildSessionStatus.SUCCEEDED,
                    BuildSessionPhase.COMPLETE,
                    "BUILD_SUCCEEDED");
            var contenderB = PersistenceFixtures.terminal(
                    expected,
                    BuildSessionStatus.CANCELLED,
                    expected.currentPhase(),
                    "BUILD_CANCELLED");
            var repository = new JdbcBuildSessionRepository(dataSource);
            repository.create(expected);

            var barrier = new CyclicBarrier(3);
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<Boolean> first = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return new JdbcBuildSessionRepository(dataSource).compareAndSet(expected, contenderA);
                });
                Future<Boolean> second = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return new JdbcBuildSessionRepository(dataSource).compareAndSet(expected, contenderB);
                });
                barrier.await(5, TimeUnit.SECONDS);

                List<Boolean> results = List.of(
                        first.get(5, TimeUnit.SECONDS),
                        second.get(5, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
                var stored = repository.find(expected.tenantId(), expected.buildSessionId()).orElseThrow();
                assertEquals(1, stored.version());
                assertTrue(stored.equals(contenderA) || stored.equals(contenderB));
            }
        }
    }
}
