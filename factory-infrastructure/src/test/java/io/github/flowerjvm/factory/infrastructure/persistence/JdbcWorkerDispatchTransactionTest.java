package io.github.flowerjvm.factory.infrastructure.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcWorkerDispatchTransactionTest {
    @Test
    void phaseChangeBeforeDispatchPreventsWorkerRunAndOutbox() throws Exception {
        JdbcDataSource dataSource = migratedH2("worker_dispatch_phase_first");

        JdbcWorkerDispatchTransactionConcurrency.assertPhaseChangeBeforeDispatchPreventsOutbox(
                dataSource, "h2");
    }

    @Test
    void cancellationBeforeDispatchPreventsWorkerRunAndOutbox() {
        JdbcDataSource dataSource = migratedH2("worker_dispatch_cancel_first");

        JdbcWorkerDispatchTransactionConcurrency.assertCancellationBeforeDispatchPreventsOutbox(
                dataSource, "h2");
    }

    @Test
    void codingWorkerBindingChangeBeforeDispatchPreventsWorkerRunAndOutbox() {
        JdbcDataSource dataSource = migratedH2("worker_dispatch_binding_first");

        JdbcWorkerDispatchTransactionConcurrency.assertBindingChangeBeforeDispatchPreventsOutbox(
                dataSource, "h2");
    }

    @Test
    void dispatchLockSerializesCancellationAfterOutboxCommit() throws Exception {
        JdbcDataSource dataSource = migratedH2("worker_dispatch_before_cancel");

        JdbcWorkerDispatchTransactionConcurrency.assertDispatchBeforeCancellationSerializes(
                dataSource, "h2");
    }

    @Test
    void deadlineElapsedWhileWaitingForLockPreventsOutbox() throws Exception {
        JdbcDataSource dataSource = migratedH2("worker_dispatch_deadline_wait");

        JdbcWorkerDispatchTransactionConcurrency.assertDeadlineElapsedWhileWaitingPreventsOutbox(
                dataSource, "h2");
    }

    @Test
    void outboxInsertFailureRollsBackWorkerRunCas() {
        JdbcDataSource dataSource = migratedH2("worker_dispatch_rollback");

        JdbcWorkerDispatchTransactionConcurrency.assertOutboxFailureRollsBackWorkerRun(
                dataSource, "h2");
    }

    @Test
    void attemptAndCapabilityChecksFailWithoutOutbox() {
        JdbcDataSource dataSource = migratedH2("worker_dispatch_domain_checks");

        JdbcWorkerDispatchTransactionConcurrency.assertAttemptAndCapabilityChecksPreventOutbox(
                dataSource, "h2");
    }

    private static JdbcDataSource migratedH2(String name) {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2(name);
        FactoryDatabaseMigrations.migrate(dataSource);
        return dataSource;
    }
}
