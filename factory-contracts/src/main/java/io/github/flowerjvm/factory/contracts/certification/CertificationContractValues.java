package io.github.flowerjvm.factory.contracts.certification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

final class CertificationContractValues {
    private static final Pattern STABLE_ID =
            Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final Pattern FLOATING_SEGMENT =
            Pattern.compile("(?i)(?:^|[:/@._-])(latest|head)(?:$|[:/@._-])");

    private CertificationContractValues() {}

    static String requireStableId(String value, String name, int maximumLength) {
        value = requireText(value, name, maximumLength);
        if (!STABLE_ID.matcher(value).matches() || FLOATING_SEGMENT.matcher(value).find()) {
            throw new IllegalArgumentException(name + " must be an exact lowercase stable id");
        }
        return value;
    }

    static String requireExactIdentity(String value, String name, int maximumLength) {
        value = requireText(value, name, maximumLength);
        if (FLOATING_SEGMENT.matcher(value).find() || containsFloatingSyntax(value)) {
            throw new IllegalArgumentException(name + " must not contain a floating reference");
        }
        return value;
    }

    static String requireResolvedVersion(String value, String name) {
        value = requireExactIdentity(value, name, 128);
        if (value.toLowerCase(java.util.Locale.ROOT).contains("snapshot")) {
            throw new IllegalArgumentException(name + " must be an immutable resolved version");
        }
        return value;
    }

    static ArtifactReference requireExactReference(ArtifactReference value, String name) {
        Objects.requireNonNull(value, name);
        requireExactIdentity(value.value(), name, 512);
        return value;
    }

    static ProductLineId requireAgentPackLine(ProductLineId value) {
        Objects.requireNonNull(value, "productLineId");
        if (!ProductLineId.AGENT_PACK.equals(value)) {
            throw new IllegalArgumentException("only the current agent-pack product line is supported");
        }
        return value;
    }

    static CertifiedArtifactType requireAgentPackType(CertifiedArtifactType value) {
        Objects.requireNonNull(value, "artifactType");
        if (value != CertifiedArtifactType.AGENT_PACK) {
            throw new IllegalArgumentException("only AGENT_PACK certification is supported");
        }
        return value;
    }

    static Instant requireCanonicalInstant(Instant value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.equals(value.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException(name + " must use microsecond precision");
        }
        return value;
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }

    private static boolean containsFloatingSyntax(String value) {
        return value.indexOf('*') >= 0
                || value.indexOf('?') >= 0
                || value.indexOf('[') >= 0
                || value.indexOf(']') >= 0
                || value.indexOf('{') >= 0
                || value.indexOf('}') >= 0;
    }
}
