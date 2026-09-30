package io.github.flowerjvm.factory.infrastructure.worker.codex;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.CandidateOutputLock;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerResultManifest;
import io.github.flowerjvm.factory.contracts.worker.PortableRelativePath;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerProtocol;
import io.github.flowerjvm.factory.infrastructure.verification.OrdinalSourceTreeHasher;
import io.github.flowerjvm.factory.infrastructure.verification.SecretScanner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Promotes untrusted relative outputs into tenant-scoped immutable Factory artifacts. */
public final class CodexWorkerOutputPromoter {
    private static final int MAX_FILES = 4096;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final String EMPTY_SHA256 = sha256(new byte[0]);
    private static final Set<String> BASELINE_FIELDS = Set.of("schemaVersion", "files");
    private static final Set<String> BASELINE_ENTRY_FIELDS = Set.of("path", "sha256", "sizeBytes");

    private final ArtifactStore artifacts;
    private final ObjectMapper mapper;
    private final SecretScanner secrets;
    private final OrdinalSourceTreeHasher sourceHasher;
    private final Clock clock;

    public CodexWorkerOutputPromoter(ArtifactStore artifacts, ObjectMapper mapper) {
        this(artifacts, mapper, new SecretScanner(), new OrdinalSourceTreeHasher(), Clock.systemUTC());
    }

