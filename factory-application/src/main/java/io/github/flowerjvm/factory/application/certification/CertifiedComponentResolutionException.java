package io.github.flowerjvm.factory.application.certification;

/** Stable fail-closed rejection from the downstream certified-component read gate. */
public final class CertifiedComponentResolutionException extends RuntimeException {
    private final String code;

    public CertifiedComponentResolutionException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a bounded uppercase stable code");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}

