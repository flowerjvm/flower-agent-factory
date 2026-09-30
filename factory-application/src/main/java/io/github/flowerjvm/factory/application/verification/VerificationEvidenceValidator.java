package io.github.flowerjvm.factory.application.verification;

/** Strict read-side validation before durable verification evidence can authorize human review. */
@FunctionalInterface
public interface VerificationEvidenceValidator {
    boolean isReviewEligible(VerificationRun run);
}
