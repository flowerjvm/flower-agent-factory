package io.github.flowerjvm.factory.application.outbox;

/** Separates a fresh external call from status-only recovery of an uncertain call. */
public enum DispatchClaimPurpose {
    SUBMIT,
    RECONCILE
}
