package io.github.flowerjvm.factory.contracts.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.util.List;
import org.junit.jupiter.api.Test;

class Pr4VerificationContractTest {
    @Test
    void candidateManifestBindsCountsSizesUniquePathsAndExactVersions() {
        var file = new CandidateSourceEntry(
                "pom.xml", new ArtifactReference("artifact:pom"), hash("1"), 10);
        var manifest = new CandidateSourceManifest(
                CandidateSourceManifest.SCHEMA_VERSION,
                new CandidateId("candidate-1"),
                new BuildSessionId("session-1"),
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                hash("2"),
                1,
                10,
                List.of(file));

        assertEquals(1, manifest.files().size());
        assertThrows(IllegalArgumentException.class, () -> new CandidateSourceManifest(
                "future",
                manifest.candidateId(),
                manifest.buildSessionId(),
                manifest.sourceLockAlgorithmId(),
                manifest.candidateHash(),
                1,
                10,
                List.of(file)));
        assertThrows(IllegalArgumentException.class, () -> new CandidateSourceManifest(
                manifest.schemaVersion(),
                manifest.candidateId(),
                manifest.buildSessionId(),
                manifest.sourceLockAlgorithmId(),
                manifest.candidateHash(),
                2,
                20,
                List.of(file, file)));
    }

    @Test
    void passedResultManifestRequiresReviewEligibilityAndFailClosedSandbox() {
        var sandbox = new VerificationSandboxEvidence(
                "docker",
                "maven@sha256:abc",
                "factory-pr4",
                true,
                true,
                true,
                512L * 1024 * 1024,
                1.0,
                128,
                120_000);
        var log = new VerificationCommandEvidence(
                "maven-verify",
                List.of("mvn", "-B", "verify"),
                0,
                3,
                0,
                new ArtifactReference("artifact:log"),
                hash("3"),
                new ArtifactReference("artifact:machine"),
                hash("5"));
        var selfTest = new VerificationFixtureSelfTestEvidence(
                List.of("seeded-blocking-step"), List.of("FLOWER-CHECK-001"),
                List.of("FLOWER-CHECK-001"), 1,
                new ArtifactReference("artifact:self-test"), hash("7"));
        var manifest = new VerificationResultManifest(
                VerificationResultManifest.SCHEMA_VERSION,
                new VerificationRunId("verification-1"),
                new BuildSessionId("session-1"),
                new CandidateId("candidate-1"),
                new ArtifactReference("artifact:candidate-manifest"),
                hash("1"),
                new ArtifactReference("artifact:dependency-lock"),
                hash("6"),
                hash("1"),
                hash("1"),
                "factory-v0.1-pr4",
                new ArtifactReference("artifact:toolchain-lock"),
                hash("2"),
                new ArtifactReference("artifact:fixture-set"),
                hash("4"),
                selfTest,
                sandbox,
                VerificationStatus.PASSED,
                VerificationDisposition.REVIEW_ELIGIBLE,
                List.of(VerificationStableCodes.VERIFIED),
                List.of(log),
                List.of(
                        new ArtifactReference("artifact:self-test"),
                        new ArtifactReference("artifact:log"),
                        new ArtifactReference("artifact:machine")));

        assertEquals(VerificationDisposition.REVIEW_ELIGIBLE, manifest.disposition());
        assertThrows(IllegalArgumentException.class, () -> new VerificationResult(
                VerificationStatus.PASSED,
                VerificationDisposition.REPAIR_REQUIRED,
                List.of("FAILED"),
                new ArtifactReference("artifact:result"),
                hash("5"),
                List.of()));
        var unsafe = new VerificationSandboxEvidence(
                "docker", "image", "policy", false, true, true, 1, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> new VerificationResultManifest(
                manifest.schemaVersion(),
                manifest.verificationRunId(),
                manifest.buildSessionId(),
                manifest.candidateId(),
                manifest.candidateManifestRef(),
                manifest.candidateHash(),
                manifest.dependencyLockRef(),
                manifest.dependencyLockHash(),
                manifest.sourceHashBefore(),
                manifest.sourceHashAfter(),
                manifest.gateProfile(),
                manifest.toolchainLockRef(),
                manifest.toolchainLockHash(),
                manifest.fixtureSetRef(),
                manifest.fixtureSetHash(),
                manifest.fixtureSelfTest(),
                unsafe,
                manifest.status(),
                manifest.disposition(),
                manifest.stableCodes(),
                manifest.commands(),
                manifest.evidenceArtifacts()));
    }

    private static ContentHash hash(String digit) {
        return new ContentHash(digit.repeat(64));
    }
}
