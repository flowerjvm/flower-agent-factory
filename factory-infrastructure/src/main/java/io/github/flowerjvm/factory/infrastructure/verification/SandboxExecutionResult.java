package io.github.flowerjvm.factory.infrastructure.verification;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded output from one fixed command in an isolated candidate workspace. */
public record SandboxExecutionResult(
        String commandId,
        List<String> arguments,
        int exitCode,
        byte[] output,
        Map<String, byte[]> evidenceFiles) {
    public SandboxExecutionResult {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        output = Objects.requireNonNull(output, "output").clone();
        var copied = new LinkedHashMap<String, byte[]>();
        Objects.requireNonNull(evidenceFiles, "evidenceFiles").forEach((name, content) -> {
            if (name == null || name.isBlank() || content == null || copied.put(name, content.clone()) != null) {
                throw new IllegalArgumentException("evidence file names must be unique and non-blank");
            }
        });
        evidenceFiles = Map.copyOf(copied);
    }

    public SandboxExecutionResult(String commandId, List<String> arguments, int exitCode, byte[] output) {
        this(commandId, arguments, exitCode, output, Map.of());
    }

    @Override
    public byte[] output() {
        return output.clone();
    }

    @Override
    public Map<String, byte[]> evidenceFiles() {
        var copied = new LinkedHashMap<String, byte[]>();
        evidenceFiles.forEach((name, content) -> copied.put(name, content.clone()));
        return Map.copyOf(copied);
    }
}
