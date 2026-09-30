package io.github.flowerjvm.factory.application.work;

/** Append-only audit sink for accepted and rejected callback attempts. */
@FunctionalInterface
public interface WorkerCallbackSecurityAudit {
    void record(WorkerCallbackAuditEvent event);
}
