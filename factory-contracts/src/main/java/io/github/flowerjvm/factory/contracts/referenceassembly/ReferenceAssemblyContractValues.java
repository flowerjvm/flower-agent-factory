package io.github.flowerjvm.factory.contracts.referenceassembly;

import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class ReferenceAssemblyContractValues {
    private static final Pattern STABLE_ID =
            Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final Pattern STABLE_CODE =
            Pattern.compile("REFERENCE_ASSEMBLY_[A-Z0-9]+(?:_[A-Z0-9]+)*");
    private static final Pattern FLOATING_SEGMENT =
            Pattern.compile("(?i)(?:^|[:/@._-])(latest|head)(?:$|[:/@._-])");

    private ReferenceAssemblyContractValues() {}

    static String requireSchema(String value, String expected, String name) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException("unsupported " + name);
        }
        return value;
    }

    static ProductLineId requireProductLine(ProductLineId value) {
        Objects.requireNonNull(value, "productLineId");
        if (!ReferenceAssemblyRequirement.PRODUCT_LINE_ID.equals(value)) {
            throw new IllegalArgumentException("reference assembly contract requires reference-assembly product line");
        }
        return value;
    }

    static CertifiedAgentComponentRef requireEmbeddedAgentPack(CertifiedAgentComponentRef value) {
        Objects.requireNonNull(value, "component");
        if (!ReferenceAssemblyRequirement.COMPONENT_ROLE.equals(value.componentRole())) {
            throw new IllegalArgumentException(
                    "reference assembly requires exactly one embedded-agent-pack component");
        }
        return value;
    }

    static String requireStableId(String value, String name, int maximumLength) {
        value = requireExactIdentity(value, name, maximumLength);
        if (!STABLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be an exact lowercase stable id");
        }
        return value;
    }

    static String requireResolvedVersion(String value, String name) {
        value = requireExactIdentity(value, name, 128);
        if (value.toLowerCase(Locale.ROOT).contains("snapshot")) {
            throw new IllegalArgumentException(name + " must be an immutable resolved version");
        }
        return value;
    }

    static String requireStableCode(String value) {
        value = requireText(value, "stableCode", 128);
        if (!STABLE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "stableCode must be a bounded REFERENCE_ASSEMBLY code");
        }
        return value;
    }

    static String requireExactIdentity(String value, String name, int maximumLength) {
        value = requireText(value, name, maximumLength);
        if (!value.equals(value.trim())
                || FLOATING_SEGMENT.matcher(value).find()
                || value.indexOf('*') >= 0
                || value.indexOf('?') >= 0
                || value.indexOf('[') >= 0
                || value.indexOf(']') >= 0
                || value.indexOf('{') >= 0
                || value.indexOf('}') >= 0) {
            throw new IllegalArgumentException(name + " must not contain a floating reference");
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
}
