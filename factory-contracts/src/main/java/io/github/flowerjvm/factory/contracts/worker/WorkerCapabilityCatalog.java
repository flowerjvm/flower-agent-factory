package io.github.flowerjvm.factory.contracts.worker;

/** Stable capability values used by WorkOrder validation and adapter parity tests. */
public final class WorkerCapabilityCatalog {
    public static final WorkerCapability REPOSITORY_READ = capability("repository-read");
    public static final WorkerCapability BOUNDED_PATCH_WRITE = capability("bounded-patch-write");
    public static final WorkerCapability FILE_CREATE = capability("file-create");
    public static final WorkerCapability COMMAND_BUILD_TEST = capability("command-build-test");
    public static final WorkerCapability STRUCTURED_OUTPUT = capability("structured-output");
    public static final WorkerCapability PROGRESS_EVENTS = capability("progress-events");
    public static final WorkerCapability COOPERATIVE_CANCEL = capability("cooperative-cancel");
    public static final WorkerCapability SESSION_RESUME = capability("session-resume");
    public static final WorkerCapability USAGE_REPORTING = capability("usage-reporting");
    public static final WorkerCapability SANDBOX_ENFORCEMENT = capability("sandbox-enforcement");

    private WorkerCapabilityCatalog() {}

    private static WorkerCapability capability(String value) {
        return new WorkerCapability(value);
    }
}
