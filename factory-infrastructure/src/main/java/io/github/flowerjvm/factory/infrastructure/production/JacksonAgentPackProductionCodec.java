package io.github.flowerjvm.factory.infrastructure.production;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.production.AgentPackProductionCodec;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPlan;
import io.github.flowerjvm.factory.application.production.MaintenanceProductionRecipe;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerRepairLock;
import io.github.flowerjvm.factory.contracts.worker.PortableRelativePath;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Explicit scalar JSON, sorted object keys, no record/default-typing serialization or coercion. */
public final class JacksonAgentPackProductionCodec implements AgentPackProductionCodec {
    public static final int MAX_PLAN_BYTES = 64 * 1024;
    public static final int MAX_WORKER_INPUT_BYTES = 256 * 1024;
    private static final Set<String> PLAN_FIELDS = Set.of(
            "schemaVersion", "tenantId", "buildSessionId", "recipeId", "requirements", "skillId",
            "skillVersion", "skill", "dependencyLock", "toolchainLock", "productContract",
            "apiSignatureIndex", "requirementTestMatrix", "gateProfile", "policySnapshot",
            "workspaceRef", "manager", "coding");
    private static final Set<String> LOCK_FIELDS = Set.of("reference", "hash");
    private static final Set<String> BINDING_FIELDS = Set.of("bindingId", "adapterVersion", "capabilities");
    private static final Set<String> BLUEPRINT_FIELDS = Set.of(
            "schemaVersion", "productContractHash", "summary", "sourceFiles", "designNotes");
    private final ObjectMapper mapper;
    private final JacksonWorkerProtocolArtifactDecoder workerDecoder;

