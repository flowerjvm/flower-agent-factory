package io.github.flowerjvm.factory.contracts.worker;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared fail-closed portable path validation for Worker read/write boundaries. */
public final class PortableRelativePath {
    private static final Pattern WINDOWS_DEVICE =
            Pattern.compile("(?i)^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$");

    private PortableRelativePath() {}

    public static String require(String path) {
        if (path == null || path.isBlank() || path.length() > 512
                || path.startsWith("/") || path.startsWith("\\") || path.contains("\\")
                || path.contains("%") || path.matches("^[A-Za-z]:.*")
                || path.chars().anyMatch(character -> character < 32)) {
            throw new IllegalArgumentException("path must be a bounded portable relative path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.chars().anyMatch(character -> "<>:\"|?*".indexOf(character) >= 0)
                    || WINDOWS_DEVICE.matcher(segment).matches()) {
                throw new IllegalArgumentException("path contains a forbidden portable segment");
            }
        }
        return path;
    }

    public static String caseFold(String path) {
        return require(path).toLowerCase(Locale.ROOT);
    }
}
