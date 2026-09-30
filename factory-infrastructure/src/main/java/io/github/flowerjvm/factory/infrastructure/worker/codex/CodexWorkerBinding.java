package io.github.flowerjvm.factory.infrastructure.worker.codex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Trusted local-process binding; none of these values may come from a WorkOrder payload. */
public final class CodexWorkerBinding {
    private static final Set<String> ALLOWED_ENVIRONMENT = Set.of(
            "PATH", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC", "LANG", "LC_ALL", "TZ");
    private final String bindingId;
    private final Path nodeExecutable;
    private final Path runnerEntrypoint;
    private final Path stateRoot;
    private final Path workspaceBase;
    private final Path codexHome;
    private final Map<String, Path> workspaces;
    private final Map<String, String> processEnvironment;
    private final Optional<String> model;
    private final Optional<Path> codexPath;
    private final Duration processTimeout;

    public CodexWorkerBinding(
            String bindingId,
            Path nodeExecutable,
            Path runnerEntrypoint,
            Path stateRoot,
            Path workspaceBase,
            Path codexHome,
            Map<String, Path> workspaces,
            Map<String, String> processEnvironment,
            Optional<String> model,
            Optional<Path> codexPath,
            Duration processTimeout) {
        this.bindingId = requireText(bindingId, "bindingId", 128);
        this.nodeExecutable = requireRegularFile(nodeExecutable, "nodeExecutable");
        this.runnerEntrypoint = requireRegularFile(runnerEntrypoint, "runnerEntrypoint");
        this.stateRoot = requireDirectory(stateRoot, "stateRoot");
        this.workspaceBase = requireDirectory(workspaceBase, "workspaceBase");
        this.codexHome = requireDirectory(codexHome, "codexHome");
        Objects.requireNonNull(workspaces, "workspaces");
        var workspaceCopy = new LinkedHashMap<String, Path>();
        workspaces.forEach((reference, path) -> workspaceCopy.put(
                requireText(reference, "workspaceRef", 512), requireAbsolute(path, "workspace path")));
        this.workspaces = Map.copyOf(workspaceCopy);
        Objects.requireNonNull(processEnvironment, "processEnvironment");
        var environmentCopy = new LinkedHashMap<String, String>();
        var foldedEnvironment = new HashSet<String>();
        processEnvironment.forEach((name, value) -> {
            String validatedName = requireEnvironmentName(name);
            String folded = validatedName.toUpperCase(Locale.ROOT);
            if (!ALLOWED_ENVIRONMENT.contains(folded) || !foldedEnvironment.add(folded)) {
                throw new IllegalArgumentException("process environment contains a non-allowlisted or ambiguous name");
            }
            environmentCopy.put(validatedName, requireText(value, "environment value", 32 * 1024));
        });
        this.processEnvironment = Map.copyOf(environmentCopy);
        this.model = Objects.requireNonNull(model, "model")
                .map(value -> requireText(value, "model", 128));
        this.codexPath = Objects.requireNonNull(codexPath, "codexPath")
                .map(value -> requireRegularFile(value, "codexPath"));
        this.processTimeout = Objects.requireNonNull(processTimeout, "processTimeout");
        if (processTimeout.isZero() || processTimeout.isNegative() || processTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("processTimeout must be within (0, 2 minutes]");
        }
    }

    public String bindingId() {
        return bindingId;
    }

    Path nodeExecutable() {
        return nodeExecutable;
    }

    Path runnerEntrypoint() {
        return runnerEntrypoint;
    }

    Path stateRoot() {
        return stateRoot;
    }

    Path workspaceBase() {
        return workspaceBase;
    }

    Path codexHome() {
        return codexHome;
    }

    Map<String, String> processEnvironment() {
        return processEnvironment;
    }

    Optional<String> model() {
        return model;
    }

    Optional<Path> codexPath() {
        return codexPath;
    }

    Duration processTimeout() {
        return processTimeout;
    }

    public Path resolveWorkspace(String workspaceRef) {
        Path configured = workspaces.get(workspaceRef);
        if (configured == null) {
            throw new IllegalArgumentException("CODING_WORKER_WORKSPACE_NOT_CONFIGURED");
        }
        try {
            Path base = workspaceBase.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path resolved = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(configured)
                    || !resolved.startsWith(base)) {
                throw new IllegalArgumentException("CODING_WORKER_WORKSPACE_OUTSIDE_BASE");
            }
            return resolved;
        } catch (IOException exception) {
            throw new IllegalArgumentException("CODING_WORKER_WORKSPACE_UNAVAILABLE", exception);
        }
    }

    @Override
    public String toString() {
        return "CodexWorkerBinding[bindingId=" + bindingId + ", nodeExecutable=" + nodeExecutable
                + ", runnerEntrypoint=" + runnerEntrypoint + ", stateRoot=" + stateRoot
                + ", workspaceBase=" + workspaceBase + ", codexHome=[REDACTED], workspaces="
                + workspaces.keySet() + ", processEnvironment=[REDACTED], model="
                + model.map(ignored -> "[CONFIGURED]").orElse("[DEFAULT]") + "]";
    }

    private static Path requireAbsolute(Path value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be absolute");
        }
        return value.normalize();
    }

    private static Path requireRegularFile(Path value, String name) {
        Path path = requireAbsolute(value, name);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(name + " must be a configured non-link regular file");
        }
        return path;
    }

    private static Path requireDirectory(Path value, String name) {
        Path path = requireAbsolute(value, name);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(name + " must be a configured non-link directory");
        }
        return path;
    }

    private static String requireText(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }

    private static String requireEnvironmentName(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw new IllegalArgumentException("environment name is invalid");
        }
        return value;
    }

}
