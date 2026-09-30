package io.github.flowerjvm.factory.contracts.ids;

/** Identity of certification evidence bound to an exact candidate and input lock. */
public record CertificationId(String value) {
    public CertificationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("certificationId must not be blank");
        }
    }
}