    CodexWorkerOutputPromoter(
            ArtifactStore artifacts,
            ObjectMapper mapper,
            SecretScanner secrets,
            OrdinalSourceTreeHasher sourceHasher,
            Clock clock) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.sourceHasher = Objects.requireNonNull(sourceHasher, "sourceHasher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PromotedResult promote(
            WorkOrder order,
            WorkerRunId workerRunId,
            String operationId,
            CodexWorkerProtocolClient.RawCompletion completion,
            CodexWorkerInputMaterializer.MaterializedInputs inputs) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(workerRunId, "workerRunId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(inputs, "inputs");
        if (!"SUCCEEDED".equals(completion.status()) || completion.outputs().isEmpty()) {
            throw new CodexWorkerProtocolException("CODING_WORKER_RESULT_INVALID", true);
        }
        boolean generation = isGenerationPhase(order.phase());
        boolean design = isDesignPhase(order.phase());
        if (!generation && !design) {
            throw new CodexWorkerProtocolException("CODING_WORKER_PHASE_UNSUPPORTED", false);
        }
        Path workspace = verifiedRoot(inputs.workspaceRoot());
        verifyDeclaredOutputs(workspace, order.allowedWritePaths(), completion.outputs());
        List<CandidateSourceEntry> entries = scanAndStoreCandidate(
                order, operationId, workspace, completion.outputs());
        ArtifactReference primaryReference;
        ContentHash primaryHash;
        Optional<CandidateOutputLock> candidateOutput;
        if (generation) {
            ContentHash candidateHash = sourceHasher.hashEntries(entries);
            CandidateId candidateId = new CandidateId("candidate-" + operationDigest(operationId));
            CandidateSourceManifest sourceManifest = new CandidateSourceManifest(
                    CandidateSourceManifest.SCHEMA_VERSION,
                    candidateId,
                    order.buildSessionId(),
                    inputs.manifest().sourceLockAlgorithmId(),
                    candidateHash,
                    entries.size(),
                    entries.stream().mapToLong(CandidateSourceEntry::sizeBytes).sum(),
                    entries);
            byte[] sourceManifestBytes = sourceManifestBytes(sourceManifest);
            ContentHash sourceManifestContentHash = hash(sourceManifestBytes);
            ArtifactReference sourceManifestRef = new ArtifactReference(
                    "factory-coding-worker/source-manifest/" + operationDigest(operationId)
                            + "/" + sourceManifestContentHash.sha256());
            artifacts.store(new Artifact(
                    order.tenantId(), sourceManifestRef, sourceManifestContentHash,
                    "application/json", sourceManifestBytes));
            primaryReference = sourceManifestRef;
            primaryHash = candidateHash;
            candidateOutput = Optional.of(new CandidateOutputLock(
                    candidateId,
                    inputs.manifest().repairLock().map(lock -> lock.baseCandidateId()),
                    sourceManifestRef,
                    sourceManifestContentHash,
                    candidateHash,
                    inputs.manifest().dependencyLockRef(),
                    inputs.manifest().dependencyLockHash(),
                    inputs.manifest().toolchainLockRef(),
                    inputs.manifest().toolchainLockHash()));
        } else {
            if (completion.outputs().size() != 1 || "DELETE".equals(completion.outputs().getFirst().kind())) {
                throw new CodexWorkerProtocolException("CODING_WORKER_DESIGN_OUTPUT_INVALID", true);
            }
            String primaryPath = PortableRelativePath.require(completion.outputs().getFirst().relativePath());
            CandidateSourceEntry primary = entries.stream()
                    .filter(entry -> entry.path().equals(primaryPath))
                    .findFirst()
                    .orElseThrow(() -> new CodexWorkerProtocolException(
                            "CODING_WORKER_DESIGN_OUTPUT_INVALID", true));
            primaryReference = primary.artifactRef();
            primaryHash = primary.contentHash();
            candidateOutput = Optional.empty();
        }

        Optional<ArtifactReference> transcriptRef = Optional.empty();
        Optional<ContentHash> transcriptHash = Optional.empty();
        if (completion.transcript().isPresent()) {
            CodexWorkerProtocolClient.RawTranscript transcript = completion.transcript().orElseThrow();
            Path transcriptFile = resolveNoFollow(inputs.operationDirectory(), transcript.relativePath());
            byte[] transcriptBytes = boundedRegularFile(transcriptFile, 64 * 1024, "CODING_WORKER_TRANSCRIPT_INVALID");
            ContentHash actual = hash(transcriptBytes);
            if (transcriptBytes.length != transcript.sizeBytes() || !actual.sha256().equals(transcript.sha256())
                    || !secrets.scan(transcript.relativePath(), transcriptBytes).isEmpty()) {
                throw new CodexWorkerProtocolException("CODING_WORKER_TRANSCRIPT_INVALID", true);
            }
            ArtifactReference reference = new ArtifactReference(
                    "factory-coding-worker/transcript/" + operationDigest(operationId) + "/" + actual.sha256());
            artifacts.store(new Artifact(order.tenantId(), reference, actual, "application/json", transcriptBytes));
            transcriptRef = Optional.of(reference);
            transcriptHash = Optional.of(actual);
        }

        CodingWorkerResultManifest result = new CodingWorkerResultManifest(
                WorkerProtocol.RESULT_SCHEMA_VERSION,
                order.workOrderId(),
                workerRunId,
                operationId,
                order.expectedOutputSchemaId(),
                order.expectedOutputSchemaVersion(),
                primaryReference,
                primaryHash,
                transcriptRef,
                transcriptHash,
                candidateOutput,
                clock.instant());
        byte[] resultBytes = resultManifestBytes(result);
        ContentHash resultContentHash = hash(resultBytes);
        ArtifactReference resultReference = new ArtifactReference(
                "factory-coding-worker/result-manifest/" + operationDigest(operationId)
                        + "/" + resultContentHash.sha256());
        artifacts.store(new Artifact(
                order.tenantId(), resultReference, resultContentHash,
                "application/json", resultBytes));
        return new PromotedResult(result, resultReference, resultContentHash);
    }

