package io.github.flowerjvm.factory.application.certification;

/** Atomic create-and-transition result for one deterministic certification request. */
public enum CertificationRequestDisposition {
    CREATED,
    EXISTING_EXACT,
    CONFLICT
}
