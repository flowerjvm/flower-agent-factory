package io.github.flowerjvm.factory.infrastructure.worker.codex;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerRepairLock;
import io.github.flowerjvm.factory.contracts.worker.PortableRelativePath;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.infrastructure.verification.SecretScanner;
import io.github.flowerjvm.factory.infrastructure.verification.OrdinalSourceTreeHasher;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.UUID;

/** Resolves tenant-scoped immutable inputs and stages only hash-verified bounded bytes. */
public final class CodexWorkerInputMaterializer {
    private static final int MAX_INPUT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 32 * 1024 * 1024;
    private static final int MAX_WORKSPACE_FILES = 4096;
    private static final long MAX_WORKSPACE_BYTES = 128L * 1024 * 1024;
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "schemaVersion", "workOrderId", "buildSessionId", "skillId", "skillVersion",
            "skillArtifactRef", "skillHash", "dependencyLockRef", "dependencyLockHash",
            "toolchainLockRef", "toolchainLockHash", "apiSignatureIndexRef",
            "apiSignatureIndexHash", "productContractBundleRef", "productContractBundleHash",
            "gateProfile", "requirementTestMatrixRef", "requirementTestMatrixHash",
            "sourceLockAlgorithmId", "repairLock");
    private static final Set<String> REPAIR_FIELDS = Set.of(
            "baseCandidateId", "baseCandidateHash", "findingManifestRef", "findingManifestHash",
            "allowedChangedPaths", "repairRound", "maxRepairRounds");

    private final ArtifactStore artifacts;
    private final ObjectMapper objectMapper;
    private final SecretScanner secretScanner;
    private final Runnable afterWorkspacePublished;
    private final CandidateVersionRepository candidates;

    public CodexWorkerInputMaterializer(ArtifactStore artifacts, ObjectMapper objectMapper) {
        this(artifacts, objectMapper, new SecretScanner(), () -> {}, null);
    }

    public CodexWorkerInputMaterializer(
            ArtifactStore artifacts, ObjectMapper objectMapper, CandidateVersionRepository candidates) {
        this(artifacts, objectMapper, new SecretScanner(), () -> {}, Objects.requireNonNull(candidates, "candidates"));
    }

    CodexWorkerInputMaterializer(
            ArtifactStore artifacts, ObjectMapper objectMapper, SecretScanner secretScanner) {
        this(artifacts, objectMapper, secretScanner, () -> {});
    }

    CodexWorkerInputMaterializer(
            ArtifactStore artifacts,
            ObjectMapper objectMapper,
            SecretScanner secretScanner,
            Runnable afterWorkspacePublished) {
        this(artifacts, objectMapper, secretScanner, afterWorkspacePublished, null);
    }

    CodexWorkerInputMaterializer(
            ArtifactStore artifacts,
            ObjectMapper objectMapper,
            SecretScanner secretScanner,
            Runnable afterWorkspacePublished,
            CandidateVersionRepository candidates) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.objectMapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.objectMapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.secretScanner = Objects.requireNonNull(secretScanner, "secretScanner");
        this.afterWorkspacePublished = Objects.requireNonNull(
                afterWorkspacePublished, "afterWorkspacePublished");
        this.candidates = candidates;
    }

    public MaterializedInputs materialize(
            WorkOrder order, String operationId, Path stateRoot, Path workspaceRoot) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(stateRoot, "stateRoot");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Artifact instruction = exact(
                order.tenantId(), order.instructionArtifactRef(), Optional.of(order.instructionHash()),
                "CODING_WORKER_INSTRUCTION_UNAVAILABLE");
        Artifact manifestArtifact = exact(
                order.tenantId(), order.inputArtifactManifestRef(), Optional.of(order.inputManifestHash()),
                "CODING_WORKER_INPUT_MANIFEST_UNAVAILABLE");
        Artifact policy = exact(
                order.tenantId(), order.policySnapshotRef(), Optional.empty(),
                "CODING_WORKER_POLICY_UNAVAILABLE");
        CodingWorkerInputManifest manifest = decodeManifest(manifestArtifact.content());
        validateManifestOwner(order, manifest);
        // Resolve from the immutable ledger before looking at a previously staged operation, too.
        // A repair never falls back to arbitrary files under the configured source workspace.
        RepairSnapshot repairSnapshot = manifest.repairLock().isPresent()
                ? canonicalRepairSnapshot(order, manifest) : null;

        var locked = new ArrayList<NamedArtifact>();
        locked.add(named("skill", exact(order.tenantId(), manifest.skillArtifactRef(),
                Optional.of(manifest.skillHash()), "CODING_WORKER_SKILL_UNAVAILABLE")));
        locked.add(named("dependency-lock", exact(order.tenantId(), manifest.dependencyLockRef(),
                Optional.of(manifest.dependencyLockHash()), "CODING_WORKER_DEPENDENCY_LOCK_UNAVAILABLE")));
        locked.add(named("toolchain-lock", exact(order.tenantId(), manifest.toolchainLockRef(),
                Optional.of(manifest.toolchainLockHash()), "CODING_WORKER_TOOLCHAIN_LOCK_UNAVAILABLE")));
        locked.add(named("api-signature-index", exact(order.tenantId(), manifest.apiSignatureIndexRef(),
                Optional.of(manifest.apiSignatureIndexHash()), "CODING_WORKER_API_INDEX_UNAVAILABLE")));
        locked.add(named("product-contract-bundle", exact(order.tenantId(), manifest.productContractBundleRef(),
                Optional.of(manifest.productContractBundleHash()), "CODING_WORKER_PRODUCT_CONTRACT_UNAVAILABLE")));
        locked.add(named("requirement-test-matrix", exact(order.tenantId(), manifest.requirementTestMatrixRef(),
                Optional.of(manifest.requirementTestMatrixHash()), "CODING_WORKER_TEST_MATRIX_UNAVAILABLE")));
        manifest.repairLock().ifPresent(repair -> locked.add(named(
                "repair-finding",
                exact(order.tenantId(), repair.findingManifestRef(), Optional.of(repair.findingManifestHash()),
                        "CODING_WORKER_REPAIR_FINDING_UNAVAILABLE"))));

        Path operationDirectory;
        try {
            Path verifiedStateRoot = verifiedStateRoot(stateRoot);
            Path tenants = createOwnedDirectory(verifiedStateRoot, "tenants");
            Path tenant = createOwnedDirectory(tenants, tenantPartition(order.tenantId()));
            Path operations = createOwnedDirectory(tenant, "operations");
            operationDirectory = createOwnedDirectory(
                    operations, sha256(operationId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Path inputs = createOwnedDirectory(operationDirectory, "inputs");
            Path lockedDirectory = createOwnedDirectory(inputs, "locked");
            int total = 0;
            total = add(total, stage(inputs, inputs.resolve("instruction"), instruction.content()));
            total = add(total, stage(inputs, inputs.resolve("input-manifest.json"), manifestArtifact.content()));
            total = add(total, stage(inputs, inputs.resolve("policy"), policy.content()));
            for (NamedArtifact entry : locked) {
                total = add(total, stage(
                        lockedDirectory, lockedDirectory.resolve(entry.name()), entry.artifact().content()));
            }
            Path isolatedWorkspace = operationDirectory.resolve("workspace");
            materializeWorkspaceSnapshot(
                    order, operationId, workspaceRoot, operationDirectory, isolatedWorkspace, repairSnapshot);
            workspaceRoot = isolatedWorkspace;
        } catch (IOException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_STAGING_FAILED", false);
        }
        return new MaterializedInputs(manifest, operationDirectory, workspaceRoot);
    }

    private void materializeWorkspaceSnapshot(
            WorkOrder order,
            String operationId,
            Path configuredWorkspace,
            Path operationDirectory,
            Path isolatedWorkspace,
            RepairSnapshot repairSnapshot) throws IOException {
        byte[] marker = ("factory.codex-operation-workspace.v1\n"
                + sha256(operationId.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\n"
                + order.inputManifestHash().sha256() + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] stagingOwner = ("factory.codex-workspace-staging.v1\n"
                + sha256(operationId.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\n"
                + order.inputManifestHash().sha256() + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (WorkspaceMaterializationLock ignored = acquireWorkspaceMaterializationLock(operationDirectory)) {
            Path ready = operationDirectory.resolve("workspace.ready");
            Path baseline = operationDirectory.resolve("workspace-baseline.json");
            if (Files.exists(ready, LinkOption.NOFOLLOW_LINKS)) {
                verifyPublishedMetadata(
                        operationDirectory, isolatedWorkspace, ready, baseline, marker, false, repairSnapshot);
                return;
            }
            if (Files.exists(isolatedWorkspace, LinkOption.NOFOLLOW_LINKS)) {
                verifyPublishedMetadata(
                        operationDirectory, isolatedWorkspace, ready, baseline, marker, true, repairSnapshot);
                return;
            }

            Path staging = operationDirectory.resolve("workspace.staging");
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                quarantineOwnedStaging(operationDirectory, staging, stagingOwner);
            }
            Path preparing = createOwnedDirectory(
                    operationDirectory, "workspace.preparing-" + UUID.randomUUID());
            Path preparingMetadata = createOwnedDirectory(preparing, ".factory-snapshot");
            writeNewForced(preparingMetadata.resolve("staging-owner"), stagingOwner);
            atomicPublishDirectory(preparing, staging);

            Path sourceRoot = repairSnapshot == null ? verifiedSourceWorkspace(configuredWorkspace) : null;
            Map<String, Path> files = repairSnapshot == null ? collectWorkspaceFiles(order, sourceRoot) : Map.of();
            Set<String> sourcePaths = repairSnapshot == null ? files.keySet() : repairSnapshot.files().keySet();
            long total = 0;
            var baselineEntries = new ArrayList<WorkspaceBaselineEntry>();
            for (String sourcePath : sourcePaths.stream().sorted().toList()) {
                byte[] bytes = repairSnapshot == null
                        ? boundedSourceFile(files.get(sourcePath), sourceRoot) : repairSnapshot.files().get(sourcePath);
                try {
                    total = Math.addExact(total, bytes.length);
                } catch (ArithmeticException exception) {
                    throw new CodexWorkerProtocolException(
                            "CODING_WORKER_WORKSPACE_QUOTA_EXCEEDED", false);
                }
                if (total > MAX_WORKSPACE_BYTES) {
                    throw new CodexWorkerProtocolException(
                            "CODING_WORKER_WORKSPACE_QUOTA_EXCEEDED", false);
                }
                if (!secretScanner.scan(sourcePath, bytes).isEmpty()) {
                    throw new CodexWorkerProtocolException(
                            "CODING_WORKER_WORKSPACE_SECRET_DETECTED", false);
                }
                Path parent = createOwnedRelativeDirectory(
                        staging, Path.of(sourcePath).getParent());
                writeNewForced(parent.resolve(Path.of(sourcePath).getFileName()), bytes);
                baselineEntries.add(new WorkspaceBaselineEntry(
                        sourcePath, sha256(bytes), bytes.length));
            }
            byte[] baselineBytes = workspaceBaselineBytes(baselineEntries);
            Path metadata = createOwnedDirectory(staging, ".factory-snapshot");
            writeNewForced(metadata.resolve("workspace-baseline.json"), baselineBytes);
            writeNewForced(metadata.resolve("workspace.ready"), marker);
            atomicPublishDirectory(staging, isolatedWorkspace);
            afterWorkspacePublished.run();
            publishExternalWorkspaceMetadata(
                    operationDirectory, ready, baseline, marker, baselineBytes);
        }
    }

    private void verifyPublishedMetadata(
            Path operationDirectory,
            Path isolatedWorkspace,
            Path ready,
            Path baseline,
            byte[] marker,
            boolean recoverExternalMetadata,
            RepairSnapshot repairSnapshot) throws IOException {
        Path workspace = verifiedOwnedDirectory(operationDirectory, isolatedWorkspace);
        Path metadata = verifiedOwnedDirectory(workspace, workspace.resolve(".factory-snapshot"));
        byte[] internalReady = boundedRegularFile(
                metadata,
                metadata.resolve("workspace.ready"),
                marker.length,
                "CODING_WORKER_WORKSPACE_SNAPSHOT_INVALID");
        if (!MessageDigest.isEqual(internalReady, marker)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_SNAPSHOT_INVALID", false);
        }
        byte[] internalBaseline = boundedRegularFileMaximum(
                metadata,
                metadata.resolve("workspace-baseline.json"),
                2 * 1024 * 1024,
                "CODING_WORKER_WORKSPACE_SNAPSHOT_INVALID");
        if (repairSnapshot != null && !MessageDigest.isEqual(internalBaseline, repairSnapshot.baseline())) {
            throw new CodexWorkerProtocolException("CODING_WORKER_REPAIR_BASE_SNAPSHOT_MISMATCH", false);
        }
        if (recoverExternalMetadata) {
            byte[] currentBaseline = snapshotPublishedWorkspace(workspace);
            if (!MessageDigest.isEqual(internalBaseline, currentBaseline)) {
                throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_SNAPSHOT_PARTIAL", false);
            }
            publishExternalWorkspaceMetadata(
                    operationDirectory, ready, baseline, marker, internalBaseline);
            return;
        }
        byte[] externalReady = boundedRegularFile(
                operationDirectory,
                ready,
                marker.length,
                "CODING_WORKER_WORKSPACE_SNAPSHOT_INVALID");
        byte[] externalBaseline = boundedRegularFile(
                operationDirectory,
                baseline,
                internalBaseline.length,
                "CODING_WORKER_WORKSPACE_SNAPSHOT_INVALID");
        if (!MessageDigest.isEqual(externalReady, marker)
                || !MessageDigest.isEqual(externalBaseline, internalBaseline)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_SNAPSHOT_INVALID", false);
        }
    }

    private void publishExternalWorkspaceMetadata(
            Path operationDirectory,
            Path ready,
            Path baseline,
            byte[] marker,
            byte[] baselineBytes) throws IOException {
        stage(operationDirectory, baseline, baselineBytes);
        stage(operationDirectory, ready, marker);
    }

    private byte[] snapshotPublishedWorkspace(Path workspace) throws IOException {
        var entries = new ArrayList<WorkspaceBaselineEntry>();
        var folded = new HashSet<String>();
        long[] total = {0};
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory, BasicFileAttributes attributes) {
                rejectSourceRedirect(workspace, directory, attributes);
                if (!directory.equals(workspace)
                        && workspace.relativize(directory).toString().replace('\\', '/')
                                .equals(".factory-snapshot")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                        || Files.isSymbolicLink(file) || entries.size() >= MAX_WORKSPACE_FILES) {
                    throw new CodexWorkerProtocolException(
                            "CODING_WORKER_WORKSPACE_SNAPSHOT_PARTIAL", false);
                }
                try {
                    byte[] bytes = boundedSourceFile(file, workspace);
                    total[0] = Math.addExact(total[0], bytes.length);
                    if (total[0] > MAX_WORKSPACE_BYTES) {
                        throw new CodexWorkerProtocolException(
                                "CODING_WORKER_WORKSPACE_QUOTA_EXCEEDED", false);
                    }
                    String relative = PortableRelativePath.require(
                            workspace.relativize(file).toString().replace('\\', '/'));
                    if (!folded.add(relative.toLowerCase(java.util.Locale.ROOT))) {
                        throw new CodexWorkerProtocolException(
                                "CODING_WORKER_WORKSPACE_PATH_COLLISION", false);
                    }
                    entries.add(new WorkspaceBaselineEntry(relative, sha256(bytes), bytes.length));
                    return FileVisitResult.CONTINUE;
                } catch (IOException exception) {
                    throw new CodexWorkerProtocolException(
                            "CODING_WORKER_WORKSPACE_SNAPSHOT_PARTIAL", false);
                }
            }
        });
        entries.sort(Comparator.comparing(WorkspaceBaselineEntry::path));
        return workspaceBaselineBytes(entries);
    }

    private static void quarantineOwnedStaging(
            Path operationDirectory, Path staging, byte[] expectedOwner) throws IOException {
        Path ownedStaging = verifiedOwnedDirectory(operationDirectory, staging);
        Path metadata = verifiedOwnedDirectory(
                ownedStaging, ownedStaging.resolve(".factory-snapshot"));
        byte[] owner = boundedRegularFile(
                metadata,
                metadata.resolve("staging-owner"),
                expectedOwner.length,
                "CODING_WORKER_WORKSPACE_SNAPSHOT_PARTIAL");
        if (!MessageDigest.isEqual(owner, expectedOwner)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_SNAPSHOT_PARTIAL", false);
        }
        atomicPublishDirectory(
                ownedStaging,
                operationDirectory.resolve("workspace.quarantine-" + UUID.randomUUID()));
    }

    private byte[] workspaceBaselineBytes(List<WorkspaceBaselineEntry> entries) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", "factory.codex-workspace-baseline.v1");
        var files = root.putArray("files");
        entries.forEach(entry -> {
            ObjectNode file = files.addObject();
            file.put("path", entry.path());
            file.put("sha256", entry.sha256());
            file.put("sizeBytes", entry.sizeBytes());
        });
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(root);
            if (bytes.length > 2 * 1024 * 1024) {
                throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_BASELINE_TOO_LARGE", false);
            }
            return bytes;
        } catch (IOException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_BASELINE_INVALID", false);
        }
    }

    private static Map<String, Path> collectWorkspaceFiles(WorkOrder order, Path sourceRoot) {
        var result = new HashMap<String, Path>();
        var folded = new HashSet<String>();
        for (String scope : sourceScopes(order)) {
            Path root = sourceRoot.resolve(scope).normalize();
            if (!root.startsWith(sourceRoot) || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attributes) {
                        rejectSourceRedirect(sourceRoot, directory, attributes);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                                || Files.isSymbolicLink(file) || attributes.size() > MAX_INPUT_BYTES) {
                            throw new CodexWorkerProtocolException(
                                    "CODING_WORKER_WORKSPACE_FILE_INVALID", false);
                        }
                        rejectSourceRedirect(sourceRoot, file, attributes);
                        String relative = PortableRelativePath.require(
                                sourceRoot.relativize(file).toString().replace('\\', '/'));
                        if (result.size() >= MAX_WORKSPACE_FILES || result.putIfAbsent(relative, file) != null
                                || !folded.add(relative.toLowerCase(java.util.Locale.ROOT))) {
                            throw new CodexWorkerProtocolException(
                                    "CODING_WORKER_WORKSPACE_PATH_COLLISION", false);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException exception) {
                throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_SCAN_FAILED", false);
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> sourceScopes(WorkOrder order) {
        var candidates = new ArrayList<String>();
        order.allowedReadPaths().forEach(value -> candidates.add(PortableRelativePath.require(value)));
        order.allowedWritePaths().forEach(value -> candidates.add(PortableRelativePath.require(value)));
        candidates.sort(Comparator.comparingInt((String value) -> value.length())
                .thenComparing(Comparator.naturalOrder()));
        var result = new ArrayList<String>();
        var exact = new HashMap<String, String>();
        for (String candidate : candidates) {
            String folded = candidate.toLowerCase(java.util.Locale.ROOT);
            String previous = exact.putIfAbsent(folded, candidate);
            if (previous != null) {
                if (!previous.equals(candidate)) {
                    throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_PATH_COLLISION", false);
                }
                continue;
            }
            if (result.stream().map(value -> value.toLowerCase(java.util.Locale.ROOT))
                    .noneMatch(parent -> folded.equals(parent) || folded.startsWith(parent + "/"))) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
    }

    private static Path verifiedSourceWorkspace(Path configuredWorkspace) throws IOException {
        Path source = configuredWorkspace.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || Files.isSymbolicLink(source)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_INVALID", false);
        }
        return source.toRealPath();
    }

    private static void rejectSourceRedirect(
            Path sourceRoot, Path candidate, BasicFileAttributes attributes) {
        try {
            if (attributes.isSymbolicLink() || attributes.isOther() || Files.isSymbolicLink(candidate)
                    || !candidate.toRealPath().startsWith(sourceRoot)) {
                throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_LINK_REJECTED", false);
            }
        } catch (IOException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_LINK_REJECTED", false);
        }
    }

    private static byte[] boundedSourceFile(Path source, Path sourceRoot) throws IOException {
        if (!source.toRealPath().startsWith(sourceRoot)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_LINK_REJECTED", false);
        }
        BasicFileAttributes before = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || Files.isSymbolicLink(source)
                || before.size() > MAX_INPUT_BYTES) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_FILE_INVALID", false);
        }
        byte[] bytes = readBoundedNoFollow(source, MAX_INPUT_BYTES);
        BasicFileAttributes after = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.isSymbolicLink() || Files.isSymbolicLink(source)
                || before.size() != bytes.length || after.size() != bytes.length
                || (before.fileKey() != null && after.fileKey() != null
                        && !before.fileKey().equals(after.fileKey()))) {
            throw new CodexWorkerProtocolException("CODING_WORKER_WORKSPACE_FILE_INVALID", false);
        }
        return bytes;
    }

    private static Path createOwnedRelativeDirectory(Path root, Path relative) throws IOException {
        Path current = root;
        if (relative == null) {
            return current;
        }
        for (Path segment : relative) {
            current = createOwnedDirectory(current, segment.toString());
        }
        return current;
    }

    private Artifact exact(
            TenantId tenantId,
            ArtifactReference reference,
            Optional<ContentHash> expectedHash,
            String missingCode) {
        Artifact artifact = artifacts.find(tenantId, reference)
                .orElseThrow(() -> new CodexWorkerProtocolException(missingCode, false));
        byte[] bytes = artifact.content();
        if (bytes.length > MAX_INPUT_BYTES || !artifact.contentHash().sha256().equals(sha256(bytes))
                || expectedHash.filter(hash -> !hash.equals(artifact.contentHash())).isPresent()) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_INTEGRITY_FAILED", false);
        }
        if (!secretScanner.scan(reference.value(), bytes).isEmpty()) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_SECRET_DETECTED", false);
        }
        return artifact;
    }

    private CodingWorkerInputManifest decodeManifest(byte[] content) {
        try {
            JsonNode parsed = objectMapper.readTree(content);
            ObjectNode root = requireObject(parsed, "input manifest");
            exactFields(root, MANIFEST_FIELDS, "input manifest");
            Optional<CodingWorkerRepairLock> repair = Optional.empty();
            JsonNode repairNode = root.get("repairLock");
            if (repairNode != null && !repairNode.isNull()) {
                ObjectNode value = requireObject(repairNode, "repairLock");
                exactFields(value, REPAIR_FIELDS, "repairLock");
                repair = Optional.of(new CodingWorkerRepairLock(
                        new CandidateId(text(value, "baseCandidateId", 256)),
                        hash(value, "baseCandidateHash"),
                        reference(value, "findingManifestRef"),
                        hash(value, "findingManifestHash"),
                        textList(value, "allowedChangedPaths", 256),
                        integer(value, "repairRound"),
                        integer(value, "maxRepairRounds")));
            }
            return new CodingWorkerInputManifest(
                    text(root, "schemaVersion", 128),
                    new WorkOrderId(text(root, "workOrderId", 256)),
                    new BuildSessionId(text(root, "buildSessionId", 256)),
                    text(root, "skillId", 128),
                    text(root, "skillVersion", 64),
                    reference(root, "skillArtifactRef"), hash(root, "skillHash"),
                    reference(root, "dependencyLockRef"), hash(root, "dependencyLockHash"),
                    reference(root, "toolchainLockRef"), hash(root, "toolchainLockHash"),
                    reference(root, "apiSignatureIndexRef"), hash(root, "apiSignatureIndexHash"),
                    reference(root, "productContractBundleRef"), hash(root, "productContractBundleHash"),
                    text(root, "gateProfile", 128),
                    reference(root, "requirementTestMatrixRef"), hash(root, "requirementTestMatrixHash"),
                    text(root, "sourceLockAlgorithmId", 128), repair);
        } catch (CodexWorkerProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_MANIFEST_INVALID", false);
        }
    }

    private static void validateManifestOwner(WorkOrder order, CodingWorkerInputManifest manifest) {
        if (!manifest.workOrderId().equals(order.workOrderId())
                || !manifest.buildSessionId().equals(order.buildSessionId())) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_MANIFEST_OWNER_MISMATCH", false);
        }
        if (order.candidateId().isPresent() != manifest.repairLock().isPresent()) {
            throw new CodexWorkerProtocolException("CODING_WORKER_REPAIR_LOCK_MISMATCH", false);
        }
        manifest.repairLock().ifPresent(repair -> {
            if (!order.allowedWritePaths().equals(repair.allowedChangedPaths())
                    || order.candidateId().filter(candidate -> !candidate.equals(repair.baseCandidateId())).isPresent()) {
                throw new CodexWorkerProtocolException("CODING_WORKER_REPAIR_LOCK_MISMATCH", false);
            }
        });
    }

    private RepairSnapshot canonicalRepairSnapshot(WorkOrder order, CodingWorkerInputManifest input) {
        if (candidates == null) {
            throw new CodexWorkerProtocolException("CODING_WORKER_REPAIR_BASE_REPOSITORY_UNAVAILABLE", false);
        }
        try {
            var repair = input.repairLock().orElseThrow();
            if (!"generate-candidate".equals(order.phase())
                    || order.candidateId().filter(repair.baseCandidateId()::equals).isEmpty()) {
                throw new IllegalArgumentException("repair identity mismatch");
            }
            var base = candidates.find(order.tenantId(), repair.baseCandidateId())
                    .orElseThrow(() -> new IllegalArgumentException("repair base missing"));
            if (!base.tenantId().equals(order.tenantId())
                    || !base.buildSessionId().equals(order.buildSessionId())
                    || !base.candidateId().equals(repair.baseCandidateId())
                    || !base.sourceHash().equals(repair.baseCandidateHash())) {
                throw new IllegalArgumentException("repair base identity mismatch");
            }
            var protocolArtifacts = new WorkerProtocolArtifacts(
                    artifacts, new JacksonWorkerProtocolArtifactDecoder(objectMapper));
            CandidateSourceManifest source = protocolArtifacts.readCanonicalCandidateSource(order.tenantId(), base.sourceManifestRef());
            if (!source.candidateId().equals(base.candidateId())
                    || !source.buildSessionId().equals(order.buildSessionId())
                    || !source.candidateHash().equals(base.sourceHash())
                    || !source.sourceLockAlgorithmId().equals(input.sourceLockAlgorithmId())
                    || source.files().isEmpty()
                    || !new OrdinalSourceTreeHasher().hashEntries(source.files()).equals(base.sourceHash())) {
                throw new IllegalArgumentException("repair base source mismatch");
            }
            var scopes = sourceScopes(order);
            var files = new HashMap<String, byte[]>();
            var baseline = new ArrayList<WorkspaceBaselineEntry>();
            var folded = new HashSet<String>();
            for (var entry : source.files().stream().sorted(Comparator.comparing(value -> value.path())).toList()) {
                String file = PortableRelativePath.require(entry.path());
                String foldedFile = PortableRelativePath.caseFold(file);
                if (foldedFile.equals(".factory-snapshot") || foldedFile.startsWith(".factory-snapshot/")
                        || scopes.stream().noneMatch(scope -> file.equals(scope) || file.startsWith(scope + "/"))
                        || !folded.add(foldedFile)) {
                    throw new IllegalArgumentException("repair source outside scope or reserved metadata");
                }
                byte[] bytes = protocolArtifacts.exact(order.tenantId(), entry.artifactRef(), entry.contentHash()).content();
                if (bytes.length != entry.sizeBytes() || bytes.length > MAX_INPUT_BYTES
                        || !secretScanner.scan(file, bytes).isEmpty()) {
                    throw new IllegalArgumentException("repair source bytes invalid");
                }
                files.put(file, bytes);
                baseline.add(new WorkspaceBaselineEntry(file, entry.contentHash().sha256(), bytes.length));
            }
            for (String file : folded) {
                for (int slash = file.indexOf('/'); slash >= 0; slash = file.indexOf('/', slash + 1)) {
                    if (folded.contains(file.substring(0, slash))) {
                        throw new IllegalArgumentException("repair source file/directory collision");
                    }
                }
            }
            return new RepairSnapshot(Map.copyOf(files), workspaceBaselineBytes(baseline));
        } catch (RuntimeException rejected) {
            throw new CodexWorkerProtocolException("CODING_WORKER_REPAIR_BASE_INVALID", false);
        }
    }

    private int stage(Path trustedParent, Path destination, byte[] bytes) throws IOException {
        if (bytes.length > MAX_INPUT_BYTES) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_QUOTA_EXCEEDED", false);
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            byte[] existing = boundedRegularFile(
                    trustedParent, destination, bytes.length, "CODING_WORKER_STAGED_INPUT_CONFLICT");
            if (!MessageDigest.isEqual(existing, bytes)) {
                throw new CodexWorkerProtocolException("CODING_WORKER_STAGED_INPUT_CONFLICT", false);
            }
            return bytes.length;
        }
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".tmp-" + UUID.randomUUID());
        writeNewForced(temporary, bytes);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_ATOMIC_STAGE_UNAVAILABLE", false);
        }
        byte[] staged = boundedRegularFile(
                trustedParent, destination, bytes.length, "CODING_WORKER_INPUT_STAGING_FAILED");
        if (!MessageDigest.isEqual(staged, bytes)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_STAGING_FAILED", false);
        }
        return bytes.length;
    }

    private static int add(int current, int next) {
        int total;
        try {
            total = Math.addExact(current, next);
        } catch (ArithmeticException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_QUOTA_EXCEEDED", false);
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_QUOTA_EXCEEDED", false);
        }
        return total;
    }

    private static Path verifiedStateRoot(Path stateRoot) throws IOException {
        Path root = stateRoot.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || Files.isSymbolicLink(root)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_STATE_ROOT_INVALID", false);
        }
        Path resolved = root.toRealPath();
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_STATE_ROOT_INVALID", false);
        }
        return resolved;
    }

    /** Creates one factory-owned path component without following a pre-seeded redirect. */
    private static Path createOwnedDirectory(Path trustedParent, String childName) throws IOException {
        Path candidate = trustedParent.resolve(childName).normalize();
        if (!candidate.getParent().equals(trustedParent)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_STATE_PATH_INVALID", false);
        }
        try {
            Files.createDirectory(candidate);
        } catch (FileAlreadyExistsException existing) {
            // The component is verified below with NOFOLLOW before it is ever traversed.
        }
        BasicFileAttributes attributes = Files.readAttributes(
                candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || Files.isSymbolicLink(candidate)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_STAGED_INPUT_LINK_REJECTED", false);
        }
        Path resolved = candidate.toRealPath();
        if (!resolved.startsWith(trustedParent) || !resolved.getParent().equals(trustedParent)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_STATE_PATH_INVALID", false);
        }
        return resolved;
    }

    private static Path verifiedOwnedDirectory(Path trustedParent, Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(trustedParent)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_STATE_PATH_INVALID", false);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || Files.isSymbolicLink(normalized)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_STAGED_INPUT_LINK_REJECTED", false);
        }
        Path resolved = normalized.toRealPath();
        if (!resolved.getParent().equals(trustedParent)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_STATE_PATH_INVALID", false);
        }
        return resolved;
    }

    private static WorkspaceMaterializationLock acquireWorkspaceMaterializationLock(
            Path operationDirectory) throws IOException {
        Path lockPath = operationDirectory.resolve("workspace-materialization.lock");
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || Files.isSymbolicLink(lockPath) || attributes.size() != 0
                    || !lockPath.toRealPath().getParent().equals(operationDirectory)) {
                throw new CodexWorkerProtocolException(
                        "CODING_WORKER_WORKSPACE_LOCK_INVALID", false);
            }
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                lock = null;
            }
            if (lock == null) {
                throw new CodexWorkerProtocolException(
                        "CODING_WORKER_WORKSPACE_SNAPSHOT_BUSY", false);
            }
            return new WorkspaceMaterializationLock(channel, lock);
        } catch (RuntimeException | IOException failure) {
            channel.close();
            throw failure;
        }
    }

    private static void atomicPublishDirectory(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new CodexWorkerProtocolException(
                    "CODING_WORKER_WORKSPACE_ATOMIC_PUBLISH_UNAVAILABLE", false);
        }
    }

    private static void writeNewForced(Path destination, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                destination,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static byte[] boundedRegularFile(
            Path trustedParent, Path file, int expectedSize, String code) throws IOException {
        BasicFileAttributes before = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || Files.isSymbolicLink(file)
                || before.size() != expectedSize || before.size() > MAX_INPUT_BYTES
                || !file.toRealPath().getParent().equals(trustedParent)) {
            throw new CodexWorkerProtocolException(code, false);
        }
        byte[] bytes = readBoundedNoFollow(file, expectedSize);
        BasicFileAttributes after = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.isSymbolicLink() || Files.isSymbolicLink(file)
                || after.size() != bytes.length || after.size() != before.size()
                || (before.fileKey() != null && after.fileKey() != null
                        && !before.fileKey().equals(after.fileKey()))
                || !file.toRealPath().getParent().equals(trustedParent)) {
            throw new CodexWorkerProtocolException(code, false);
        }
        return bytes;
    }

    private static byte[] boundedRegularFileMaximum(
            Path trustedParent, Path file, int maximum, String code) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || Files.isSymbolicLink(file)
                || attributes.size() < 1 || attributes.size() > maximum
                || !file.toRealPath().getParent().equals(trustedParent)) {
            throw new CodexWorkerProtocolException(code, false);
        }
        return boundedRegularFile(trustedParent, file, Math.toIntExact(attributes.size()), code);
    }

    private static byte[] readBoundedNoFollow(Path file, int maximum) throws IOException {
        try (InputStream input = Files.newInputStream(
                        file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maximum) {
                    throw new CodexWorkerProtocolException("CODING_WORKER_STAGED_INPUT_CONFLICT", false);
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static ObjectNode requireObject(JsonNode value, String name) {
        if (!(value instanceof ObjectNode object)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_MANIFEST_INVALID", false);
        }
        return object;
    }

    private static void exactFields(ObjectNode object, Set<String> fields, String name) {
        var actual = new java.util.HashSet<String>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_MANIFEST_INVALID", false);
        }
    }

    private static String text(ObjectNode object, String field, int maximum) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_MANIFEST_INVALID", false);
        }
        String text = value.textValue();
        if (text.isBlank() || text.length() > maximum || text.chars().anyMatch(Character::isISOControl)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_MANIFEST_INVALID", false);
        }
        return text;
    }

    private static ArtifactReference reference(ObjectNode object, String field) {
        return new ArtifactReference(text(object, field, 512));
    }

    private static ContentHash hash(ObjectNode object, String field) {
        return new ContentHash(text(object, field, 64));
    }

    private static int integer(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isInt()) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_MANIFEST_INVALID", false);
        }
        return value.intValue();
    }

    private static List<String> textList(ObjectNode object, String field, int maximum) {
        JsonNode value = object.get(field);
        if (value == null || !value.isArray() || value.size() > maximum) {
            throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_MANIFEST_INVALID", false);
        }
        var result = new ArrayList<String>();
        value.forEach(entry -> {
            if (!entry.isTextual()) {
                throw new CodexWorkerProtocolException("CODING_WORKER_INPUT_MANIFEST_INVALID", false);
            }
            result.add(entry.textValue());
        });
        return List.copyOf(result);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    static String tenantPartition(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return sha256(("factory.codex-worker.tenant-partition.v1\n" + tenantId.value())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static NamedArtifact named(String name, Artifact artifact) {
        return new NamedArtifact(name, artifact);
    }

    public record MaterializedInputs(
            CodingWorkerInputManifest manifest,
            Path operationDirectory,
            Path workspaceRoot) {
        public MaterializedInputs {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(operationDirectory, "operationDirectory");
            Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        }
    }

    private record NamedArtifact(String name, Artifact artifact) {}

    private record WorkspaceBaselineEntry(String path, String sha256, long sizeBytes) {}

    private record RepairSnapshot(Map<String, byte[]> files, byte[] baseline) {}

    private record WorkspaceMaterializationLock(FileChannel channel, FileLock lock)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