    private void verifyDeclaredOutputs(
            Path workspace,
            List<String> allowedWritePaths,
            List<CodexWorkerProtocolClient.RawOutput> outputs) {
        var allowed = allowedWritePaths.stream().map(PortableRelativePath::require).toList();
        var unique = new HashSet<String>();
        var folded = new HashSet<String>();
        for (CodexWorkerProtocolClient.RawOutput output : outputs) {
            String relative = PortableRelativePath.require(output.relativePath());
            if (!unique.add(relative) || !folded.add(PortableRelativePath.caseFold(relative))
                    || allowed.stream().noneMatch(path -> relative.equals(path) || relative.startsWith(path + "/"))) {
                throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_SCOPE_INVALID", true);
            }
            if ("DELETE".equals(output.kind())) {
                if (!EMPTY_SHA256.equals(output.sha256()) || output.sizeBytes() != 0
                        || Files.exists(workspace.resolve(relative), LinkOption.NOFOLLOW_LINKS)) {
                    throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_HASH_MISMATCH", true);
                }
                continue;
            }
            if (!"ADD".equals(output.kind()) && !"UPDATE".equals(output.kind())) {
                throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_KIND_INVALID", true);
            }
            byte[] bytes = boundedRegularFile(
                    resolveNoFollow(workspace, relative), MAX_FILE_BYTES,
                    "CODING_WORKER_OUTPUT_FILE_INVALID");
            if (bytes.length != output.sizeBytes() || !sha256(bytes).equals(output.sha256())) {
                throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_HASH_MISMATCH", true);
            }
        }
    }

