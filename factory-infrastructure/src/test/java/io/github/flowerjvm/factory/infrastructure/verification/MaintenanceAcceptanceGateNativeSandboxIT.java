package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real candidate main execution occurs only inside the pinned Docker sandbox. */
class MaintenanceAcceptanceGateNativeSandboxIT {
    @TempDir Path temporaryDirectory;

    @Test
    void realMainCandidatePassesAllGoldenCasesAndStoredEvidenceCanBeRechecked() throws Exception {
        Path candidate = temporaryDirectory.resolve("positive");
        MaintenanceAcceptanceTestCandidates.write(candidate, MaintenanceAcceptanceTestCandidates.Mutation.NONE);
        var mapper = new ObjectMapper();
        var sandbox = sandbox();
        var sourceHash = sourceHash(candidate);

        var ownTests = sandbox.execute(candidate, repository(), VerificationCommand.MAVEN_VERIFY);
        assertEquals(0, ownTests.exitCode(), text(ownTests));
        assertTrue(IndependentMavenVerifier.verifiedTestCount(ownTests.evidenceFiles()) > 0);
        var result = new MaintenanceAcceptanceGate(sandbox, mapper).verify(candidate, repository(), sourceHash);

        assertTrue(result.passed(), text(result.execution()) + new String(result.summaryBytes(), StandardCharsets.UTF_8));
        assertTrue(MaintenanceAcceptanceGate.evidenceMatches(result.summaryBytes(), result.actualBytes(), sourceHash, mapper));
        assertEquals(18, mapper.readTree(result.actualBytes()).get("cases").size());
    }

    @Test
    void actualSemanticMutantsPassTheirOwnTestsButFailFactoryOwnedGoldenComparison() throws Exception {
        for (var mutation : new MaintenanceAcceptanceTestCandidates.Mutation[] {
                MaintenanceAcceptanceTestCandidates.Mutation.WRONG_EVIDENCE_REFERENCE,
                MaintenanceAcceptanceTestCandidates.Mutation.WRONG_CONFLICT_CODE,
                MaintenanceAcceptanceTestCandidates.Mutation.LOST_GROUP_REFERENCE}) {
            Path candidate = temporaryDirectory.resolve(mutation.name());
            MaintenanceAcceptanceTestCandidates.write(candidate, mutation);
            var sandbox = sandbox();
            var ownTests = sandbox.execute(candidate, repository(), VerificationCommand.MAVEN_VERIFY);
            assertEquals(0, ownTests.exitCode(), mutation + " " + text(ownTests));
            assertTrue(IndependentMavenVerifier.verifiedTestCount(ownTests.evidenceFiles()) > 0);

            var result = new MaintenanceAcceptanceGate(sandbox, new ObjectMapper())
                    .verify(candidate, repository(), sourceHash(candidate));

            assertEquals(0, result.execution().exitCode(), mutation + " " + text(result.execution()));
            assertFalse(result.passed(), mutation.toString());
            assertFalse(new ObjectMapper().readTree(result.summaryBytes()).get("failedCaseIds").isEmpty());
            assertFalse(MaintenanceAcceptanceGate.evidenceMatches(result.summaryBytes(), result.actualBytes(),
                    sourceHash(candidate), new ObjectMapper()));
        }
    }

    private static DockerCliVerificationSandbox sandbox() {
        return new DockerCliVerificationSandbox("docker", Duration.ofMinutes(2), 2 * 1024 * 1024);
    }

    private static Path repository() {
        String configured = System.getProperty("factory.verification.nativeCuratedRepository");
        if (configured == null || configured.isBlank()) throw new IllegalStateException("native curated repository is required");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static ContentHash sourceHash(Path workspace) throws Exception {
        var hasher = new OrdinalSourceTreeHasher();
        var lines = new ArrayList<String>();
        try (var files = Files.walk(workspace)) {
            for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                lines.add(workspace.relativize(path).toString().replace('\\', '/') + "\t"
                        + hasher.sha256(Files.readAllBytes(path)).sha256());
            }
        }
        lines.sort(String::compareTo);
        return hasher.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private static String text(SandboxExecutionResult result) {
        return new String(result.output(), StandardCharsets.UTF_8);
    }
}
