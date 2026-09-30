package io.github.flowerjvm.factory.contracts.artifact;

import java.util.Locale;
import java.util.regex.Pattern;

/** Lowercase SHA-256 content hash. */
public record ContentHash(String sha256) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ContentHash {
        if (sha256 == null || !SHA_256.matcher(sha256.toLowerCase(Locale.ROOT)).matches()) {
            throw new IllegalArgumentException("sha256 must contain exactly 64 hexadecimal characters");
        }
        sha256 = sha256.toLowerCase(Locale.ROOT);
    }
}
