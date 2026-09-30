package io.github.flowerjvm.factory.infrastructure.verification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Implements {@code factory.ordinal-sha256.v1} without locale or platform path semantics. */
public final class OrdinalSourceTreeHasher {
    private static final HexFormat HEX = HexFormat.of();

    public ContentHash hashEntries(List<CandidateSourceEntry> entries) {
        var lines = entries.stream()
                .sorted(Comparator.comparing(CandidateSourceEntry::path))
                .map(entry -> entry.path() + "\t" + entry.contentHash().sha256())
                .toList();
        return sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    public ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
