package io.github.flowerjvm.factory.infrastructure.verification;

import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.SANDBOX_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerCliVerificationSandboxTest {
    @Test
    void evidencePinsDockerIsolationAndResourcePolicy() {
        var sandbox = new DockerCliVerificationSandbox();
        var evidence = sandbox.evidence();

        assertEquals("docker-cli", evidence.backend());
        assertEquals(DockerCliVerificationSandbox.IMAGE, evidence.imageDigest());
        assertTrue(evidence.networkDisabled());
        assertTrue(evidence.readOnlyRootFilesystem());
        assertTrue(evidence.nonRoot());
        assertEquals(256, evidence.pidLimit());
    }

    @Test
    void missingDockerHasNoHostFallback() {
        var sandbox = new DockerCliVerificationSandbox(
                "definitely-not-a-docker-executable",
                Duration.ofSeconds(1),
                1_024);
        var failure = assertThrows(SandboxExecutionException.class, () ->
                sandbox.execute(Path.of(System.getProperty("java.io.tmpdir")), VerificationCommand.MAVEN_VERIFY));
        assertEquals(SANDBOX_UNAVAILABLE, failure.stableCode());
    }

    @Test
    void dockerArgvHasNoShellSocketOrHostSecretEnvironment() {
        Path workspace = Path.of("D:/factory/workspace").toAbsolutePath();
        Path repository = Path.of("D:/factory/m2").toAbsolutePath();
        var sandbox = new DockerCliVerificationSandbox("docker", Duration.ofSeconds(30), 1_024);

        List<String> arguments = sandbox.dockerArguments(
                "fixed-name", workspace, repository, VerificationCommand.MAVEN_VERIFY);

        assertTrue(arguments.containsAll(List.of(
                "--network", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--user", "1000:1000", "--pull=never",
                "--label", DockerCliVerificationSandbox.MANAGED_LABEL,
                "--log-driver", "local", "--log-opt", "max-size=4m", "--log-opt", "max-file=1",
                "--log-opt", "compress=false")));
        assertEquals(2, arguments.stream().filter("--mount"::equals).count());
        assertTrue(arguments.stream().noneMatch(value -> value.toLowerCase().contains("docker.sock")));
        assertTrue(arguments.contains("/usr/bin/timeout"));
        assertTrue(arguments.contains("--signal=TERM"));
        assertTrue(arguments.contains("--kill-after=10s"));
        assertTrue(arguments.stream().anyMatch(value -> value.contains("target=/workspace,readonly")));
        assertTrue(arguments.stream().noneMatch(value -> value.contains(System.getProperty("user.home") + "\\.m2")));
        assertEquals(2, arguments.stream().filter("--env"::equals).count());
        assertTrue(arguments.contains("HOME=/tmp/home"));
        assertTrue(arguments.contains("MAVEN_CONFIG=/tmp/maven"));
        List<String> execution = sandbox.dockerExecArguments("fixed-name", VerificationCommand.MAVEN_VERIFY);
        assertTrue(execution.contains("FACTORY_COMMAND=MAVEN_VERIFY"));
        assertTrue(execution.contains("/bin/sh"));
        assertTrue(execution.stream().anyMatch(value -> value.contains(
                "/usr/bin/timeout --signal=TERM --kill-after=10s")));
        assertEquals(
                VerificationCommand.MAVEN_VERIFY.arguments(),
                execution.subList(execution.size() - VerificationCommand.MAVEN_VERIFY.arguments().size(), execution.size()));
    }

    @Test
    void commaInBindPathFailsClosedInsteadOfChangingMountOptions() throws Exception {
        Path workspace = java.nio.file.Files.createTempDirectory("factory,workspace");
        Path repository = java.nio.file.Files.createTempDirectory("factory,m2");
        try {
            var sandbox = new DockerCliVerificationSandbox("docker", Duration.ofSeconds(1), 1_024);
            var failure = assertThrows(SandboxExecutionException.class, () ->
                    sandbox.execute(workspace, VerificationCommand.MAVEN_VERIFY));
            assertEquals(SANDBOX_UNAVAILABLE, failure.stableCode());
        } finally {
            java.nio.file.Files.deleteIfExists(workspace);
            java.nio.file.Files.deleteIfExists(repository);
        }
    }
}
