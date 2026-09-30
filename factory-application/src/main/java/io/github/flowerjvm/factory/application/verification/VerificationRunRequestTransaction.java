package io.github.flowerjvm.factory.application.verification;

/** Atomic create-or-return-canonical boundary for one active immutable-candidate verification. */
@FunctionalInterface
public interface VerificationRunRequestTransaction {
    VerificationRun request(VerificationRun requested);
}
