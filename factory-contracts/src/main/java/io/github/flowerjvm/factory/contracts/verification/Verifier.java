package io.github.flowerjvm.factory.contracts.verification;

/**
 * Boundary for deterministic Factory verification.
 *
 * <p>This operation may run build tools and must never be invoked synchronously from a Flower worker tick.
 */
public interface Verifier {
    VerificationResult verify(VerificationRequest request);
}
