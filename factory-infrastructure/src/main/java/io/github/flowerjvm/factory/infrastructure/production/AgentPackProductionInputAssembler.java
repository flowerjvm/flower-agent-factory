package io.github.flowerjvm.factory.infrastructure.production;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPlan;
import io.github.flowerjvm.factory.application.production.MaintenanceProductionRecipe;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.MavenDependencyLock;
import io.github.flowerjvm.factory.contracts.verification.MavenRepositoryFile;
import io.github.flowerjvm.factory.contracts.verification.MavenToolchainLock;
import io.github.flowerjvm.factory.infrastructure.verification.OrdinalSourceTreeHasher;
import io.github.flowerjvm.factory.infrastructure.verification.Pr4MavenToolchainInstaller;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only preflight for one code-owned recipe. This is not an intake, installer, Worker build
 * environment, or verifier: only the registered intake Action may persist the returned inputs.
 */
public final class AgentPackProductionInputAssembler {
    public static final String SKILL_ID = "flower-plugin-production-reference-bundle";
    public static final String SKILL_VERSION = "0.3.3";
    static final int MAX_TEXT_FILE_BYTES = 512 * 1024;
    static final int MAX_BUNDLE_BYTES = 2 * 1024 * 1024;
    static final List<String> SKILL_PATHS = List.of(
            "flower-app-guide/SKILL.md",
            "flower-app-guide/references/00-guide-version.md",
            "flower-app-guide/references/01-app-quick-rules.md",
            "flower-app-guide/references/10-flow-step-authoring.md",
            "flower-app-guide/references/30-durable-app-flows.md",
            "flower-app-guide/references/40-testing-with-testkit.md",
            "flower-app-guide/references/90-verification.md",
            "flower-action-runtime-guide/SKILL.md",
            "flower-action-runtime-guide/references/00-guide-version.md",
            "flower-action-runtime-guide/references/01-runtime-quick-rules.md",
            "flower-action-runtime-guide/references/10-action-model-and-controls.md",
            "flower-action-runtime-guide/references/20-execution-modes.md",
            "flower-action-runtime-guide/references/30-persistence-and-concurrency.md",
            "flower-action-runtime-guide/references/50-testing.md",
            "flower-action-runtime-guide/references/60-host-integration.md",
            "flower-action-runtime-guide/references/90-verification.md");
    static final List<String> DEPENDENCY_COORDINATES = List.of(
            "io.github.flowerjvm:flower-core:jar:0.1.3",
            "org.apiguardian:apiguardian-api:jar:1.1.2",
            "org.junit.jupiter:junit-jupiter-api:jar:5.12.2",
            "org.junit.jupiter:junit-jupiter-engine:jar:5.12.2",
            "org.junit.jupiter:junit-jupiter-params:jar:5.12.2",
            "org.junit.jupiter:junit-jupiter:jar:5.12.2",
            "org.junit.platform:junit-platform-commons:jar:1.12.2",
            "org.junit.platform:junit-platform-engine:jar:1.12.2",
            "org.opentest4j:opentest4j:jar:1.3.0");

    private final Pr4MavenToolchainInstaller toolchain;
    private final Path skillRoot;
    private final ObjectMapper mapper;
    private final OrdinalSourceTreeHasher hasher = new OrdinalSourceTreeHasher();

    public AgentPackProductionInputAssembler(
            Pr4MavenToolchainInstaller toolchain, Path installedFlowerSkillRoot, ObjectMapper mapper) {
        this.toolchain = Objects.requireNonNull(toolchain, "toolchain");
        this.skillRoot = Objects.requireNonNull(installedFlowerSkillRoot, "installedFlowerSkillRoot")
                .toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public PreparedInputs prepare(
            TenantId tenantId, BuildSessionId sessionId, String workspaceRef,
            AgentPackProductionPlan.WorkerBinding manager, AgentPackProductionPlan.WorkerBinding coding) {
        return prepare(tenantId, sessionId, workspaceRef, manager, coding, "");
    }

    public PreparedInputs prepare(
            TenantId tenantId, BuildSessionId sessionId, String workspaceRef,
            AgentPackProductionPlan.WorkerBinding manager, AgentPackProductionPlan.WorkerBinding coding,
            String demoScenario) {
        String recipeId = io.github.flowerjvm.factory.application.production.MaintenanceRepairDemoScenario
                .recipeForSelection(demoScenario);
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionId, "sessionId");
        Artifact skill = skillArtifact(tenantId);
        Artifact manifest = toolchain.manifestArtifact(tenantId);
        List<String> paths = dependencyPaths();
        List<Artifact> dependencies = toolchain.repositoryArtifacts(tenantId, paths);
        Artifact dependencyLock = dependencyLock(tenantId, manifest, paths, dependencies);
        Artifact policy = policyArtifact(tenantId, demoScenario);
        var inputs = new ArrayList<>(MaintenanceInvestigationProductContract.artifacts(tenantId));
        inputs.add(skill);
        inputs.add(manifest);
        inputs.add(dependencyLock);
        inputs.add(policy);
        inputs.addAll(dependencies);
        var plan = new AgentPackProductionPlan(AgentPackProductionPlan.SCHEMA_VERSION,
                tenantId, sessionId, recipeId,
                MaintenanceInvestigationProductContract.requirementsLock(), SKILL_ID, SKILL_VERSION,
                lock(skill), lock(dependencyLock), lock(manifest), MaintenanceInvestigationProductContract.lock(),
                MaintenanceInvestigationProductContract.apiSignatureIndexLock(),
                MaintenanceInvestigationProductContract.requirementTestMatrixLock(),
                MaintenanceInvestigationProductContract.GATE_PROFILE, lock(policy), workspaceRef, manager, coding);
        return new PreparedInputs(plan, inputs);
    }

