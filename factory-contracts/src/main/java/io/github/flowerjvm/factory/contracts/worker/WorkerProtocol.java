package io.github.flowerjvm.factory.contracts.worker;

/** Stable protocol identifiers shared by Factory and a Coding Worker adapter. */
public final class WorkerProtocol {
    public static final String DISPATCH_SCHEMA_VERSION = "factory.coding-worker-dispatch.v1";
    public static final String COMPLETION_SCHEMA_VERSION = "factory.coding-worker-completion.v1";
    public static final String RESULT_SCHEMA_VERSION = "factory.coding-worker-result-manifest.v1";

    private WorkerProtocol() {}
}
