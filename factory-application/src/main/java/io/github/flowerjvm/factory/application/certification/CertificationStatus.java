package io.github.flowerjvm.factory.application.certification;

/** Durable lifecycle of one exact certification subject lock. */
public enum CertificationStatus {
    REQUESTED,
    CERTIFIED,
    NOT_CERTIFIED,
    REVOKED;

    public boolean isTerminalForIssuance() {
        return this != REQUESTED;
    }
}

