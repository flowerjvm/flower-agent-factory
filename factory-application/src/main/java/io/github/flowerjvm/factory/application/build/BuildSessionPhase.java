package io.github.flowerjvm.factory.application.build;

import java.util.Arrays;

/** Stable machine phase recorded in the durable BuildSession ledger. */
public enum BuildSessionPhase {
    UNDERSTAND_CUSTOMER("understand-customer"),
    ANALYZE_SYSTEMS("analyze-systems"),
    RESOLVE_REUSE_STRATEGY("resolve-reuse-strategy"),
    DESIGN_AGENT("design-agent"),
    REVIEW_CRITICAL_DECISIONS("review-critical-decisions"),
    GENERATE_CANDIDATE("generate-candidate"),
    BUILD("build"),
    TEST("test"),
    EVALUATE("evaluate"),
    SECURITY_CHECK("security-check"),
    ASSEMBLE_CANDIDATE("assemble-candidate"),
    CERTIFY("certify"),
    HUMAN_RELEASE_REVIEW("human-release-review"),
    PACKAGE_RELEASE("package-release"),
    COMPLETE("complete");

    private final String id;

    BuildSessionPhase(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static BuildSessionPhase fromId(String id) {
        return Arrays.stream(values())
                .filter(value -> value.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown BuildSession phase: " + id));
    }
}
