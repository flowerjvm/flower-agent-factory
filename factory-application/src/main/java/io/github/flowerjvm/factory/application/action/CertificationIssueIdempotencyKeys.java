package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact input-lock and version-bound logical key for one Certification issuance attempt. */
public final class CertificationIssueIdempotencyKeys {
    private static final String VERSION = "factory.certification.issue.idempotency.v1";

    private CertificationIssueIdempotencyKeys() {}

    public static String derive(Certification certification) {
        if (certification == null) {
            throw new IllegalArgumentException("certification must not be null");
        }
        return derive(certification, certification.version());
    }

    /** Derives the original logical-attempt key for an authorized historical replay. */
    public static String derive(Certification certification, long requestedVersion) {
        if (certification == null) {
            throw new IllegalArgumentException("certification must not be null");
        }
        if (requestedVersion < 0) {
            throw new IllegalArgumentException("requestedVersion must not be negative");
        }
        var lock = certification.inputLock();
        StringBuilder material = new StringBuilder();
        append(material, VERSION);
        append(material, certification.certificationId().value());
        append(material, lock.tenantId().value());
        append(material, lock.productLineId().value());
        append(material, lock.artifactType().name());
        append(material, lock.buildSessionId().value());
        append(material, lock.generationWorkOrderId().value());
        append(material, lock.candidateId().value());
        append(material, lock.candidateHash().sha256());
        append(material, lock.sourceManifest());
        append(material, lock.dependencyLock());
        append(material, lock.toolchainLock());
        append(material, lock.generationInputManifest());
        append(material, lock.productContractBundle());
        append(material, lock.apiSignatureIndex());
        append(material, lock.sourceLockAlgorithmId());
        append(material, lock.gateProfile());
        append(material, lock.verificationRunId().value());
        append(material, lock.verificationActionRunId());
        append(material, lock.verificationResultManifest());
        append(material, lock.verificationFixtureSetHash().sha256());
        append(material, lock.policySnapshot());
        append(material, lock.compatibilityDescriptor());
        append(material, lock.certificationProfile());
        append(material, lock.factoryVersion());
        append(material, lock.flowerVersion());
        append(material, lock.actionRuntimeVersion());
        append(material, certification.inputLockArtifact());
        append(material, Long.toString(requestedVersion));
        try {
            return "certification-issue-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void append(StringBuilder material, CertificationArtifactLock lock) {
        append(material, lock.reference().value());
        append(material, lock.hash().sha256());
    }

    private static void append(StringBuilder material, String value) {
        material.append(value.length()).append(':').append(value).append('\n');
    }
}