    private List<CandidateSourceEntry> scanAndStoreCandidate(
            WorkOrder order,
            String operationId,
            Path workspace,
            List<CodexWorkerProtocolClient.RawOutput> declaredOutputs) {
        var files = new HashMap<String, Path>();
        var caseFolded = new HashSet<String>();
        // Immutable worker inputs live under stateRoot/operations, never in this workspace tree.
        // Candidate projection is the complete bounded source view (read + write scopes), while
        // verifyDeclaredOutputs above intentionally keeps mutation authorization write-only.
        for (String allowedValue : candidateSourceScopes(order)) {
            String allowed = PortableRelativePath.require(allowedValue);
            Path root = workspace.resolve(allowed).normalize();
            if (!root.startsWith(workspace) || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        if (attributes.isSymbolicLink() || attributes.isOther() || Files.isSymbolicLink(directory)) {
                            throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_LINK_REJECTED", true);
                        }
                        ensureWithinRealWorkspace(workspace, directory);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                                || Files.isSymbolicLink(file) || attributes.size() > MAX_FILE_BYTES) {
                            throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_FILE_INVALID", true);
                        }
                        ensureWithinRealWorkspace(workspace, file);
                        String relative = portableRelative(workspace, file);
                        if (files.size() >= MAX_FILES || files.putIfAbsent(relative, file) != null
                                || !caseFolded.add(PortableRelativePath.caseFold(relative))) {
                            throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_PATH_COLLISION", true);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException exception) {
                throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_SCAN_FAILED", true);
            }
        }
        if (files.isEmpty()) {
            throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_EMPTY", true);
        }
        var current = new HashMap<String, WorkspaceFile>();
        long[] promotedBytes = {0};
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            byte[] bytes = boundedRegularFile(entry.getValue(), MAX_FILE_BYTES, "CODING_WORKER_OUTPUT_FILE_INVALID");
            try {
                promotedBytes[0] = Math.addExact(promotedBytes[0], bytes.length);
            } catch (ArithmeticException exception) {
                throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_QUOTA_EXCEEDED", true);
            }
            if (promotedBytes[0] > MAX_TOTAL_BYTES) {
                throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_QUOTA_EXCEEDED", true);
            }
            if (!secrets.scan(entry.getKey(), bytes).isEmpty()) {
                throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_SECRET_DETECTED", true);
            }
            ContentHash contentHash = hash(bytes);
            current.put(entry.getKey(), new WorkspaceFile(contentHash.sha256(), bytes.length, bytes));
        });
        verifyCompleteWorkspaceDiff(
                order, loadBaseline(workspace.getParent()), current, declaredOutputs);
        var entries = new ArrayList<CandidateSourceEntry>();
        current.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            WorkspaceFile source = entry.getValue();
            ContentHash contentHash = new ContentHash(source.sha256());
            ArtifactReference reference = new ArtifactReference(
                    "factory-coding-worker/source/" + operationDigest(operationId) + "/" + contentHash.sha256());
            artifacts.store(new Artifact(
                    order.tenantId(), reference, contentHash, "application/octet-stream", source.bytes()));
            entries.add(new CandidateSourceEntry(entry.getKey(), reference, contentHash, source.sizeBytes()));
        });
        return List.copyOf(entries);
    }

    private static List<String> candidateSourceScopes(WorkOrder order) {
        List<String> candidates = Stream.concat(
                        order.allowedReadPaths().stream(), order.allowedWritePaths().stream())
                .map(PortableRelativePath::require)
                .sorted(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()))
                .toList();
        var scopes = new ArrayList<String>();
        var foldedExact = new HashMap<String, String>();
        for (String candidate : candidates) {
            String folded = PortableRelativePath.caseFold(candidate);
            String exact = foldedExact.putIfAbsent(folded, candidate);
            if (exact != null) {
                if (!exact.equals(candidate)) {
                    throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_PATH_COLLISION", true);
                }
                continue;
            }
            boolean nested = scopes.stream()
                    .map(PortableRelativePath::caseFold)
                    .anyMatch(parent -> folded.equals(parent) || folded.startsWith(parent + "/"));
            if (!nested) {
                scopes.add(candidate);
            }
        }
        return List.copyOf(scopes);
    }

    private Map<String, WorkspaceBaseline> loadBaseline(Path operationDirectory) {
        byte[] bytes = boundedRegularFile(
                operationDirectory.resolve("workspace-baseline.json"),
                2 * 1024 * 1024,
                "CODING_WORKER_WORKSPACE_BASELINE_INVALID");
        try {
            JsonNode parsed = mapper.readTree(bytes);
            if (!(parsed instanceof ObjectNode root)) {
                throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_BASELINE_INVALID", true);
            }
            exactJsonFields(root, BASELINE_FIELDS);
            if (!"factory.codex-workspace-baseline.v1".equals(jsonText(root, "schemaVersion", 128))) {
                throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_BASELINE_INVALID", true);
            }
            JsonNode fileNode = root.get("files");
            if (!(fileNode instanceof ArrayNode array) || array.size() > MAX_FILES) {
                throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_BASELINE_INVALID", true);
            }
            var result = new HashMap<String, WorkspaceBaseline>();
            var folded = new HashSet<String>();
            for (JsonNode node : array) {
                if (!(node instanceof ObjectNode file)) {
                    throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_BASELINE_INVALID", true);
                }
                exactJsonFields(file, BASELINE_ENTRY_FIELDS);
                String relative = PortableRelativePath.require(jsonText(file, "path", 1024));
                String hash = jsonText(file, "sha256", 64);
                JsonNode sizeNode = file.get("sizeBytes");
                if (!hash.matches("[0-9a-f]{64}") || sizeNode == null || !sizeNode.canConvertToLong()) {
                    throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_BASELINE_INVALID", true);
                }
                long size = sizeNode.longValue();
                if (size < 0 || size > MAX_FILE_BYTES || result.putIfAbsent(
                                relative, new WorkspaceBaseline(hash, size)) != null
                        || !folded.add(PortableRelativePath.caseFold(relative))) {
                    throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_BASELINE_INVALID", true);
                }
            }
            return Map.copyOf(result);
        } catch (CodexWorkerProtocolException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_BASELINE_INVALID", true);
        }
    }

    private static void verifyCompleteWorkspaceDiff(
            WorkOrder order,
            Map<String, WorkspaceBaseline> baseline,
            Map<String, WorkspaceFile> current,
            List<CodexWorkerProtocolClient.RawOutput> declaredOutputs) {
        var declared = new HashMap<String, CodexWorkerProtocolClient.RawOutput>();
        declaredOutputs.forEach(output -> declared.put(
                PortableRelativePath.require(output.relativePath()), output));
        List<String> allowedWrites = order.allowedWritePaths().stream()
                .map(PortableRelativePath::require)
                .toList();
        var paths = new TreeSet<String>();
        paths.addAll(baseline.keySet());
        paths.addAll(current.keySet());
        int changes = 0;
        for (String path : paths) {
            WorkspaceBaseline before = baseline.get(path);
            WorkspaceFile after = current.get(path);
            if (before != null && after != null
                    && before.sha256().equals(after.sha256()) && before.sizeBytes() == after.sizeBytes()) {
                continue;
            }
            changes++;
            if (allowedWrites.stream().noneMatch(
                    allowed -> path.equals(allowed) || path.startsWith(allowed + "/"))) {
                throw new CodexWorkerProtocolException("CODING_WORKER_READ_ONLY_MUTATION", true);
            }
            CodexWorkerProtocolClient.RawOutput output = declared.get(path);
            String expectedKind = before == null ? "ADD" : after == null ? "DELETE" : "UPDATE";
            String expectedHash = after == null ? EMPTY_SHA256 : after.sha256();
            long expectedSize = after == null ? 0 : after.sizeBytes();
            if (output == null || !expectedKind.equals(output.kind())
                    || !expectedHash.equals(output.sha256()) || expectedSize != output.sizeBytes()) {
                throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_DIFF_INVALID", true);
            }
        }
        if (changes != declared.size()) {
            throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_DIFF_INVALID", true);
        }
    }

    private static void exactJsonFields(ObjectNode object, Set<String> expected) {
        var actual = new HashSet<String>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_BASELINE_INVALID", true);
        }
    }

    private static String jsonText(ObjectNode object, String field, int maximum) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum
                || value.textValue().chars().anyMatch(Character::isISOControl)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_BASELINE_INVALID", true);
        }
        return value.textValue();
    }

    private byte[] sourceManifestBytes(CandidateSourceManifest manifest) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", manifest.schemaVersion());
        root.put("candidateId", manifest.candidateId().value());
        root.put("buildSessionId", manifest.buildSessionId().value());
        root.put("sourceLockAlgorithmId", manifest.sourceLockAlgorithmId());
        root.put("candidateHash", manifest.candidateHash().sha256());
        root.put("fileCount", manifest.fileCount());
        root.put("totalBytes", manifest.totalBytes());
        ArrayNode files = root.putArray("files");
        manifest.files().stream().sorted(Comparator.comparing(CandidateSourceEntry::path)).forEach(file -> {
            ObjectNode value = files.addObject();
            value.put("path", file.path());
            value.put("artifactRef", file.artifactRef().value());
            value.put("contentHash", file.contentHash().sha256());
            value.put("sizeBytes", file.sizeBytes());
        });
        return writeJson(root);
    }

    private byte[] resultManifestBytes(CodingWorkerResultManifest manifest) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", manifest.schemaVersion());
        root.put("workOrderId", manifest.workOrderId().value());
        root.put("workerRunId", manifest.workerRunId().value());
        root.put("operationId", manifest.operationId());
        root.put("outputSchemaId", manifest.outputSchemaId());
        root.put("outputSchemaVersion", manifest.outputSchemaVersion());
        root.put("primaryArtifactRef", manifest.primaryArtifactRef().value());
        root.put("primaryResultHash", manifest.primaryResultHash().sha256());
        manifest.transcriptArtifactRef().ifPresentOrElse(
                value -> root.put("transcriptArtifactRef", value.value()),
                () -> root.putNull("transcriptArtifactRef"));
        manifest.transcriptHash().ifPresentOrElse(
                value -> root.put("transcriptHash", value.sha256()),
                () -> root.putNull("transcriptHash"));
        manifest.candidateOutput().ifPresentOrElse(lock -> {
            ObjectNode candidate = root.putObject("candidateOutput");
            candidate.put("candidateId", lock.candidateId().value());
            lock.parentCandidateId().ifPresentOrElse(
                    value -> candidate.put("parentCandidateId", value.value()),
                    () -> candidate.putNull("parentCandidateId"));
            candidate.put("sourceManifestRef", lock.sourceManifestRef().value());
            candidate.put("sourceManifestHash", lock.sourceManifestHash().sha256());
            candidate.put("candidateHash", lock.candidateHash().sha256());
            candidate.put("dependencyLockRef", lock.dependencyLockRef().value());
            candidate.put("dependencyLockHash", lock.dependencyLockHash().sha256());
            candidate.put("toolchainLockRef", lock.toolchainLockRef().value());
            candidate.put("toolchainLockHash", lock.toolchainLockHash().sha256());
        }, () -> root.putNull("candidateOutput"));
        root.put("producedAt", manifest.producedAt().toString());
        return writeJson(root);
    }

    private byte[] writeJson(ObjectNode node) {
        try {
            return mapper.writeValueAsBytes(node);
        } catch (IOException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_RESULT_ENCODING_FAILED", true);
        }
    }

    private static Path verifiedRoot(Path root) {
        try {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_INVALID", true);
            }
            return root.toRealPath();
        } catch (IOException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_INVALID", true);
        }
    }

    private static Path resolveNoFollow(Path root, String relativeValue) {
        String relative = PortableRelativePath.require(relativeValue);
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_PATH_INVALID", true);
        }
        Path current = root;
        for (Path segment : root.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_LINK_REJECTED", true);
            }
        }
        return candidate;
    }

    private static void ensureWithinRealWorkspace(Path workspace, Path candidate) {
        try {
            if (!candidate.toRealPath().startsWith(workspace)) {
                throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_PATH_INVALID", true);
            }
        } catch (IOException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_OUTPUT_PATH_INVALID", true);
        }
    }

    private static String portableRelative(Path root, Path file) {
        return PortableRelativePath.require(root.relativize(file).toString().replace('\\', '/'));
    }

    private static byte[] boundedRegularFile(Path file, int maximum, String code) {
        try {
            BasicFileAttributes before = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.isSymbolicLink() || Files.isSymbolicLink(file)
                    || before.size() > maximum) {
                throw new CodexWorkerProtocolException(code, true);
            }
            byte[] bytes = readBoundedNoFollow(file, maximum, code);
            BasicFileAttributes after = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || after.isSymbolicLink() || Files.isSymbolicLink(file)
                    || bytes.length != before.size() || bytes.length != after.size()
                    || (before.fileKey() != null && after.fileKey() != null
                            && !before.fileKey().equals(after.fileKey()))) {
                throw new CodexWorkerProtocolException(code, true);
            }
            return bytes;
        } catch (IOException exception) {
            throw new CodexWorkerProtocolException(code, true);
        }
    }

    private static byte[] readBoundedNoFollow(Path file, int maximum, String code) throws IOException {
        try (InputStream input = Files.newInputStream(
                        file, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maximum) {
                    throw new CodexWorkerProtocolException(code, true);
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static boolean isGenerationPhase(String phase) {
        return "generate-candidate".equals(phase) || "GENERATE_CANDIDATE".equals(phase);
    }

    private static boolean isDesignPhase(String phase) {
        return "design-agent".equals(phase) || "DESIGN_CANDIDATE".equals(phase);
    }

    private static String operationDigest(String operationId) {
        return sha256(operationId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static ContentHash hash(byte[] bytes) {
        return new ContentHash(sha256(bytes));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public record PromotedResult(
            CodingWorkerResultManifest manifest,
            ArtifactReference manifestReference,
            ContentHash manifestContentHash) {
        public PromotedResult {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(manifestReference, "manifestReference");
            Objects.requireNonNull(manifestContentHash, "manifestContentHash");
        }
    }

    private record WorkspaceBaseline(String sha256, long sizeBytes) {}

    private record WorkspaceFile(String sha256, long sizeBytes, byte[] bytes) {
        private WorkspaceFile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
