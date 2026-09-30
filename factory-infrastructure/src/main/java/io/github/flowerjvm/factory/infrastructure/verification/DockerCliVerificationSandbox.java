package io.github.flowerjvm.factory.infrastructure.verification;

import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.SANDBOX_TIMEOUT;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.SANDBOX_UNAVAILABLE;

import io.github.flowerjvm.factory.contracts.verification.VerificationSandboxEvidence;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs fixed Factory verification commands in a pinned, networkless, non-root Docker container.
 * There is deliberately no host-process fallback.
 */
public final class DockerCliVerificationSandbox implements VerificationSandbox {
    public static final String IMAGE =
            "maven@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    public static final int DEFAULT_MAX_LOG_BYTES = 4 * 1024 * 1024;
    static final long WORKSPACE_QUOTA_BYTES = 64L * 1024 * 1024;
    private static final int MAX_EVIDENCE_FILE_BYTES = 4 * 1024 * 1024;
    static final String MANAGED_LABEL = "io.github.flowerjvm.factory.verifier=pr4";

    private final String dockerExecutable;
    private final Duration timeout;
    private final int maxLogBytes;

    public DockerCliVerificationSandbox() {
        this("docker", DEFAULT_TIMEOUT, DEFAULT_MAX_LOG_BYTES);
    }

