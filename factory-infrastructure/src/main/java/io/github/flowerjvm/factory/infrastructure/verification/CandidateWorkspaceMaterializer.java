package io.github.flowerjvm.factory.infrastructure.verification;

import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.ARTIFACT_CHECKSUM_MISMATCH;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.CANDIDATE_HASH_MISMATCH;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.MANIFEST_REFERENCE_UNRESOLVED;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.WORKSPACE_PATH_COLLISION;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.WORKSPACE_PATH_TRAVERSAL;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.WORKSPACE_QUOTA_EXCEEDED;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Materializes an immutable candidate manifest into a brand-new, bounded workspace. */
public final class CandidateWorkspaceMaterializer {
    public static final Limits DEFAULT_LIMITS = new Limits(2_048, 8L * 1024 * 1024, 64L * 1024 * 1024, 240);
    private static final Pattern WINDOWS_DEVICE_NAME =
            Pattern.compile("(?i)^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$");

    private final ArtifactStore artifactStore;
    private final OrdinalSourceTreeHasher hasher;
    private final Limits limits;

    public CandidateWorkspaceMaterializer(ArtifactStore artifactStore) {
        this(artifactStore, new OrdinalSourceTreeHasher(), DEFAULT_LIMITS);
    }

    public CandidateWorkspaceMaterializer(
            ArtifactStore artifactStore, OrdinalSourceTreeHasher hasher, Limits limits) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public MaterializedCandidate materialize(TenantId tenantId, CandidateSourceManifest manifest, Path workspace)
            throws CandidateMaterializationException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(workspace, "workspace");
        validateManifestQuotas(manifest);

        Path absoluteWorkspace = workspace.toAbsolutePath().normalize();
        if (Files.exists(absoluteWorkspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new CandidateMaterializationException(
                    WORKSPACE_PATH_COLLISION, "candidate workspace must not already exist");
        }
        Path parent = absoluteWorkspace.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new CandidateMaterializationException(
                    WORKSPACE_PATH_TRAVERSAL, "candidate workspace parent is not a regular directory");
        }

        var canonicalPaths = new HashSet<String>();
        var foldedPaths = new HashSet<String>();
        for (CandidateSourceEntry entry : manifest.files()) {
            validateCanonicalPath(entry.path(), canonicalPaths, foldedPaths);
        }

        var verifiedFiles = new ArrayList<VerifiedFile>(manifest.fileCount());
        for (CandidateSourceEntry entry : manifest.files()) {
            var artifact = artifactStore.find(tenantId, entry.artifactRef()).orElseThrow(() ->
                    new CandidateMaterializationException(
                            MANIFEST_REFERENCE_UNRESOLVED, "candidate file artifact is unavailable"));
            byte[] content = artifact.content();
            ContentHash computed = hasher.sha256(content);
            if (!artifact.tenantId().equals(tenantId)
                    || !artifact.reference().equals(entry.artifactRef())
                    || !artifact.contentHash().equals(entry.contentHash())
                    || !computed.equals(entry.contentHash())
                    || content.length != entry.sizeBytes()) {
                throw new CandidateMaterializationException(
                        ARTIFACT_CHECKSUM_MISMATCH, "candidate file artifact metadata or checksum differs");
            }
            verifiedFiles.add(new VerifiedFile(entry, content));
        }

        ContentHash declaredTreeHash = hasher.hashEntries(manifest.files());
        if (!declaredTreeHash.equals(manifest.candidateHash())) {
            throw new CandidateMaterializationException(
                    CANDIDATE_HASH_MISMATCH, "candidate source lock does not match manifest entries");
        }

        try {
            Files.createDirectory(absoluteWorkspace);
            for (VerifiedFile file : verifiedFiles) {
                Path output = resolveInside(absoluteWorkspace, file.entry().path());
                createRegularParents(absoluteWorkspace, output.getParent());
                Files.write(output, file.content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
                    throw new CandidateMaterializationException(
                            WORKSPACE_PATH_TRAVERSAL, "candidate artifact did not materialize as a regular file");
                }
            }
        } catch (CandidateMaterializationException exception) {
            throw exception;
        } catch (FileAlreadyExistsException exception) {
            throw new CandidateMaterializationException(
                    WORKSPACE_PATH_COLLISION, "candidate path collided during materialization", exception);
        } catch (IOException exception) {
            throw new CandidateMaterializationException(
                    WORKSPACE_PATH_TRAVERSAL, "candidate workspace could not be materialized", exception);
        }

