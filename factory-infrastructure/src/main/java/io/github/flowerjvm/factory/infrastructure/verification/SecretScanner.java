package io.github.flowerjvm.factory.infrastructure.verification;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** High-confidence source and evidence scanner that never exposes matched secret text. */
public final class SecretScanner {
    private static final List<Rule> RULES = List.of(
            new Rule("PRIVATE_KEY", Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")),
            new Rule("FACTORY_TEST_SECRET", Pattern.compile("FACTORY_TEST_SECRET")),
            new Rule("OPENAI_TOKEN", Pattern.compile("(?<![A-Za-z0-9])sk-(?:proj-)?[A-Za-z0-9_-]{20,}")),
            new Rule("GITHUB_TOKEN", Pattern.compile("(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9]{20,}")),
            new Rule("AWS_ACCESS_KEY", Pattern.compile("(?<![A-Z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Z0-9])")),
            new Rule("SLACK_TOKEN", Pattern.compile("(?<![A-Za-z0-9])xox[baprs]-[A-Za-z0-9-]{20,}")));

    public List<Finding> scan(String path, byte[] content) {
        Objects.requireNonNull(path, "path");
        content = Objects.requireNonNull(content, "content");
        if (content.length > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("secret scan input exceeds the bounded text evidence limit");
        }
        String text = new String(content, StandardCharsets.UTF_8);
        var findings = new ArrayList<Finding>();
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                findings.add(new Finding(path, rule.id()));
            }
        }
        return List.copyOf(findings);
    }

    public byte[] redact(byte[] content) {
        String redacted = new String(Objects.requireNonNull(content, "content"), StandardCharsets.UTF_8);
        for (Rule rule : RULES) {
            redacted = rule.pattern().matcher(redacted).replaceAll("[REDACTED:" + rule.id() + "]");
        }
        return redacted.getBytes(StandardCharsets.UTF_8);
    }

    public record Finding(String path, String ruleId) {
        public Finding {
            if (path == null || path.isBlank() || ruleId == null || ruleId.isBlank()) {
                throw new IllegalArgumentException("secret finding metadata must not be blank");
            }
        }
    }

    private record Rule(String id, Pattern pattern) {}
}