    public DockerCliVerificationSandbox(
            String dockerExecutable, Duration timeout, int maxLogBytes) {
        if (dockerExecutable == null || dockerExecutable.isBlank()) {
            throw new IllegalArgumentException("dockerExecutable must not be blank");
        }
        this.dockerExecutable = dockerExecutable;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || maxLogBytes < 1) {
            throw new IllegalArgumentException("sandbox bounds must be positive");
        }
        this.maxLogBytes = maxLogBytes;
    }

    @Override
    public SandboxExecutionResult execute(Path workspace, Path curatedMavenRepository, VerificationCommand command)
            throws SandboxExecutionException {
        Objects.requireNonNull(command, "command");
        Path candidateRoot = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        Path repositoryRoot = Objects.requireNonNull(curatedMavenRepository, "curatedMavenRepository")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(candidateRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(candidateRoot)
                || !Files.isDirectory(repositoryRoot, LinkOption.NOFOLLOW_LINKS)
                || candidateRoot.toString().contains(",")
                || repositoryRoot.toString().contains(",")) {
            throw new SandboxExecutionException(
                    SANDBOX_UNAVAILABLE, "sandbox workspace or read-only Maven repository is unavailable");
        }
        ensurePinnedImageAvailable();

        String containerName = "factory-verifier-" + UUID.randomUUID().toString().replace("-", "");
        Path targetMountPoint = candidateRoot.resolve("target");
        Path evidenceMountPoint = candidateRoot.resolve(".factory-evidence");
        try {
            prepareOutputMountPoints(targetMountPoint, evidenceMountPoint);
            createStoppedContainer(containerName, candidateRoot, repositoryRoot, command);
            startContainer(containerName);
            ProcessResult execution = executeAndWait(containerName, command);
            Map<String, byte[]> evidenceFiles = copyEvidenceOut(containerName, command);
            return new SandboxExecutionResult(
                    command.commandId(), command.arguments(), execution.exitCode(), execution.output(), evidenceFiles);
        } finally {
            removeContainer(containerName);
            removeOutputMountPoints(targetMountPoint, evidenceMountPoint);
        }
    }

    @Override
    public VerificationSandboxEvidence evidence() {
        return new VerificationSandboxEvidence(
                "docker-cli",
                IMAGE,
                "factory.verifier-sandbox.v1",
                true,
                true,
                true,
                1_073_741_824L,
                2.0,
                256,
                timeout.toMillis());
    }

    private void ensurePinnedImageAvailable() throws SandboxExecutionException {
        Process process;
        try {
            process = new ProcessBuilder(dockerExecutable, "image", "inspect", IMAGE)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(Math.min(timeout.toMillis(), 15_000L), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "Docker image inspection timed out");
            }
            if (process.exitValue() != 0) {
                throw new SandboxExecutionException(
                        SANDBOX_UNAVAILABLE, "the pinned verifier image is not present locally");
            }
        } catch (IOException exception) {
            throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "Docker CLI is unavailable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "Docker image inspection was interrupted", exception);
        }
    }

    List<String> dockerArguments(String name, Path workspace, Path curatedMavenRepository, VerificationCommand command) {
        var arguments = new ArrayList<String>();
        arguments.addAll(List.of(
                dockerExecutable,
                "create",
                "--pull=never",
                "--init",
                "--name",
                name,
                "--label",
                MANAGED_LABEL,
                "--log-driver",
                "local",
                "--log-opt",
                "max-size=4m",
                "--log-opt",
                "max-file=1",
                "--log-opt",
                "compress=false",
                "--network",
                "none",
                "--read-only",
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges",
                "--user",
                "1000:1000",
                "--pids-limit",
                "256",
                "--memory",
                "1g",
                "--cpus",
                "2",
                "--tmpfs",
                "/tmp:rw,exec,nosuid,nodev,size=268435456,nr_inodes=4096",
                "--mount",
                "type=bind,source=" + workspace + ",target=/workspace,readonly",
                "--tmpfs",
                "/workspace/target:rw,exec,nosuid,nodev,size=58720256,nr_inodes=4096,uid=1000,gid=1000,mode=0700",
                "--tmpfs",
                "/workspace/.factory-evidence:rw,nosuid,nodev,noexec,size=8388608,nr_inodes=512,uid=1000,gid=1000,mode=0700",
                "--mount",
                "type=bind,source=" + curatedMavenRepository + ",target=/m2,readonly",
                "--workdir",
                "/workspace",
                "--env",
                "HOME=/tmp/home",
                "--env",
                "MAVEN_CONFIG=/tmp/maven",
                IMAGE,
                "/usr/bin/timeout",
                "--signal=TERM",
                "--kill-after=10s",
                Math.addExact(Math.max(1L, timeout.toSeconds()), 45L) + "s",
                "/bin/sleep",
                Math.addExact(Math.max(1L, timeout.toSeconds()), 45L) + "s"));
        return List.copyOf(arguments);
    }

    List<String> dockerExecArguments(String name, VerificationCommand command) {
        var arguments = new ArrayList<>(List.of(
                dockerExecutable,
                "exec",
                "--env",
                "FACTORY_COMMAND=" + command.name(),
                name,
                "/bin/sh",
                "-c",
                evidenceEntrypoint(),
                "factory-verifier-entrypoint",
                Math.max(1L, timeout.toSeconds()) + "s"));
        arguments.addAll(command.arguments());
        return List.copyOf(arguments);
    }

    private static String evidenceEntrypoint() {
        return """
                set +e
                deadline="$1"; shift
                /usr/bin/timeout --signal=TERM --kill-after=10s "$deadline" "$@"
                rc=$?
                set -e
                manifest=/workspace/.factory-evidence/host-evidence.manifest
                : > "$manifest"
                case "$FACTORY_COMMAND" in
                  MAVEN_VERIFY)
                    directory=/workspace/target/surefire-reports
                    count=0; total=0
                    if [ -d "$directory" ]; then
                      # The three globs are disjoint and include hidden direct children. A
                      # directory, link, device, nested tree, or non-portable name fails closed.
                      for file in "$directory"/.[!.]* "$directory"/..?* "$directory"/*; do
                        [ -e "$file" ] || continue
                        [ -f "$file" ] && [ ! -L "$file" ] || exit 125
                        name=${file##*/}
                        case "$name" in *[!A-Za-z0-9_.-]*|*.|[Cc][Oo][Nn].*|[Pp][Rr][Nn].*|[Aa][Uu][Xx].*|[Nn][Uu][Ll].*|[Cc][Oo][Mm][1-9].*|[Ll][Pp][Tt][1-9].*) exit 125;; esac
                        case "$name" in *.xml) ;; *) exit 125;; esac
                        size=$(/usr/bin/stat -c %s -- "$file")
                        count=$((count + 1)); total=$((total + size))
                        [ "$count" -le 128 ] && [ "$size" -le 1048576 ] && [ "$total" -le 4194304 ] || exit 125
                        hash=$(/usr/bin/sha256sum -- "$file"); hash=${hash%% *}
                        printf '%s %s %s\n' "$hash" "$size" "$name" >> "$manifest"
                      done
                    fi
                    ;;
                  FLOWER_CHECK) evidence=flower-check.sarif ;;
                  MAVEN_DEPENDENCY_TREE) evidence=dependency-tree.txt ;;
                  MAINTENANCE_ACCEPTANCE) evidence=maintenance-acceptance.json ;;
                  *) exit 125 ;;
                esac
                if [ -n "${evidence:-}" ]; then
                  file=/workspace/.factory-evidence/$evidence
                  [ -f "$file" ] && [ ! -L "$file" ] || exit "$rc"
                  size=$(/usr/bin/stat -c %s -- "$file")
                  [ "$size" -le 4194304 ] || exit 125
                  hash=$(/usr/bin/sha256sum -- "$file"); hash=${hash%% *}
                  printf '%s %s %s\n' "$hash" "$size" "$evidence" >> "$manifest"
                fi
                exit "$rc"
                """;
    }

    private void createStoppedContainer(
            String name, Path workspace, Path repository, VerificationCommand command)
            throws SandboxExecutionException {
        ProcessResult result = runDocker(dockerArguments(name, workspace, repository, command), 30_000, 64 * 1024);
        if (result.exitCode() != 0) {
            throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "Docker could not create the pinned verifier");
        }
    }

    private static void prepareOutputMountPoints(Path target, Path evidence)
            throws SandboxExecutionException {
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(evidence, LinkOption.NOFOLLOW_LINKS)) {
                throw new SandboxExecutionException(
                        SANDBOX_UNAVAILABLE, "candidate pre-seeded a host evidence/output directory");
            }
            Files.createDirectory(target);
            Files.createDirectory(evidence);
        } catch (IOException exception) {
            throw new SandboxExecutionException(
                    SANDBOX_UNAVAILABLE, "host output mount points could not be created", exception);
        }
    }

    private static void removeOutputMountPoints(Path target, Path evidence) {
        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(evidence, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(target);
                Files.delete(evidence);
            }
        } catch (IOException ignored) {
            // The enclosing random verification workspace is quarantined and blocks reuse on failure.
        }
    }

    private void startContainer(String name) throws SandboxExecutionException {
        ProcessResult started = runDocker(List.of(dockerExecutable, "start", name), 30_000L, 64 * 1024);
        if (started.exitCode() != 0) {
            throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "Docker could not start the pinned verifier");
        }
    }

    private ProcessResult executeAndWait(String name, VerificationCommand command)
            throws SandboxExecutionException {
        return runDocker(
                dockerExecArguments(name, command), Math.addExact(timeout.toMillis(), 15_000L), maxLogBytes);
    }

    private Map<String, byte[]> copyEvidenceOut(String name, VerificationCommand command)
            throws SandboxExecutionException {
        long copyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
        ProcessResult manifestRead = readBoundedContainerFile(
                name,
                "/workspace/.factory-evidence/host-evidence.manifest",
                64 * 1024,
                copyDeadline);
        if (manifestRead.exitCode() != 0 || manifestRead.output().length > 64 * 1024) {
            throw new SandboxExecutionException(
                    SANDBOX_UNAVAILABLE, "host could not retrieve the bounded evidence manifest");
        }
        String manifestText = new String(manifestRead.output(), java.nio.charset.StandardCharsets.US_ASCII);
        var entries = parseEvidenceManifest(manifestText.lines().toList(), command);
        var result = new LinkedHashMap<String, byte[]>();
        for (EvidenceEntry entry : entries) {
            String containerPath = command == VerificationCommand.MAVEN_VERIFY
                    ? "/workspace/target/surefire-reports/" + entry.name()
                    : "/workspace/.factory-evidence/" + entry.name();
            ProcessResult read = readBoundedContainerFile(
                    name, containerPath, Math.toIntExact(entry.size()), copyDeadline);
            byte[] bytes = read.output();
            if (read.exitCode() != 0 || bytes.length != entry.size()) {
                throw new SandboxExecutionException(
                        SANDBOX_UNAVAILABLE, "host could not retrieve one exact bounded evidence file");
            }
            if (!sha256(bytes).equals(entry.hash())) {
                throw new SandboxExecutionException(
                        SANDBOX_UNAVAILABLE, "retrieved evidence did not match its in-container digest");
            }
            result.put(command == VerificationCommand.MAVEN_VERIFY ? "surefire/" + entry.name() : entry.name(), bytes);
        }
        return Map.copyOf(result);
    }

    private ProcessResult readBoundedContainerFile(
            String containerName, String absolutePath, int maximumBytes, long deadlineNanos)
            throws SandboxExecutionException {
        int probeBytes = Math.addExact(maximumBytes, 1);
        return runDocker(
                List.of(
                        dockerExecutable,
                        "exec",
                        containerName,
                        "/usr/bin/head",
                        "-c",
                        Integer.toString(probeBytes),
                        "--",
                        absolutePath),
                remainingCopyMillis(deadlineNanos),
                probeBytes);
    }

    private static long remainingCopyMillis(long deadlineNanos) throws SandboxExecutionException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remaining < 1L) {
            throw new SandboxExecutionException(SANDBOX_TIMEOUT, "sandbox evidence copy exceeded its deadline");
        }
        return remaining;
    }

    private static List<EvidenceEntry> parseEvidenceManifest(
            List<String> lines, VerificationCommand command) throws SandboxExecutionException {
        int maximum = command == VerificationCommand.MAVEN_VERIFY ? 128 : 1;
        if (lines.size() > maximum) throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "evidence count exceeds bound");
        var result = new ArrayList<EvidenceEntry>();
        var names = new java.util.HashSet<String>();
        long total = 0;
        for (String line : lines) {
            String[] parts = line.split(" ", -1);
            if (parts.length != 3 || !parts[0].matches("[0-9a-f]{64}")
                    || !parts[1].matches("[0-9]{1,7}") || !portableEvidenceName(parts[2])) {
                throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "evidence manifest is not canonical");
            }
            long size = Long.parseLong(parts[1]);
            total = Math.addExact(total, size);
            String required = command == VerificationCommand.FLOWER_CHECK ? "flower-check.sarif"
                    : command == VerificationCommand.MAVEN_DEPENDENCY_TREE ? "dependency-tree.txt"
                    : command == VerificationCommand.MAINTENANCE_ACCEPTANCE ? "maintenance-acceptance.json" : null;
            if (size > MAX_EVIDENCE_FILE_BYTES || total > MAX_EVIDENCE_FILE_BYTES
                    || !names.add(parts[2]) || (required != null && !required.equals(parts[2]))) {
                throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "evidence manifest exceeds bounds");
            }
            result.add(new EvidenceEntry(parts[0], size, parts[2]));
        }
        if (result.isEmpty() || (command != VerificationCommand.MAVEN_VERIFY && result.size() != 1)) {
            throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "required machine evidence is missing");
        }
        return List.copyOf(result);
    }

    private static boolean portableEvidenceName(String name) {
        return name.matches("[A-Za-z0-9_.-]{1,128}") && !name.endsWith(".") && !name.endsWith(" ")
                && !name.matches("(?i)^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$");
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record EvidenceEntry(String hash, long size, String name) {}

    private ProcessResult runDocker(List<String> command, long timeoutMillis, int outputLimit)
            throws SandboxExecutionException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException exception) {
            throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "Docker CLI could not start", exception);
        }
        var collector = new BoundedOutputCollector(process.getInputStream(), outputLimit);
        Thread outputThread = Thread.ofVirtual().name("factory-verifier-docker-output").start(collector);
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new SandboxExecutionException(SANDBOX_TIMEOUT, "sandbox Docker operation exceeded its deadline");
            }
            joinCollector(outputThread);
            if (collector.failure() != null) {
                throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "Docker output could not be collected", collector.failure());
            }
            return new ProcessResult(process.exitValue(), collector.content());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new SandboxExecutionException(SANDBOX_UNAVAILABLE, "sandbox Docker operation was interrupted", exception);
        }
    }

    private void removeContainer(String name) {
        try {
            new ProcessBuilder(dockerExecutable, "rm", "-f", name)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException ignored) {
            // A random, non-reused name prevents residue from being trusted by a later run.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record ProcessResult(int exitCode, byte[] output) {}

    private void terminate(Process process, String containerName) {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            new ProcessBuilder(dockerExecutable, "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
        } catch (IOException ignored) {
            process.destroyForcibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void joinCollector(Thread thread) throws SandboxExecutionException {
        try {
            thread.join(10_000);
            if (thread.isAlive()) {
                thread.interrupt();
                throw new SandboxExecutionException(
                        SANDBOX_UNAVAILABLE, "sandbox output collector did not terminate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SandboxExecutionException(
                    SANDBOX_UNAVAILABLE, "sandbox output collection was interrupted", exception);
        }
    }

    private static final class BoundedOutputCollector implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream retained = new ByteArrayOutputStream();
        private volatile IOException failure;
        private boolean truncated;

        private BoundedOutputCollector(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8_192];
            try (input) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int remaining = limit - retained.size();
                    if (remaining > 0) {
                        retained.write(buffer, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        truncated = true;
                    }
                }
            } catch (IOException exception) {
                failure = exception;
            }
        }

        private byte[] content() {
            if (!truncated) {
                return retained.toByteArray();
            }
            byte[] marker = "\n[FACTORY_LOG_TRUNCATED]\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var output = new ByteArrayOutputStream(retained.size() + marker.length);
            output.writeBytes(retained.toByteArray());
            output.writeBytes(marker);
            return output.toByteArray();
        }

        private IOException failure() {
            return failure;
        }
    }
}