    public JacksonAgentPackProductionCodec(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        // Host mapper modules, default typing and permissive parser flags are not protocol authority.
        this.mapper = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(16).maxNumberLength(20).maxStringLength(4096).build())
                .build());
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.workerDecoder = new JacksonWorkerProtocolArtifactDecoder(this.mapper);
    }

    @Override
    public byte[] writePlan(AgentPackProductionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        var fields = new TreeMap<String, Object>();
        fields.put("schemaVersion", plan.schemaVersion());
        fields.put("tenantId", plan.tenantId().value());
        fields.put("buildSessionId", plan.buildSessionId().value());
        fields.put("recipeId", plan.recipeId());
        fields.put("requirements", lock(plan.requirements()));
        fields.put("skillId", plan.skillId());
        fields.put("skillVersion", plan.skillVersion());
        fields.put("skill", lock(plan.skill()));
        fields.put("dependencyLock", lock(plan.dependencyLock()));
        fields.put("toolchainLock", lock(plan.toolchainLock()));
        fields.put("productContract", lock(plan.productContract()));
        fields.put("apiSignatureIndex", lock(plan.apiSignatureIndex()));
        fields.put("requirementTestMatrix", lock(plan.requirementTestMatrix()));
        fields.put("gateProfile", plan.gateProfile());
        fields.put("policySnapshot", lock(plan.policySnapshot()));
        fields.put("workspaceRef", plan.workspaceRef());
        fields.put("manager", binding(plan.manager()));
        fields.put("coding", binding(plan.coding()));
        byte[] bytes = write(fields, MAX_PLAN_BYTES);
        if (!readPlan(bytes).equals(plan)) throw invalid();
        return bytes;
    }

    @Override
    public AgentPackProductionPlan readPlan(byte[] bytes) {
        try {
            ObjectNode root = read(bytes, MAX_PLAN_BYTES);
            exactFields(root, PLAN_FIELDS);
            return new AgentPackProductionPlan(
                    text(root.get("schemaVersion"), 128),
                    new TenantId(text(root.get("tenantId"), 256)),
                    new BuildSessionId(text(root.get("buildSessionId"), 256)),
                    text(root.get("recipeId"), 128), readLock(root.get("requirements")),
                    text(root.get("skillId"), 128), text(root.get("skillVersion"), 64),
                    readLock(root.get("skill")), readLock(root.get("dependencyLock")),
                    readLock(root.get("toolchainLock")), readLock(root.get("productContract")),
                    readLock(root.get("apiSignatureIndex")), readLock(root.get("requirementTestMatrix")),
                    text(root.get("gateProfile"), 128), readLock(root.get("policySnapshot")),
                    text(root.get("workspaceRef"), 128), readBinding(root.get("manager")),
                    readBinding(root.get("coding")));
        } catch (RuntimeException rejected) { throw invalid(); }
    }

    @Override
    public byte[] writeWorkerInput(CodingWorkerInputManifest input) {
        Objects.requireNonNull(input, "input");
        var fields = new TreeMap<String, Object>();
        fields.put("schemaVersion", input.schemaVersion());
        fields.put("workOrderId", input.workOrderId().value());
        fields.put("buildSessionId", input.buildSessionId().value());
        fields.put("skillId", input.skillId());
        fields.put("skillVersion", input.skillVersion());
        fields.put("skillArtifactRef", input.skillArtifactRef().value());
        fields.put("skillHash", input.skillHash().sha256());
        fields.put("dependencyLockRef", input.dependencyLockRef().value());
        fields.put("dependencyLockHash", input.dependencyLockHash().sha256());
        fields.put("toolchainLockRef", input.toolchainLockRef().value());
        fields.put("toolchainLockHash", input.toolchainLockHash().sha256());
        fields.put("apiSignatureIndexRef", input.apiSignatureIndexRef().value());
        fields.put("apiSignatureIndexHash", input.apiSignatureIndexHash().sha256());
        fields.put("productContractBundleRef", input.productContractBundleRef().value());
        fields.put("productContractBundleHash", input.productContractBundleHash().sha256());
        fields.put("gateProfile", input.gateProfile());
        fields.put("requirementTestMatrixRef", input.requirementTestMatrixRef().value());
        fields.put("requirementTestMatrixHash", input.requirementTestMatrixHash().sha256());
        fields.put("sourceLockAlgorithmId", input.sourceLockAlgorithmId());
        fields.put("repairLock", input.repairLock().map(JacksonAgentPackProductionCodec::repair).orElse(null));
        byte[] bytes = write(fields, MAX_WORKER_INPUT_BYTES);
        requireWellFormedStrings(read(bytes, MAX_WORKER_INPUT_BYTES));
        if (!workerDecoder.decodeInputManifest(bytes).equals(input)) throw invalid();
        return bytes;
    }

    @Override
    public byte[] normalizeBlueprint(byte[] bytes) {
        try {
            ObjectNode root = read(bytes, MaintenanceProductionRecipe.MAX_BLUEPRINT_BYTES);
            exactFields(root, BLUEPRINT_FIELDS);
            if (!MaintenanceProductionRecipe.BLUEPRINT_SCHEMA_VERSION.equals(text(root.get("schemaVersion"), 128))
                    || !MaintenanceInvestigationProductContract.lock().hash().equals(hash(root.get("productContractHash")))) {
                throw invalid();
            }
            String summary = text(root.get("summary"), 2048);
            ArrayNode sources = array(root.get("sourceFiles"), 3, 64);
            var files = new ArrayList<String>();
            var folded = new HashSet<String>();
            for (JsonNode source : sources) {
                String file = PortableRelativePath.require(text(source, 512));
                if (!isSourceFile(file) || !folded.add(PortableRelativePath.caseFold(file))) throw invalid();
                files.add(file);
            }
            if (!files.contains("pom.xml") || !files.contains(MaintenanceProductionRecipe.BRIDGE_SOURCE_PATH)
                    || files.stream().noneMatch(file -> file.startsWith("src/test/java/") && file.endsWith(".java"))) {
                throw invalid();
            }
            for (String parent : folded) {
                if (folded.stream().anyMatch(file -> file.startsWith(parent + "/"))) throw invalid();
            }
            files.sort(String::compareTo);
            var notes = new ArrayList<String>();
            for (JsonNode note : array(root.get("designNotes"), 0, 16)) notes.add(text(note, 1024));
            var canonical = new TreeMap<String, Object>();
            canonical.put("schemaVersion", MaintenanceProductionRecipe.BLUEPRINT_SCHEMA_VERSION);
            canonical.put("productContractHash", MaintenanceInvestigationProductContract.lock().hash().sha256());
            canonical.put("summary", summary);
            canonical.put("sourceFiles", files);
            canonical.put("designNotes", notes);
            return write(canonical, MaintenanceProductionRecipe.MAX_BLUEPRINT_BYTES);
        } catch (RuntimeException rejected) {
            throw new IllegalArgumentException("MAINTENANCE_BLUEPRINT_INVALID");
        }
    }

    @Override
    public List<String> blueprintSourceFiles(byte[] bytes) {
        ObjectNode blueprint = read(normalizeBlueprint(bytes), MaintenanceProductionRecipe.MAX_BLUEPRINT_BYTES);
        var paths = new ArrayList<String>();
        blueprint.get("sourceFiles").forEach(value -> paths.add(value.textValue()));
        return List.copyOf(paths);
    }

    private static boolean isSourceFile(String file) {
        return file.equals("pom.xml")
                || ((file.startsWith("src/main/java/") || file.startsWith("src/test/java/"))
                        && file.endsWith(".java") && !file.endsWith("/.java"))
                || file.startsWith("src/main/resources/") || file.startsWith("src/test/resources/");
    }

    private ObjectNode read(byte[] bytes, int maximum) {
        if (bytes == null || bytes.length == 0 || bytes.length > maximum) throw invalid();
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (json.charAt(0) == '\ufeff') throw invalid();
            return object(mapper.readTree(json));
        } catch (IOException | RuntimeException rejected) { throw invalid(); }
    }

    private byte[] write(Object fields, int maximum) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(fields);
            if (bytes.length == 0 || bytes.length > maximum) throw invalid();
            return bytes;
        } catch (IOException | RuntimeException rejected) { throw invalid(); }
    }

    private static Map<String, Object> lock(CertificationArtifactLock lock) {
        var fields = new TreeMap<String, Object>();
        fields.put("reference", lock.reference().value());
        fields.put("hash", lock.hash().sha256());
        return fields;
    }

    private static CertificationArtifactLock readLock(JsonNode node) {
        ObjectNode value = object(node);
        exactFields(value, LOCK_FIELDS);
        return new CertificationArtifactLock(new ArtifactReference(text(value.get("reference"), 512)), hash(value.get("hash")));
    }

    private static Map<String, Object> binding(AgentPackProductionPlan.WorkerBinding binding) {
        var fields = new TreeMap<String, Object>();
        fields.put("bindingId", binding.bindingId());
        fields.put("adapterVersion", binding.adapterVersion());
        fields.put("capabilities", binding.capabilities().values().stream().map(WorkerCapability::value).sorted().toList());
        return fields;
    }

    private static AgentPackProductionPlan.WorkerBinding readBinding(JsonNode node) {
        ObjectNode value = object(node);
        exactFields(value, BINDING_FIELDS);
        var capabilities = new HashSet<WorkerCapability>();
        for (JsonNode capability : array(value.get("capabilities"), 0, 64)) {
            if (!capabilities.add(new WorkerCapability(text(capability, 128)))) throw invalid();
        }
        return new AgentPackProductionPlan.WorkerBinding(text(value.get("bindingId"), 128),
                text(value.get("adapterVersion"), 128), new WorkerCapabilities(capabilities));
    }

    private static Map<String, Object> repair(CodingWorkerRepairLock lock) {
        var fields = new TreeMap<String, Object>();
        fields.put("baseCandidateId", lock.baseCandidateId().value());
        fields.put("baseCandidateHash", lock.baseCandidateHash().sha256());
        fields.put("findingManifestRef", lock.findingManifestRef().value());
        fields.put("findingManifestHash", lock.findingManifestHash().sha256());
        fields.put("allowedChangedPaths", lock.allowedChangedPaths());
        fields.put("repairRound", lock.repairRound());
        fields.put("maxRepairRounds", lock.maxRepairRounds());
        return fields;
    }

    private static String text(JsonNode node, int maximum) {
        if (node == null || !node.isTextual()) throw invalid();
        String value = node.textValue();
        if (value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw invalid();
            } else if (Character.isLowSurrogate(current)) throw invalid();
        }
        return value;
    }

    private static ContentHash hash(JsonNode node) {
        String value = text(node, 64);
        if (!value.matches("[0-9a-f]{64}")) throw invalid();
        return new ContentHash(value);
    }

    private static void requireWellFormedStrings(JsonNode node) {
        if (node.isTextual()) text(node, 4096);
        else if (node.isContainerNode()) node.forEach(JacksonAgentPackProductionCodec::requireWellFormedStrings);
    }

    private static ArrayNode array(JsonNode node, int minimum, int maximum) {
        if (!(node instanceof ArrayNode array) || array.size() < minimum || array.size() > maximum) throw invalid();
        return array;
    }

    private static ObjectNode object(JsonNode node) {
        if (!(node instanceof ObjectNode object)) throw invalid();
        return object;
    }

    private static void exactFields(ObjectNode object, Set<String> expected) {
        var fields = new HashSet<String>();
        object.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(expected)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("AGENT_PACK_PRODUCTION_JSON_INVALID");
    }
}
