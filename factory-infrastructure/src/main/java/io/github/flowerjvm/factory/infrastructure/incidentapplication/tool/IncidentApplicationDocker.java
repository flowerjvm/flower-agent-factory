package io.github.flowerjvm.factory.infrastructure.incidentapplication.tool;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Fixed no-egress equipment. Blocking process IO is used only by the durable dispatch runner. */
final class IncidentApplicationDocker {
    static final String IMAGE = "maven@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e";
    static final String LABEL = "io.github.flowerjvm.factory.incident-application.owner";
    private final String executable;
    private final Duration timeout;
    IncidentApplicationDocker(String executable, Duration timeout) {
        if (executable == null || executable.isBlank() || timeout == null || timeout.compareTo(Duration.ofSeconds(5)) < 0
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("INCIDENT_APPLICATION_SANDBOX_CONFIG");
        this.executable = executable; this.timeout = timeout;
    }
    record Result(int exitCode, String output) { }
    Result call(List<String> arguments) {
        var command = new ArrayList<String>(); command.add(executable); command.addAll(arguments);
        Process process = null;
        try {
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            // Do not pass provider credentials to Docker CLI or forward them to containers.
            builder.environment().keySet().removeIf(key -> key.matches("(?i).*(API_KEY|ACCESS_TOKEN|SECRET|PASSWORD).*"));
            process = builder.start(); final InputStream input = process.getInputStream();
            var output = new CompletableFuture<byte[]>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (input) {
                    var captured = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int read;
                    while ((read = input.read(buffer)) >= 0) { if (captured.size() + read > 256 * 1024) throw new IOException("PROCESS_OUTPUT_BOUND"); captured.write(buffer, 0, read); }
                    output.complete(captured.toByteArray());
                } catch (Throwable failure) { output.completeExceptionally(failure); }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); throw new IllegalStateException("INCIDENT_APPLICATION_SANDBOX_TIMEOUT");
            }
            byte[] bytes = output.get(5, TimeUnit.SECONDS); reader.join(5000);
            return new Result(process.exitValue(), new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("INCIDENT_APPLICATION_SANDBOX_INTERRUPTED", failure);
        } catch (IOException | ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("INCIDENT_APPLICATION_SANDBOX_UNAVAILABLE", failure);
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
    static final class EquipmentFailure extends IllegalStateException {
        private final String equipmentOutput;
        EquipmentFailure(String operation, Result result) {
            super("INCIDENT_APPLICATION_SANDBOX_FAILED_" + operation.toUpperCase(Locale.ROOT) + "_" + result.exitCode());
            this.equipmentOutput = result.output();
        }
        /** Test diagnostics only. Durable reports retain the stable code, never this untrusted output. */
        String equipmentOutput() { return equipmentOutput; }
    }
    void require(List<String> args) { Result result = call(args); if (result.exitCode() != 0) throw new EquipmentFailure(args.get(0), result); }
    void available() { require(List.of("image", "inspect", "--format", "{{.Id}}", IMAGE)); }
    List<String> create(String name, String entrypoint) {
        return new ArrayList<>(List.of("create", "--pull=never", "--init", "--name", name, "--label", LABEL + "=" + name,
                "--log-driver", "local", "--log-opt", "max-size=1m", "--log-opt", "max-file=1", "--log-opt", "compress=false", "--network", "none",
                "--read-only", "--user", "1000:1000", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--pids-limit", "128", "--memory", "768m", "--memory-swap", "768m", "--cpus", "2", "--tmpfs",
                "/tmp:rw,nosuid,nodev,noexec,size=64m,mode=1777", "--entrypoint", entrypoint));
    }
    static void mount(List<String> args, Path source, String target, boolean readonly) {
        args.add("--mount"); args.add("type=bind,source=" + source.toAbsolutePath().normalize() + ",target=" + target + (readonly ? ",readonly" : ""));
    }
    void remove(String name) {
        // Name is locally generated, and the label must still prove ownership before deletion.
        if (!name.matches("factory-incident-(build|probe|http)-[0-9a-f]{32}")) throw new IllegalStateException("INCIDENT_APPLICATION_CONTAINER_OWNER_MISMATCH");
        Result present = call(List.of("ps", "-a", "--filter", "name=^/" + name + "$", "--format", "{{.Names}}"));
        if (present.exitCode() != 0) throw new EquipmentFailure("cleanup-query", present);
        if (present.output().isBlank()) return;
        Result owned = call(List.of("ps", "-a", "--filter", "name=^/" + name + "$", "--filter", "label=" + LABEL + "=" + name, "--format", "{{.Names}}"));
        if (owned.exitCode() != 0) throw new EquipmentFailure("cleanup-owner-query", owned);
        if (!owned.output().trim().equals(name)) throw new IllegalStateException("INCIDENT_APPLICATION_CONTAINER_OWNER_MISMATCH");
        require(List.of("rm", "-f", name));
    }
}
