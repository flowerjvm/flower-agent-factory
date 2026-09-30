package io.github.flowerjvm.factory.contracts.worker;

/** Typed result of querying the exact external operation owner tuple. */
public enum WorkerLookupState {
    FOUND,
    NOT_FOUND,
    UNAVAILABLE,
    UNKNOWN
}