        return new MaterializedCandidate(
                absoluteWorkspace, declaredTreeHash, Set.copyOf(canonicalPaths), manifest.fileCount(), manifest.totalBytes());
    }

    private void validateManifestQuotas(CandidateSourceManifest manifest) throws CandidateMaterializationException {
        if (manifest.fileCount() > limits.maxFiles() || manifest.totalBytes() > limits.maxTotalBytes()) {
            throw new CandidateMaterializationException(WORKSPACE_QUOTA_EXCEEDED, "candidate exceeds workspace quota");
        }
        for (CandidateSourceEntry file : manifest.files()) {
            if (file.sizeBytes() > limits.maxFileBytes() || file.path().length() > limits.maxPathCharacters()) {
                throw new CandidateMaterializationException(WORKSPACE_QUOTA_EXCEEDED, "candidate file exceeds quota");
            }
        }
    }

    private static void validateCanonicalPath(
            String path, Set<String> canonicalPaths, Set<String> foldedPaths)
            throws CandidateMaterializationException {
        if (path.isBlank()
                || path.startsWith("/")
                || path.startsWith("\\")
                || path.contains("\\")
                || path.indexOf('\0') >= 0
                || path.matches("^[A-Za-z]:.*")) {
            throw new CandidateMaterializationException(WORKSPACE_PATH_TRAVERSAL, "candidate path is not canonical");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()
                    || segment.equals(".")
                    || segment.equals("..")
                    || segment.endsWith(".")
                    || segment.endsWith(" ")
                    || segment.chars().anyMatch(character -> character < 32 || "<>:\"|?*".indexOf(character) >= 0)
                    || WINDOWS_DEVICE_NAME.matcher(segment).matches()) {
                throw new CandidateMaterializationException(
                        WORKSPACE_PATH_TRAVERSAL, "candidate path contains a forbidden segment");
            }
            if (segment.equals("target") || segment.equals(".factory-evidence")) {
                throw new CandidateMaterializationException(
                        WORKSPACE_PATH_TRAVERSAL, "candidate path uses a host-owned evidence/output directory");
            }
        }
        if (!canonicalPaths.add(path) || !foldedPaths.add(path.toLowerCase(Locale.ROOT))) {
            throw new CandidateMaterializationException(
                    WORKSPACE_PATH_COLLISION, "candidate contains duplicate or case-colliding paths");
        }
    }

    private static Path resolveInside(Path root, String canonicalPath) throws CandidateMaterializationException {
        Path result = root;
        for (String segment : canonicalPath.split("/")) {
            result = result.resolve(segment);
        }
        result = result.normalize();
        if (!result.startsWith(root)) {
            throw new CandidateMaterializationException(WORKSPACE_PATH_TRAVERSAL, "candidate path escaped workspace");
        }
        return result;
    }

    private static void createRegularParents(Path root, Path target) throws IOException, CandidateMaterializationException {
        if (target == null || target.equals(root)) {
            return;
        }
        Path relative = root.relativize(target);
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                    throw new CandidateMaterializationException(
                            WORKSPACE_PATH_COLLISION, "candidate parent path is not a regular directory");
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    public record Limits(int maxFiles, long maxFileBytes, long maxTotalBytes, int maxPathCharacters) {
        public Limits {
            if (maxFiles < 1 || maxFileBytes < 1 || maxTotalBytes < 1 || maxPathCharacters < 1) {
                throw new IllegalArgumentException("all workspace limits must be positive");
            }
            if (maxFileBytes > maxTotalBytes) {
                throw new IllegalArgumentException("per-file quota must not exceed aggregate quota");
            }
        }
    }

    public record MaterializedCandidate(
            Path workspace, ContentHash sourceHash, Set<String> sourcePaths, int fileCount, long totalBytes) {
        public MaterializedCandidate {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(sourceHash, "sourceHash");
            sourcePaths = Set.copyOf(Objects.requireNonNull(sourcePaths, "sourcePaths"));
        }
    }

    private record VerifiedFile(CandidateSourceEntry entry, byte[] content) {
        private VerifiedFile {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