    Artifact skillArtifact(TenantId tenantId) {
        try {
            if (skillRoot.getFileName() == null || !"skills".equals(skillRoot.getFileName().toString())
                    || skillRoot.getParent() == null) throw invalidSkill();
            byte[] pluginBytes = readRegular(skillRoot.getParent().resolve(".codex-plugin/plugin.json"));
            var plugin = mapper.readTree(pluginBytes);
            if (!"flower".equals(plugin.path("name").asText())
                    || !SKILL_VERSION.equals(plugin.path("version").asText())
                    || !"./skills/".equals(plugin.path("skills").asText())) throw invalidSkill();
            var files = new ArrayList<Map<String, Object>>();
            for (String relative : SKILL_PATHS.stream().sorted().toList()) {
                byte[] bytes = readRegular(skillRoot.resolve(relative));
                String text = utf8(bytes);
                if (relative.endsWith("/SKILL.md")) {
                    String name = relative.substring(0, relative.indexOf('/'));
                    if (!text.contains("name: " + name + "\n") && !text.contains("name: " + name + "\r\n")) {
                        throw invalidSkill();
                    }
                }
                if (relative.equals("flower-app-guide/references/00-guide-version.md")
                        && (!text.contains("Guide version: `0.7.0`")
                        || !text.contains("Target Flower version: `0.1.3`"))) throw invalidSkill();
                if (relative.equals("flower-action-runtime-guide/references/00-guide-version.md")
                        && (!text.contains("Guide version: `0.6.0`")
                        || !text.contains("Target runtime line: `flower-action-runtime 0.3.3`"))) throw invalidSkill();
                files.add(Map.of("path", relative, "contentHash", hasher.sha256(bytes).sha256(),
                        "sizeBytes", bytes.length, "text", text));
            }
            byte[] bytes = json(Map.ofEntries(
                    Map.entry("schemaVersion", "factory.flower-plugin-reference-bundle.v1"),
                    Map.entry("pluginId", "flower"), Map.entry("pluginVersion", SKILL_VERSION),
                    Map.entry("pluginManifestHash", hasher.sha256(pluginBytes).sha256()),
                    Map.entry("appGuideVersion", "0.7.0"), Map.entry("actionGuideVersion", "0.6.0"),
                    Map.entry("flowerVersion", "0.1.3"), Map.entry("actionRuntimeVersion", "0.3.3"),
                    Map.entry("selection", "code-owned-production-reference-allowlist-v1"),
                    Map.entry("files", files)));
            if (bytes.length > MAX_BUNDLE_BYTES) throw invalidSkill();
            return artifact(tenantId, "skill-bundle", bytes);
        } catch (IOException invalid) {
            throw invalidSkill();
        }
    }

    Artifact policyArtifact(TenantId tenantId) {
        return artifact(tenantId, "production-policy", json(Map.ofEntries(
                Map.entry("schemaVersion", "factory.maintenance-production-policy.v1"),
                Map.entry("recipeId", MaintenanceProductionRecipe.ID),
                Map.entry("javaVersion", "21"), Map.entry("flowerVersion", "0.1.3"),
                Map.entry("dependencyPolicy", "exact supplied Maven Central dependency/toolchain locks only; no upgrades"),
                Map.entry("skillPolicy", "selected installed Flower plugin source guidance; not extra tool or filesystem authority"),
                Map.entry("networkAllowed", false), Map.entry("webSearchAllowed", false),
                Map.entry("credentialAccessAllowed", false), Map.entry("deploymentAllowed", false),
                Map.entry("operationAllowed", false),
                Map.entry("authority", "WorkOrder and code-owned product/API/requirements/matrix/gate locks remain binding; blueprint is untrusted design context"),
                Map.entry("writeScope", "only exact paths authorized by the current WorkOrder; design and generation are separate orders"),
                Map.entry("sourcePolicy", "derive source from requirements; no copied generated candidate or trusted verifier implementation"),
                Map.entry("workerBuildEnvironment", "not provisioned by these inputs; public Maven file bytes are stored for independent Factory verification"),
                Map.entry("releasePolicy", "Worker self-tests are not independent verification; inspection and human release review remain mandatory"))));
    }

