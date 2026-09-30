package io.github.flowerjvm.factory.infrastructure.incidentapplication.tool;

import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationArtifacts.*;
import static io.github.flowerjvm.factory.infrastructure.incidentapplication.tool.IncidentApplicationFiles.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.certification.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.*;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.infrastructure.verification.CandidateSourceManifestReader;
import io.github.flowerjvm.factory.infrastructure.verification.OrdinalSourceTreeHasher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Concrete code-owned module assembly equipment, invoked only by the governed durable runner.
 * No LLM worker, candidate POM, host Java fallback, production service, certification or approval side door.
 */
public final class DockerIncidentApplicationProductionTool implements IncidentApplicationProductionTool {
    public static final String ALGORITHM = "factory.incident-application.deterministic-module-assembly.v1";
    public static final String MAIN_CLASS = "io.github.flowerjvm.product.incident.IncidentApplication";
    private static final String CORE_PREFIX = "src/main/java/io/github/flowerjvm/pack/maintenance/";
    private static final String PRODUCT_PREFIX = "io/github/flowerjvm/product/incident/";
    private static final List<String> CORE_FILES = List.of(CORE_PREFIX + "IncidentInvestigator.java", CORE_PREFIX + "InvestigationAcceptanceApi.java");
    static final Map<String, String> LIBRARIES = Map.of(
            "com/fasterxml/jackson/core/jackson-databind/2.21.4/jackson-databind-2.21.4.jar", "3888e9e69ab66fbacaacc9aea0e9ffbf15368288e4aca468b024dba11c09fbf9",
            "com/fasterxml/jackson/core/jackson-core/2.21.4/jackson-core-2.21.4.jar", "4b40a06396f239f8de2da57419adde6e94e5edc18a2171d471ea05eeed4e5c2d",
            "com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar", "53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b");
    private final ArtifactStore artifacts;
    private final CertifiedAgentComponentReadGate components;
    private final Path catalogRoot;
    private final Path mavenRepository;
    private final Path workspaceRoot;
    private final IncidentApplicationDocker docker;
    private final ObjectMapper mapper;

