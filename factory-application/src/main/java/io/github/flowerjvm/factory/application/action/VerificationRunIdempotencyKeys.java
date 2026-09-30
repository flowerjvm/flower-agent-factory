package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Version-bound logical execution key; candidate isolation is deliberately supplied by the trusted
 * duplicate visibility scope instead of being hidden inside this transport key.
 */
public final class VerificationRunIdempotencyKeys {
    private static final String VERSION = "factory.verification.run.idempotency.v1";

    private VerificationRunIdempotencyKeys() {}

    /** Binds the governed attempt to the candidate's immutable dependency repository lock. */
    public static String derive(VerificationRun run, CandidateVersion candidate) {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        return derive(run, candidate, run.version());
    }

    /** Derives a historical logical-attempt key using repository-verified candidate locks. */
    public static String derive(
            VerificationRun run, CandidateVersion candidate, long requestedVersion) {
        if (run == null || candidate == null || !candidate.candidateId().equals(run.candidateId())) {
            throw new IllegalArgumentException("candidate must own the verification run");
        }
        return derive(
                run,
                requestedVersion,
                candidate.sourceManifestRef().value(),
                candidate.sourceHash().sha256(),
                candidate.dependencyLockRef().value(),
                candidate.dependencyLockHash().sha256(),
                candidate.toolchainLockRef().value(),
                candidate.toolchainLockHash().sha256());
    }

    private static String derive(
            VerificationRun run,
            long requestedVersion,
            String sourceManifestRef,
            String sourceHash,
            String dependencyLockRef,
            String dependencyLockHash,
            String toolchainLockRef,
            String toolchainLockHash) {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        if (requestedVersion < 0) {
            throw new IllegalArgumentException("requestedVersion must not be negative");
        }
        String material = String.join(
                "\n",
                VERSION,
                run.tenantId().value(),
                run.gateProfile(),
                sourceManifestRef,
                sourceHash,
                dependencyLockRef,
                dependencyLockHash,
                toolchainLockRef,
                toolchainLockHash,
                run.fixtureSetHash().sha256(),
                Long.toString(requestedVersion));
        try {
            return "verification-run-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
