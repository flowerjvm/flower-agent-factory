package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrdinalSourceTreeHasherTest {
    private final OrdinalSourceTreeHasher hasher = new OrdinalSourceTreeHasher();

    @Test
    void hashesOrdinalPathsWithTabLfAndNoTrailingLf() throws Exception {
        var a = entry("z.txt", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        var b = entry("A.txt", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        String canonical = "A.txt\t" + b.contentHash().sha256() + "\nz.txt\t" + a.contentHash().sha256();
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, hasher.hashEntries(List.of(a, b)).sha256());
        assertEquals(expected, hasher.hashEntries(List.of(b, a)).sha256());
        assertNotEquals(
                hasher.sha256((canonical + "\n").getBytes(StandardCharsets.UTF_8)),
                hasher.hashEntries(List.of(a, b)));
    }

    private static CandidateSourceEntry entry(String path, String hash) {
        return new CandidateSourceEntry(path, new ArtifactReference("artifact-" + path), new ContentHash(hash), 0);
    }
}
