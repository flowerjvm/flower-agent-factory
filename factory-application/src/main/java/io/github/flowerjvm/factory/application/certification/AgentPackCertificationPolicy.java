package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable trusted admission policy for issuing the first AGENT_PACK certifications. */
public record AgentPackCertificationPolicy(
        CertificationArtifactLock productContractBundle,
        String gateProfile,
        ContentHash verificationFixtureSetHash,
        String sourceLockAlgorithmId,
        String certificationProfile,
        String factoryVersion,
        String flowerVersion,
        String actionRuntimeVersion) {

    private static final Pattern STABLE_ID =
            Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final Pattern FLOATING_SEGMENT =
            Pattern.compile("(?i)(?:^|[:/@._-])(latest|head)(?:$|[:/@._-])");

    public AgentPackCertificationPolicy {
        Objects.requireNonNull(productContractBundle, "productContractBundle");
        gateProfile = requireStableId(gateProfile, "gateProfile");
        Objects.requireNonNull(verificationFixtureSetHash, "verificationFixtureSetHash");
        sourceLockAlgorithmId = requireStableId(sourceLockAlgorithmId, "sourceLockAlgorithmId");
        certificationProfile = requireStableId(certificationProfile, "certificationProfile");
        factoryVersion = requireResolvedVersion(factoryVersion, "factoryVersion");
        flowerVersion = requireResolvedVersion(flowerVersion, "flowerVersion");
        actionRuntimeVersion = requireResolvedVersion(actionRuntimeVersion, "actionRuntimeVersion");
    }

    /** Compares every authority-bearing admission value with the persisted exact input lock. */
    public boolean matches(CertificationInputLock lock) {
        return lock != null
                && ProductLineId.AGENT_PACK.equals(lock.productLineId())
                && lock.artifactType() == CertifiedArtifactType.AGENT_PACK
                && productContractBundle.equals(lock.productContractBundle())
                && gateProfile.equals(lock.gateProfile())
                && verificationFixtureSetHash.equals(lock.verificationFixtureSetHash())
                && sourceLockAlgorithmId.equals(lock.sourceLockAlgorithmId())
                && certificationProfile.equals(lock.certificationProfile())
                && factoryVersion.equals(lock.factoryVersion())
                && flowerVersion.equals(lock.flowerVersion())
                && actionRuntimeVersion.equals(lock.actionRuntimeVersion());
    }

    private static String requireStableId(String value, String name) {
        value = requireText(value, name);
        if (!STABLE_ID.matcher(value).matches() || FLOATING_SEGMENT.matcher(value).find()) {
            throw new IllegalArgumentException(name + " must be an exact lowercase stable id");
        }
        return value;
    }

    private static String requireResolvedVersion(String value, String name) {
        value = requireText(value, name);
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("snapshot")
                || FLOATING_SEGMENT.matcher(value).find()
                || value.indexOf('*') >= 0
                || value.indexOf('?') >= 0
                || value.indexOf('[') >= 0
                || value.indexOf(']') >= 0
                || value.indexOf('{') >= 0
                || value.indexOf('}') >= 0) {
            throw new IllegalArgumentException(name + " must be an immutable resolved version");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null
                || value.isBlank()
                || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }
}
