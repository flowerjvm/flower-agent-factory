package io.github.flowerjvm.factory.contracts.verification;

/** Stable machine codes emitted by the PR4 independent verifier. */
public final class VerificationStableCodes {
    public static final String VERIFIED = "VERIFIED";
    public static final String MANIFEST_REFERENCE_UNRESOLVED = "MANIFEST_REFERENCE_UNRESOLVED";
    public static final String CANDIDATE_MANIFEST_INVALID = "CANDIDATE_MANIFEST_INVALID";
    public static final String WORKSPACE_PATH_TRAVERSAL = "WORKSPACE_PATH_TRAVERSAL";
    public static final String WORKSPACE_PATH_COLLISION = "WORKSPACE_PATH_COLLISION";
    public static final String ARTIFACT_CHECKSUM_MISMATCH = "ARTIFACT_CHECKSUM_MISMATCH";
    public static final String CANDIDATE_HASH_MISMATCH = "CANDIDATE_HASH_MISMATCH";
    public static final String DEPENDENCY_LOCK_INVALID = "DEPENDENCY_LOCK_INVALID";
    public static final String DEPENDENCY_TREE_MISMATCH = "DEPENDENCY_TREE_MISMATCH";
    public static final String BUILD_POLICY_VIOLATION = "BUILD_POLICY_VIOLATION";
    public static final String FIXTURE_SET_INVALID = "FIXTURE_SET_INVALID";
    public static final String VERIFIER_SELF_TEST_FAILED = "VERIFIER_SELF_TEST_FAILED";
    public static final String SECRET_MATERIAL_DETECTED = "SECRET_MATERIAL_DETECTED";
    public static final String WORKSPACE_QUOTA_EXCEEDED = "WORKSPACE_QUOTA_EXCEEDED";
    public static final String SANDBOX_UNAVAILABLE = "SANDBOX_UNAVAILABLE";
    public static final String SANDBOX_TIMEOUT = "SANDBOX_TIMEOUT";
    public static final String MAVEN_VERIFICATION_FAILED = "MAVEN_VERIFICATION_FAILED";
    public static final String FLOWER_CHECK_FAILED = "FLOWER_CHECK_FAILED";
    public static final String FLOWER_CHECK_EVIDENCE_INCOMPLETE = "FLOWER_CHECK_EVIDENCE_INCOMPLETE";
    public static final String MAVEN_TESTS_MISSING = "MAVEN_TESTS_MISSING";
    public static final String CANDIDATE_SOURCE_MUTATED = "CANDIDATE_SOURCE_MUTATED";
    public static final String WORKSPACE_WRITE_OUTSIDE_ALLOWED_PATH =
            "WORKSPACE_WRITE_OUTSIDE_ALLOWED_PATH";
    public static final String VERIFICATION_INTERNAL_ERROR = "VERIFICATION_INTERNAL_ERROR";
    public static final String VERIFICATION_PROFILE_UNSUPPORTED = "VERIFICATION_PROFILE_UNSUPPORTED";
    public static final String PRODUCT_ACCEPTANCE_FAILED = "PRODUCT_ACCEPTANCE_FAILED";

    private VerificationStableCodes() {}
}
