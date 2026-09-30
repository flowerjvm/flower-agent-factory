package io.github.flowerjvm.factory.infrastructure.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import java.io.IOException;

/** Synthetic test bytes with the scalar wire shape emitted by CodexWorkerOutputPromoter. */
final class CandidateSourceManifestTestWire {
    private CandidateSourceManifestTestWire() {}

    static byte[] scalarBytes(ObjectMapper mapper, CandidateSourceManifest manifest) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", manifest.schemaVersion());
        root.put("candidateId", manifest.candidateId().value());
        root.put("buildSessionId", manifest.buildSessionId().value());
        root.put("sourceLockAlgorithmId", manifest.sourceLockAlgorithmId());
        root.put("candidateHash", manifest.candidateHash().sha256());
        root.put("fileCount", manifest.fileCount());
        root.put("totalBytes", manifest.totalBytes());
        var files = root.putArray("files");
        for (var entry : manifest.files()) {
            var file = files.addObject();
            file.put("path", entry.path());
            file.put("artifactRef", entry.artifactRef().value());
            file.put("contentHash", entry.contentHash().sha256());
            file.put("sizeBytes", entry.sizeBytes());
        }
        return mapper.writeValueAsBytes(root);
    }
}