    Artifact policyArtifact(TenantId tenantId, String demoScenario) {
        String selected = io.github.flowerjvm.factory.application.production.MaintenanceRepairDemoScenario
                .recipeForSelection(demoScenario);
        return MaintenanceProductionRecipe.ID.equals(selected) ? policyArtifact(tenantId)
                : io.github.flowerjvm.factory.application.production.MaintenanceRepairDemoScenario.policyArtifact(tenantId, selected);
    }

    private Artifact dependencyLock(TenantId tenantId, Artifact manifest, List<String> paths, List<Artifact> materialized) {
        try {
            if (!manifest.reference().equals(Pr4MavenToolchainInstaller.EXPECTED_REFERENCE)
                    || !manifest.contentHash().equals(Pr4MavenToolchainInstaller.EXPECTED_HASH)
                    || !hasher.sha256(manifest.content()).equals(manifest.contentHash())) {
                throw new IllegalStateException("production toolchain manifest is not the compiled-in lock");
            }
            MavenToolchainLock toolchainLock = mapper.readValue(manifest.content(), MavenToolchainLock.class);
            List<MavenRepositoryFile> entries = toolchainLock.files().stream()
                    .filter(file -> paths.contains(file.path())).toList();
            if (entries.size() != 18 || materialized.size() != 18) {
                throw new IllegalStateException("production dependency subset is incomplete");
            }
            for (MavenRepositoryFile file : entries) {
                Artifact found = materialized.stream().filter(value -> value.reference().equals(file.artifactRef()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("production dependency is absent"));
                if (!tenantId.equals(found.tenantId()) || found.content().length != file.sizeBytes()
                        || !found.contentHash().equals(file.contentHash())
                        || !hasher.sha256(found.content()).equals(file.contentHash())) {
                    throw new IllegalStateException("production dependency bytes differ from their lock");
                }
            }
            var lock = new MavenDependencyLock(MavenDependencyLock.SCHEMA_VERSION,
                    MavenDependencyLock.REPOSITORY_ID, MavenDependencyLock.REPOSITORY_URL,
                    MavenDependencyLock.GRAPH_ALGORITHM_ID, DEPENDENCY_COORDINATES,
                    entries.size(), entries.stream().mapToLong(MavenRepositoryFile::sizeBytes).sum(), entries);
            return artifact(tenantId, "dependency-lock", json(lock));
        } catch (IOException invalid) {
            throw new IllegalStateException("production dependency lock could not be decoded", invalid);
        }
    }

    static List<String> dependencyPaths() {
        var paths = new ArrayList<String>();
        for (String coordinate : DEPENDENCY_COORDINATES) {
            String[] parts = coordinate.split(":");
            String prefix = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[3] + "/"
                    + parts[1] + "-" + parts[3];
            paths.add(prefix + ".jar");
            paths.add(prefix + ".pom");
        }
        return paths.stream().sorted().toList();
    }

    private byte[] readRegular(Path file) throws IOException {
        Path exact = file.toAbsolutePath().normalize();
        for (Path current = exact; current != null; current = current.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(current) || attributes.isOther()
                    || (current.equals(exact) ? !attributes.isRegularFile() : !attributes.isDirectory())) throw invalidSkill();
            if (current.equals(exact) && (attributes.size() < 1 || attributes.size() > MAX_TEXT_FILE_BYTES)) throw invalidSkill();
        }
        byte[] bytes;
        try (var input = Files.newInputStream(exact, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_TEXT_FILE_BYTES + 1);
        }
        if (bytes.length < 1 || bytes.length > MAX_TEXT_FILE_BYTES) throw invalidSkill();
        utf8(bytes);
        return bytes;
    }

    private static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private byte[] json(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (IOException invalid) { throw new IllegalStateException("production input serialization failed", invalid); }
    }

    private Artifact artifact(TenantId tenantId, String kind, byte[] bytes) {
        var hash = hasher.sha256(bytes);
        return new Artifact(tenantId, new ArtifactReference("factory-production/maintenance/" + kind + "/" + hash.sha256()),
                hash, "application/json", bytes);
    }

    private static CertificationArtifactLock lock(Artifact artifact) {
        return new CertificationArtifactLock(artifact.reference(), artifact.contentHash());
    }

    private static IllegalArgumentException invalidSkill() {
        return new IllegalArgumentException("installed Flower plugin Skill source is missing, unsafe, oversized, or incompatible");
    }

    public record PreparedInputs(AgentPackProductionPlan plan, List<Artifact> artifacts) {
        public PreparedInputs {
            Objects.requireNonNull(plan, "plan");
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
            if (artifacts.size() > 62 || artifacts.stream().mapToLong(value -> value.content().length).sum() > 32L * 1024 * 1024
                    || artifacts.stream().anyMatch(value -> !plan.tenantId().equals(value.tenantId()))) {
                throw new IllegalArgumentException("production inputs exceed tenant/count/byte bounds");
            }
        }
    }
}