    public DockerIncidentApplicationProductionTool(ArtifactStore artifacts, CertifiedAgentComponentReadGate components,
            Path catalogRoot, Path mavenRepository, Path workspaceRoot, String dockerExecutable, Duration timeout) {
        this.artifacts = Objects.requireNonNull(artifacts); this.components = Objects.requireNonNull(components);
        this.catalogRoot = Objects.requireNonNull(catalogRoot).toAbsolutePath().normalize();
        this.mavenRepository = Objects.requireNonNull(mavenRepository).toAbsolutePath().normalize();
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot).toAbsolutePath().normalize();
        this.docker = new IncidentApplicationDocker(dockerExecutable, timeout);
        this.mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                .maxStringLength(262144).maxNumberLength(20).build());
    }

    @Override public IncidentApplicationBuildWorkOrder plan(IncidentApplicationOrder order) {
        componentSources(order); // A historical handoff index is deliberately not an input authority.
        try {
            var files = new TreeMap<String, byte[]>();
            for (String directory : List.of("common", "basic", "history")) {
                Path root = catalogRoot.resolve(directory); noLinks(root);
                try (var entries = Files.list(root)) {
                    for (Path source : entries.sorted().toList()) {
                        String name = source.getFileName().toString();
                        if (!name.matches("[A-Za-z][A-Za-z0-9]*\\.java")) throw invalid();
                        files.put(directory + "/" + name, read(source, 256 * 1024));
                    }
                }
            }
            if (!files.keySet().equals(Set.of("common/IncidentApplication.java", "common/HistoryStore.java", "common/JsonSupport.java", "common/WebPage.java",
                    "basic/SelectedHistoryStore.java", "history/SelectedHistoryStore.java"))) throw invalid();
            for (var library : new TreeMap<>(LIBRARIES).entrySet()) {
                byte[] bytes = read(mavenRepository.resolve(library.getKey()), MAX_FILE);
                if (!hash(bytes).equals(library.getValue())) throw new IllegalStateException("INCIDENT_APPLICATION_TOOLCHAIN_HASH_MISMATCH");
                files.put("lib/" + Path.of(library.getKey()).getFileName(), bytes);
            }
            files.put("inspector/FactoryIncidentHttpProbe.java", utf8(IncidentApplicationHttpProbeSource.SOURCE));
            files.put("inspector/golden.json", MaintenanceInvestigationProductContract.caseSuiteBytes());
            var entries = new ArrayList<Map<String,Object>>();
            for (var entry : files.entrySet()) entries.add(Map.of("path", entry.getKey(), "artifact", lockMap(storeBytes(order.tenantId(), "catalog-file", media(entry.getKey()), entry.getValue())), "sizeBytes", entry.getValue().length));
            var catalog = storeDocument(order.tenantId(), "module-catalog", Map.of("algorithmId", ALGORITHM,
                    "image", IncidentApplicationDocker.IMAGE, "files", entries, "variants", List.of("BASIC", "HISTORY")));
            var requirements = storeDocument(order.tenantId(), "requirements", requirements(order));
            var blueprint = storeDocument(order.tenantId(), "blueprint", blueprint(order, requirements, catalog));
            var policy = storeDocument(order.tenantId(), "build-policy", policy());
            var work = storeDocument(order.tenantId(), "build-work-order", workOrder(order, requirements, blueprint, catalog, policy));
            return new IncidentApplicationBuildWorkOrder(order, requirements, blueprint, catalog, policy, work, ALGORITHM);
        } catch (IOException failure) { throw new IllegalStateException("INCIDENT_APPLICATION_CATALOG_UNAVAILABLE", failure); }
    }

    @Override public IncidentApplicationPreparedProduct produce(IncidentApplicationBuildWorkOrder work) {
        var catalog = validateWorkOrder(work); var order = work.order(); var sources = sourceInputs(work, catalog);
        try {
            Map<String,byte[]> libraries = libraries(catalog);
            byte[] application = compileApplication(sources, libraries);
            var bomFields = new TreeMap<String,Object>();
            bomFields.put("order", orderMap(order)); bomFields.put("workOrder", lockMap(work.workOrder()));
            bomFields.put("moduleCatalog", lockMap(work.moduleCatalog())); bomFields.put("component", componentMap(order.component()));
            bomFields.put("selectedModules", selected(order)); bomFields.put("sourceFiles", inventory(sources));
            bomFields.put("runtimeLibraries", inventory(libraries)); bomFields.put("appJarSha256", hash(application));
            var bom = storeDocument(order.tenantId(), "bill-of-materials", bomFields);
            var bundleFiles = new TreeMap<>(libraries);
            bundleFiles.put("app.jar", application);
            bundleFiles.put("BOM.json", require(artifacts, order.tenantId(), bom).content());
            bundleFiles.put("product-config.json", canonical(config(order)));
            bundleFiles.put("source-lock.json", canonical(Map.of("component", componentMap(order.component()), "sourceFiles", inventory(sources))));
            bundleFiles.put("README.md", utf8(readme(order)));
            bundleFiles.put("run-local.ps1", utf8(IncidentApplicationLauncherSource.POWERSHELL));
            var bundle = storeBytes(order.tenantId(), "bundle", "application/zip", zip(bundleFiles));
            var candidate = storeDocument(order.tenantId(), "candidate", candidate(work, bom, bundle, bundleFiles));
            var evidence = storeDocument(order.tenantId(), "build-evidence", buildEvidence(work, candidate, bom, bundle));
            return new IncidentApplicationPreparedProduct(candidate, bom, bundle, evidence);
        } catch (IOException failure) { throw new IllegalStateException("INCIDENT_APPLICATION_BUILD_FAILED", failure); }
    }

    @Override public IncidentApplicationWholeVerification verify(IncidentApplicationBuildWorkOrder work, IncidentApplicationPreparedProduct product) {
        Map<String,byte[]> catalog = validateWorkOrder(work);
        Map<String,byte[]> bundle = validateProduct(work, product, catalog);
        var suite = storeDocument(work.order().tenantId(), "whole-product-fixture-suite", suiteFields());
        var phases = new ArrayList<Map<String,Object>>(); boolean passed;
        try {
            phases.addAll(inspectHttp(work.order().variant(), bundle));
            passed = phases.size() == (work.order().variant() == IncidentApplicationOrder.Variant.HISTORY ? 2 : 1)
                    && phases.stream().allMatch(value -> Boolean.TRUE.equals(value.get("passed")));
        } catch (RuntimeException failure) {
            // Stable failure evidence only; candidate stdout, host paths and incident payloads are not persisted.
            phases.add(Map.of("passed", false, "stableCode", "WHOLE_PRODUCT_EQUIPMENT_FAILED")); passed = false;
        }
        var code = passed ? "INCIDENT_APPLICATION_WHOLE_PRODUCT_PASSED" : "INCIDENT_APPLICATION_WHOLE_PRODUCT_FAILED";
        var report = storeDocument(work.order().tenantId(), "whole-product-verification", verificationFields(work, product, suite, passed, code, phases));
        return new IncidentApplicationWholeVerification(passed, code, report, suite);
    }

    @Override public void validate(IncidentApplicationBuildWorkOrder work, IncidentApplicationPreparedProduct product, IncidentApplicationWholeVerification verification) {
        Map<String,byte[]> catalog = validateWorkOrder(work); validateProduct(work, product, catalog);
        requireDocument(work.order().tenantId(), verification.fixtureSuite(), "whole-product-fixture-suite", suiteFields());
        JsonNode report = json(require(artifacts, work.order().tenantId(), verification.report()).content());
        List<Map<String,Object>> phases = maps(report.path("phases"));
        requireDocument(work.order().tenantId(), verification.report(), "whole-product-verification",
                verificationFields(work, product, verification.fixtureSuite(), verification.passed(), verification.stableCode(), phases));
        if (!verification.passed() || !verification.stableCode().equals("INCIDENT_APPLICATION_WHOLE_PRODUCT_PASSED")
                || phases.size() != (work.order().variant() == IncidentApplicationOrder.Variant.HISTORY ? 2 : 1)
                || phases.stream().anyMatch(value -> !Boolean.TRUE.equals(value.get("passed")))) throw invalid();
        for (int i = 0; i < phases.size(); i++) validateProbe(phases.get(i), work.order().variant(), i == 0 ? "initial" : "restart");
    }

    private Map<String,byte[]> validateWorkOrder(IncidentApplicationBuildWorkOrder work) {
        if (!ALGORITHM.equals(work.algorithmId())) throw invalid();
        componentSources(work.order());
        requireDocument(work.order().tenantId(), work.requirements(), "requirements", requirements(work.order()));
        requireDocument(work.order().tenantId(), work.blueprint(), "blueprint", blueprint(work.order(), work.requirements(), work.moduleCatalog()));
        requireDocument(work.order().tenantId(), work.policy(), "build-policy", policy());
        requireDocument(work.order().tenantId(), work.workOrder(), "build-work-order",
                workOrder(work.order(), work.requirements(), work.blueprint(), work.moduleCatalog(), work.policy()));
        JsonNode manifest = json(require(artifacts, work.order().tenantId(), work.moduleCatalog()).content());
        if (!manifest.path("algorithmId").asText().equals(ALGORITHM) || !manifest.path("image").asText().equals(IncidentApplicationDocker.IMAGE)) throw invalid();
        var entries = maps(manifest.path("files")); var files = new TreeMap<String,byte[]>();
        for (var entry : entries) {
            if (!entry.keySet().equals(Set.of("path", "artifact", "sizeBytes"))) throw invalid();
            String path = member((String)entry.get("path"));
            var lock = parseLock(mapper.valueToTree(entry.get("artifact")));
            byte[] bytes = require(artifacts, work.order().tenantId(), lock).content();
            if (!(entry.get("sizeBytes") instanceof Number size) || size.intValue() != bytes.length || files.put(path, bytes) != null) throw invalid();
        }
        var allowed = new TreeSet<>(List.of("common/IncidentApplication.java", "common/HistoryStore.java", "common/JsonSupport.java", "common/WebPage.java",
                "basic/SelectedHistoryStore.java", "history/SelectedHistoryStore.java", "inspector/FactoryIncidentHttpProbe.java", "inspector/golden.json"));
        LIBRARIES.forEach((path, sha) -> allowed.add("lib/" + Path.of(path).getFileName()));
        if (!files.keySet().equals(allowed) || !Arrays.equals(files.get("inspector/FactoryIncidentHttpProbe.java"), utf8(IncidentApplicationHttpProbeSource.SOURCE))
                || !Arrays.equals(files.get("inspector/golden.json"), MaintenanceInvestigationProductContract.caseSuiteBytes())) throw invalid();
        LIBRARIES.forEach((path, sha) -> { if (!hash(files.get("lib/" + Path.of(path).getFileName())).equals(sha)) throw invalid(); });
        requireDocument(work.order().tenantId(), work.moduleCatalog(), "module-catalog",
                Map.of("algorithmId", ALGORITHM, "image", IncidentApplicationDocker.IMAGE, "files", entries, "variants", List.of("BASIC", "HISTORY")));
        return files;
    }

    private Map<String,byte[]> componentSources(IncidentApplicationOrder order) {
        ResolvedCertifiedAgentComponent component = components.resolve(order.tenantId(), order.component());
        if (!component.reference().equals(order.component()) || !component.compatibilityDescriptor().tenantId().equals(order.tenantId())
                || !component.compatibilityDescriptor().productContractBundle().equals(MaintenanceInvestigationProductContract.lock())
                || !component.compatibilityDescriptor().apiSignatureIndex().equals(MaintenanceInvestigationProductContract.apiSignatureIndexLock())
                || !component.compatibilityDescriptor().gateProfile().equals(MaintenanceInvestigationProductContract.GATE_PROFILE)) throw invalid();
        try {
            var manifest = new CandidateSourceManifestReader(mapper).read(require(artifacts, order.tenantId(), order.component().sourceManifest()).content());
            if (!manifest.candidateId().equals(order.component().candidateId()) || !manifest.candidateHash().equals(order.component().candidateHash())
                    || !new OrdinalSourceTreeHasher().hashEntries(manifest.files()).equals(manifest.candidateHash())) throw invalid();
            var selected = new TreeMap<String,byte[]>();
            for (var entry : manifest.files()) {
                byte[] bytes = require(artifacts, order.tenantId(), new CertificationArtifactLock(entry.artifactRef(), entry.contentHash())).content();
                if (bytes.length != entry.sizeBytes()) throw invalid();
                if (CORE_FILES.contains(entry.path())) selected.put(entry.path(), bytes);
            }
            if (!selected.keySet().equals(new TreeSet<>(CORE_FILES))) throw invalid();
            return selected;
        } catch (IOException failure) { throw invalid(); }
    }

    private Map<String,byte[]> sourceInputs(IncidentApplicationBuildWorkOrder work, Map<String,byte[]> catalog) {
        var result = new TreeMap<>(componentSources(work.order()));
        catalog.forEach((path, bytes) -> { if (path.startsWith("common/")) result.put("src/main/java/" + PRODUCT_PREFIX + path.substring(7), bytes); });
        String selection = work.order().variant() == IncidentApplicationOrder.Variant.BASIC ? "basic" : "history";
        result.put("src/main/java/" + PRODUCT_PREFIX + "SelectedHistoryStore.java", catalog.get(selection + "/SelectedHistoryStore.java"));
        result.put("src/main/java/" + PRODUCT_PREFIX + "RuntimeConfiguration.java", utf8("package io.github.flowerjvm.product.incident;\npublic final class RuntimeConfiguration {\n"
                + "  public static final String VARIANT = \"" + work.order().variant().name() + "\";\n"
                + "  public static final String TITLE = \"" + title(work.order()) + "\";\n  private RuntimeConfiguration() { }\n}\n"));
        return result;
    }
    private Map<String,byte[]> validateProduct(IncidentApplicationBuildWorkOrder work, IncidentApplicationPreparedProduct product, Map<String,byte[]> catalog) {
        try {
            Map<String,byte[]> bundle = unzip(require(artifacts, work.order().tenantId(), product.bundle()).content());
            var expectedMembers = new TreeSet<>(libraries(catalog).keySet());
            expectedMembers.addAll(List.of("app.jar", "BOM.json", "product-config.json", "source-lock.json", "README.md", "run-local.ps1"));
            if (!bundle.keySet().equals(expectedMembers)) throw invalid();
            libraries(catalog).forEach((path, bytes) -> { if (!Arrays.equals(bytes, bundle.get(path))) throw invalid(); });
            var sourceFiles = inventory(sourceInputs(work, catalog));
            if (!Arrays.equals(bundle.get("BOM.json"), require(artifacts, work.order().tenantId(), product.billOfMaterials()).content())
                    || !Arrays.equals(bundle.get("product-config.json"), canonical(config(work.order())))
                    || !Arrays.equals(bundle.get("source-lock.json"), canonical(Map.of("component", componentMap(work.order().component()), "sourceFiles", sourceFiles)))
                    || !Arrays.equals(bundle.get("README.md"), utf8(readme(work.order())))
                    || !Arrays.equals(bundle.get("run-local.ps1"), utf8(IncidentApplicationLauncherSource.POWERSHELL))) throw invalid();
            requireDocument(work.order().tenantId(), product.billOfMaterials(), "bill-of-materials", Map.of(
                    "order", orderMap(work.order()), "workOrder", lockMap(work.workOrder()), "moduleCatalog", lockMap(work.moduleCatalog()),
                    "component", componentMap(work.order().component()), "selectedModules", selected(work.order()), "sourceFiles", sourceFiles,
                    "runtimeLibraries", inventory(libraries(catalog)), "appJarSha256", hash(bundle.get("app.jar"))));
            var classes = unzip(bundle.get("app.jar"));
            if (!classes.containsKey(PRODUCT_PREFIX + "IncidentApplication.class") || !classes.containsKey("io/github/flowerjvm/pack/maintenance/InvestigationAcceptanceApi.class")
                    || classes.keySet().stream().anyMatch(path -> !path.endsWith(".class"))) throw invalid();
            requireDocument(work.order().tenantId(), product.candidate(), "candidate", candidate(work, product.billOfMaterials(), product.bundle(), bundle));
            requireDocument(work.order().tenantId(), product.buildEvidence(), "build-evidence", buildEvidence(work, product.candidate(), product.billOfMaterials(), product.bundle()));
            return bundle;
        } catch (IOException failure) { throw invalid(); }
    }

    byte[] compileApplication(Map<String,byte[]> sources, Map<String,byte[]> libraries) throws IOException {
        Path root = newWorkspace(); String name = "factory-incident-build-" + UUID.randomUUID().toString().replace("-", "");
        try {
            materialize(root.resolve("source"), sources); materialize(root.resolve("libraries"), libraries);
            Path output = safeDirectory(root.resolve("classes")); docker.available();
            var args = docker.create(name, "/opt/java/openjdk/bin/javac");
            IncidentApplicationDocker.mount(args, root.resolve("source"), "/source", true);
            IncidentApplicationDocker.mount(args, root.resolve("libraries"), "/libraries", true);
            IncidentApplicationDocker.mount(args, output, "/output", false);
            args.add(IncidentApplicationDocker.IMAGE); args.addAll(List.of("-proc:none", "-g:none", "-encoding", "UTF-8", "--release", "21", "-cp", "/libraries/lib/*", "-d", "/output"));
            for (String source : new TreeSet<>(sources.keySet())) args.add("/source/" + member(source));
            docker.require(args); docker.require(List.of("start", "-a", name));
            var classes = readTree(output);
            if (classes.isEmpty() || classes.keySet().stream().anyMatch(path -> !path.endsWith(".class"))) throw invalid();
            return zip(classes);
        } finally { try { docker.remove(name); } finally { removeOwnedWorkspace(root, workspaceRoot); } }
    }

    List<Map<String,Object>> inspectHttp(IncidentApplicationOrder.Variant variant, Map<String,byte[]> bundle) {
        Path root;
        try { root = newWorkspace(); }
        catch (IOException failure) { throw new IllegalStateException("INCIDENT_APPLICATION_SANDBOX_UNAVAILABLE", failure); }
        String compile = "factory-incident-probe-" + UUID.randomUUID().toString().replace("-", "");
        String runtime = "factory-incident-http-" + UUID.randomUUID().toString().replace("-", "");
        try {
            materialize(root.resolve("product"), bundle);
            materialize(root.resolve("probe"), Map.of("FactoryIncidentHttpProbe.java", utf8(IncidentApplicationHttpProbeSource.SOURCE),
                    "golden.json", MaintenanceInvestigationProductContract.caseSuiteBytes()));
            Path output = safeDirectory(root.resolve("probe-classes")); docker.available();
            var args = docker.create(compile, "/opt/java/openjdk/bin/javac");
            IncidentApplicationDocker.mount(args, root.resolve("product"), "/product", true);
            IncidentApplicationDocker.mount(args, root.resolve("probe"), "/probe", true);
            IncidentApplicationDocker.mount(args, output, "/output", false);
            args.add(IncidentApplicationDocker.IMAGE); args.addAll(List.of("-proc:none", "-g:none", "-encoding", "UTF-8", "--release", "21", "-cp", "/product/lib/*", "-d", "/output", "/probe/FactoryIncidentHttpProbe.java"));
            docker.require(args); docker.require(List.of("start", "-a", compile));
            args = docker.create(runtime, "/opt/java/openjdk/bin/java");
            IncidentApplicationDocker.mount(args, root.resolve("product"), "/product", true);
            IncidentApplicationDocker.mount(args, root.resolve("probe"), "/probe", true);
            IncidentApplicationDocker.mount(args, output, "/probe-classes", true);
            args.addAll(List.of("--tmpfs", "/data:rw,nosuid,nodev,noexec,size=32m,mode=1777", "--env", "PORT=8080", "--env", "INCIDENT_DATA_DIR=/data", IncidentApplicationDocker.IMAGE,
                    "-Xmx192m", "-cp", "/probe-classes:/product/lib/*", "FactoryIncidentHttpProbe", variant.name()));
            docker.require(args);
            var execution = docker.call(List.of("start", "-a", runtime));
            JsonNode result = json(utf8(execution.output().trim()));
            if (execution.exitCode() != 0 || !result.path("passed").asBoolean()) {
                String failedCheck = result.path("failedCheck").asText("PRODUCT_EXECUTION_FAILED");
                if (!failedCheck.matches("[A-Z0-9_]{1,128}")) failedCheck = "PRODUCT_EXECUTION_FAILED";
                return List.of(Map.of("passed", false, "stableCode", "WHOLE_PRODUCT_HTTP_FAILED", "failedCheck", failedCheck));
            }
            var phases = maps(result.path("phases"));
            if (phases.size() != (variant == IncidentApplicationOrder.Variant.HISTORY ? 2 : 1)) throw invalid();
            for (int i = 0; i < phases.size(); i++) validateProbe(phases.get(i), variant, i == 0 ? "initial" : "restart");
            return phases;
        } catch (IOException failure) { throw new IllegalStateException("INCIDENT_APPLICATION_SANDBOX_FAILED", failure); }
        finally {
            try { docker.remove(runtime); }
            finally { try { docker.remove(compile); } finally { try { removeOwnedWorkspace(root, workspaceRoot); } catch (IOException failure) { throw new IllegalStateException("INCIDENT_APPLICATION_CLEANUP_FAILED", failure); } } }
        }
    }

    private void validateProbe(Map<String,Object> value, IncidentApplicationOrder.Variant variant, String phase) {
        if (!value.keySet().equals(Set.of("passed", "stableCode", "phase", "variant", "checks", "ids"))
                || !Boolean.TRUE.equals(value.get("passed")) || !"WHOLE_PRODUCT_HTTP_PASSED".equals(value.get("stableCode"))
                || !variant.name().equals(value.get("variant")) || !phase.equals(value.get("phase"))
                || !(value.get("checks") instanceof List<?> checks) || checks.isEmpty() || checks.size() > 512
                || checks.stream().anyMatch(item -> !(item instanceof String text) || !text.matches("[A-Z0-9_]+"))
                || !(value.get("ids") instanceof List<?> ids) || ids.size() > 100 || ids.stream().anyMatch(item -> !(item instanceof String text) || !text.matches("[0-9a-f]{64}"))) throw invalid();
        if (!checks.contains("EXACT_VARIANT_CONFIGURATION")) throw invalid();
        if (phase.equals("initial") && (!checks.contains("HTTP_CORE_CASE_INSENSITIVE_0") || !checks.contains("INPUT_SIZE_BOUND") || !checks.contains("FOREIGN_ORIGIN_DENIED")
                || !checks.contains(variant == IncidentApplicationOrder.Variant.HISTORY ? "HISTORY_DEDUPLICATED_LIST" : "BASIC_HISTORY_DISABLED"))) throw invalid();
        if (phase.equals("restart") && (!checks.contains("HISTORY_RESTART_PERSISTENCE") || !checks.contains("RESTART_REPORT_BYTES") || !checks.contains("HISTORY_CAPACITY_BOUND"))) throw invalid();
    }

    private Path newWorkspace() throws IOException { safeDirectory(workspaceRoot); return Files.createTempDirectory(workspaceRoot, "incident-"); }
    private static Map<String,byte[]> libraries(Map<String,byte[]> catalog) { var result = new TreeMap<String,byte[]>(); catalog.forEach((path, bytes) -> { if (path.startsWith("lib/")) result.put(path, bytes); }); return result; }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String media(String path) { return path.endsWith(".jar") ? "application/java-archive" : path.endsWith(".json") ? "application/json" : "text/x-java-source; charset=utf-8"; }
    private CertificationArtifactLock storeBytes(TenantId tenant, String kind, String media, byte[] bytes) {
        if (bytes.length > MAX_TOTAL + 128 * 1024) throw invalid(); String hash = hash(bytes);
        var value = new Artifact(tenant, new ArtifactReference("factory-incident-application/" + kind + "/sha256/" + hash), new ContentHash(hash), media, bytes);
        if (!artifacts.store(value).equals(value.reference())) throw invalid(); return lock(value);
    }
    private CertificationArtifactLock storeDocument(TenantId tenant, String kind, Map<String,Object> fields) {
        var value = document(tenant, kind, fields); if (!artifacts.store(value).equals(value.reference())) throw invalid(); return lock(value);
    }
    private void requireDocument(TenantId tenant, CertificationArtifactLock expected, String kind, Map<String,Object> fields) {
        Artifact actual = require(artifacts, tenant, expected), canonical = document(tenant, kind, fields);
        if (!lock(canonical).equals(expected) || !actual.mediaType().equals("application/json") || !Arrays.equals(actual.content(), canonical.content())) throw invalid();
    }
    private JsonNode json(byte[] bytes) { try { if (bytes.length > 512 * 1024) throw invalid(); JsonNode value = mapper.readTree(bytes); if (value == null || !value.isObject()) throw invalid(); return value; } catch (IOException failure) { throw invalid(); } }
    private List<Map<String,Object>> maps(JsonNode value) {
        if (!value.isArray() || value.size() > 512) throw invalid();
        for (JsonNode item : value) if (!item.isObject()) throw invalid();
        return mapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>() { });
    }
    private static CertificationArtifactLock parseLock(JsonNode node) {
        if (!node.isObject() || node.size() != 2 || !node.path("ref").isTextual() || !node.path("sha256").isTextual()) throw invalid();
        return new CertificationArtifactLock(new ArtifactReference(node.path("ref").asText()), new ContentHash(node.path("sha256").asText()));
    }
    private static String title(IncidentApplicationOrder order) { return order.variant() == IncidentApplicationOrder.Variant.BASIC ? "장애 조사 기본형" : "장애 조사 이력형"; }
    private static Map<String,Object> config(IncidentApplicationOrder order) { return Map.of("variant", order.variant().name(), "historyEnabled", order.variant() == IncidentApplicationOrder.Variant.HISTORY, "title", title(order)); }
    private static List<String> selected(IncidentApplicationOrder order) { return List.of("http-report-ui.v1", "certified-maintenance-investigation-core.v1", order.variant() == IncidentApplicationOrder.Variant.BASIC ? "no-history.v1" : "file-history.v1"); }
    private static Map<String,Object> orderMap(IncidentApplicationOrder order) { return Map.of("buildSessionId", order.buildSessionId().value(), "tenantId", order.tenantId().value(), "projectId", order.projectId().value(), "requestKey", order.requestKey(), "variant", order.variant().name(), "component", componentMap(order.component()), "deadlineAt", order.deadlineAt().toString()); }
    private static Map<String,Object> componentMap(CertifiedAgentComponentRef value) {
        return Map.ofEntries(Map.entry("schemaVersion", value.schemaVersion()), Map.entry("componentRole", value.componentRole()), Map.entry("productLineId", value.productLineId().value()),
                Map.entry("artifactType", value.artifactType().name()), Map.entry("certificationId", value.certificationId().value()), Map.entry("certificationManifest", lockMap(value.certificationManifest())),
                Map.entry("candidateId", value.candidateId().value()), Map.entry("candidateHash", value.candidateHash().sha256()), Map.entry("sourceManifest", lockMap(value.sourceManifest())),
                Map.entry("inputLockManifest", lockMap(value.inputLockManifest())), Map.entry("verificationRunId", value.verificationRunId().value()), Map.entry("verificationResultManifest", lockMap(value.verificationResultManifest())),
                Map.entry("compatibilityDescriptor", lockMap(value.compatibilityDescriptor())), Map.entry("certificationEvidence", lockMap(value.certificationEvidence())), Map.entry("certificationProfile", value.certificationProfile()));
    }
    private static Map<String,Object> requirements(IncidentApplicationOrder order) { return Map.of("order", orderMap(order), "productContract", "incident-application-http.v1", "historyEnabled", order.variant() == IncidentApplicationOrder.Variant.HISTORY, "historyCapacity", 100, "responsibility", "production-through-shipment-only"); }
    private static Map<String,Object> blueprint(IncidentApplicationOrder order, CertificationArtifactLock requirements, CertificationArtifactLock catalog) { return Map.of("requirements", lockMap(requirements), "moduleCatalog", lockMap(catalog), "selectedModules", selected(order), "configuration", config(order), "mainClass", MAIN_CLASS); }
    private static Map<String,Object> policy() { return Map.of("image", IncidentApplicationDocker.IMAGE, "network", "none", "nonRoot", true, "readOnlyRoot", true, "hostExecutionFallback", false, "candidatePomExecution", false, "compiler", "javac -proc:none -g:none -encoding UTF-8 --release 21", "maxRepairRounds", 0); }
    private static Map<String,Object> workOrder(IncidentApplicationOrder order, CertificationArtifactLock requirements, CertificationArtifactLock blueprint, CertificationArtifactLock catalog, CertificationArtifactLock policy) { return Map.of("order", orderMap(order), "requirements", lockMap(requirements), "blueprint", lockMap(blueprint), "moduleCatalog", lockMap(catalog), "policy", lockMap(policy), "algorithmId", ALGORITHM, "executionMode", "deterministic-module-assembly", "codingWorkerExecuted", false, "customizationGap", "none"); }
    private static Map<String,Object> candidate(IncidentApplicationBuildWorkOrder work, CertificationArtifactLock bom, CertificationArtifactLock bundle, Map<String,byte[]> files) { return Map.of("workOrder", lockMap(work.workOrder()), "billOfMaterials", lockMap(bom), "bundle", lockMap(bundle), "bundleFiles", inventory(files), "variant", work.order().variant().name()); }
    private static Map<String,Object> buildEvidence(IncidentApplicationBuildWorkOrder work, CertificationArtifactLock candidate, CertificationArtifactLock bom, CertificationArtifactLock bundle) { return Map.of("workOrder", lockMap(work.workOrder()), "candidate", lockMap(candidate), "billOfMaterials", lockMap(bom), "bundle", lockMap(bundle), "equipment", policy(), "compilation", "PASSED", "wholeProductVerification", "NOT_RUN", "codingWorkerExecuted", false); }
    private static Map<String,Object> suiteFields() { return Map.of("profile", "factory-incident-application-http-v1", "probeSourceSha256", hash(utf8(IncidentApplicationHttpProbeSource.SOURCE)), "componentGoldenSuiteSha256", hash(MaintenanceInvestigationProductContract.caseSuiteBytes()), "transport", "actual-http-inside-network-none-container", "historyRestart", "fresh-product-jvm-same-bounded-32mb-filesystem", "cases", List.of("UI_CONFIG", "CORE_16_CASES_18_INVOCATIONS", "MALFORMED_DUPLICATE_UTF8_DEPTH_SIZE", "FOREIGN_ORIGIN", "BASIC_NO_STORAGE", "HISTORY_QUERY_REPORT_DEDUPLICATION", "HISTORY_RESTART_PERSISTENCE", "HISTORY_CAPACITY", "TRAVERSAL")); }
    private static Map<String,Object> verificationFields(IncidentApplicationBuildWorkOrder work, IncidentApplicationPreparedProduct product, CertificationArtifactLock suite, boolean passed, String code, List<Map<String,Object>> phases) { return Map.of("workOrder", lockMap(work.workOrder()), "candidate", lockMap(product.candidate()), "bundle", lockMap(product.bundle()), "billOfMaterials", lockMap(product.billOfMaterials()), "buildEvidence", lockMap(product.buildEvidence()), "fixtureSuite", lockMap(suite), "passed", passed, "stableCode", code, "phases", phases); }
    private static String readme(IncidentApplicationOrder order) { return "# " + title(order) + "\n\nThis is a locally runnable demonstration product assembled by the incident-application Factory line.\n"
            + "Variant: " + order.variant().name() + ". Core component: " + order.component().certificationId().value() + ".\n"
            + "The included investigation core is rule based, not an autonomous LLM agent. No API key or Factory checkout is needed.\n\n"
            + "## Run locally\n\nPrerequisites: PowerShell 7.2+ and Docker with the exact locally available Java21 image named in run-local.ps1. No image is pulled automatically.\n"
            + "Run `pwsh -NoProfile -File ./run-local.ps1`, then open http://127.0.0.1:18080 . Ctrl+C stops the local listener and removes only its owned temporary container.\n"
            + "The product uses a network-none container. A fixed-route loopback HTTP relay passes bounded requests through Docker exec stdio; it grants no general proxy or external network access.\n"
            + "The default local demo lasts 900 seconds; `-Port` changes the loopback port and `-MaxSeconds` accepts 5 to 3600. Active bounded requests and cleanup may finish just after this duration.\n"
            + "HISTORY alone stores data in the local `incident-data` directory beside this bundle; back it up before deleting it. BASIC stores no history.\n"
            + "This product has no authentication or multi-user isolation. Do not publish its port or use actual customer data.\n\n"
            + "## Provenance\n\nBOM.json, source-lock.json and product-config.json describe the exact selected source modules, unchanged certified core and runtime libraries.\n"
            + "Whole-product certification and exact approved shipment evidence are supplied separately with the released handoff.\n"
            + "This launcher is a consumer-invoked local demonstration, not Factory deployment or continuous operations.\n"; }
}
