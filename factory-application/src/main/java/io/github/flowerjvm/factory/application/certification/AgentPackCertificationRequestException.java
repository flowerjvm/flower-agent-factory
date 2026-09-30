package io.github.flowerjvm.factory.application.certification;

/** Stable fail-closed rejection from trusted Agent Pack certification request construction. */
public final class AgentPackCertificationRequestException extends IllegalStateException {
    private final String code;

    public AgentPackCertificationRequestException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a bounded uppercase stable code");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
