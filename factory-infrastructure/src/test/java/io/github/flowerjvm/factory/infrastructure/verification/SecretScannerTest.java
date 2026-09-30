package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SecretScannerTest {
    private final SecretScanner scanner = new SecretScanner();

    @Test
    void detectsHighConfidenceMarkersWithoutReturningSecretText() {
        String secret = "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789";
        var findings = scanner.scan("config.txt", ("value=" + secret).getBytes(StandardCharsets.UTF_8));

        assertEquals(1, findings.size());
        assertEquals("OPENAI_TOKEN", findings.getFirst().ruleId());
        assertFalse(findings.toString().contains(secret));
        assertFalse(new String(scanner.redact(secret.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)
                .contains(secret));
    }

    @Test
    void detectsPrivateKeyAndDeterministicTestMarker() {
        assertTrue(scanner.scan("key.pem", "-----BEGIN PRIVATE KEY-----".getBytes(StandardCharsets.UTF_8))
                .stream().anyMatch(finding -> finding.ruleId().equals("PRIVATE_KEY")));
        assertTrue(scanner.scan("fixture.txt", "FACTORY_TEST_SECRET=present".getBytes(StandardCharsets.UTF_8))
                .stream().anyMatch(finding -> finding.ruleId().equals("FACTORY_TEST_SECRET")));
    }
}
