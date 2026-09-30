package io.github.flowerjvm.factory.host.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.work.WorkerCallbackAuditEvent;
import io.github.flowerjvm.factory.application.work.WorkerCallbackCommand;
import io.github.flowerjvm.factory.application.work.WorkerCallbackSecurityAudit;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class WorkerCallbackControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String KEY_ID = "codex-worker-key-1";
    private static final String NONCE = "nonce-1234567890abcdef";

    @Test
    void authenticatedBodyIsStoredInTenantScopeAndEndpointDoesNotExposeDomainOutcome() throws Exception {
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        AtomicReference<WorkerCallbackCommand> handled = new AtomicReference<>();
        WorkerCallbackController controller = controller(artifacts, handled::set, ignored -> {});
        byte[] body = "{\"schemaVersion\":\"coding-worker-completion.v1\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String timestamp = Long.toString(NOW.getEpochSecond());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);

        var response = controller.receive(
                KEY_ID,
                timestamp,
                NONCE,
                WorkerCallbackAuthenticator.signForTesting(SECRET, KEY_ID, timestamp, NONCE, body),
                request);

        assertEquals(202, response.getStatusCode().value());
        WorkerCallbackCommand command = handled.get();
        assertEquals(new TenantId("tenant-a"), command.trustedContext().tenantId());
        assertEquals("codex-primary", command.trustedContext().workerBindingId());
        Artifact stored = artifacts.find(command.trustedContext().tenantId(), command.payloadArtifactRef())
                .orElseThrow();
        assertEquals(command.payloadHash(), stored.contentHash());
        assertArrayEquals(body, stored.content());
        assertFalse(stored.reference().value().contains(KEY_ID));
    }

    @Test
    void tamperedOrStaleEnvelopeMutatesNothing() throws Exception {
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        AtomicReference<WorkerCallbackCommand> handled = new AtomicReference<>();
        List<WorkerCallbackAuditEvent> audit = new ArrayList<>();
        WorkerCallbackController controller = controller(artifacts, handled::set, audit::add);
        byte[] signed = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tampered = "{ }".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String stale = Long.toString(NOW.minus(Duration.ofMinutes(6)).getEpochSecond());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(tampered);

        var response = controller.receive(
                KEY_ID,
                stale,
                NONCE,
                WorkerCallbackAuthenticator.signForTesting(SECRET, KEY_ID, stale, NONCE, signed),
                request);

        assertEquals(401, response.getStatusCode().value());
        assertTrue(artifacts.values.isEmpty());
        assertEquals(null, handled.get());
        assertEquals(1, audit.size());
        assertEquals("WORKER_CALLBACK_AUTH_REJECTED", audit.getFirst().code());
        assertFalse(audit.getFirst().accepted());
    }

    @Test
    void oversizedBodyIsRejectedBeforeArtifactWrite() throws Exception {
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        List<WorkerCallbackAuditEvent> audit = new ArrayList<>();
        WorkerCallbackController controller = controller(artifacts, ignored -> {}, audit::add);
        byte[] body = new byte[WorkerCallbackController.MAX_CALLBACK_BYTES + 1];
        Arrays.fill(body, (byte) 'x');
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);

        var response = controller.receive(KEY_ID, "0", NONCE, "0".repeat(64), request);

        assertEquals(413, response.getStatusCode().value());
        assertTrue(artifacts.values.isEmpty());
        assertEquals("WORKER_CALLBACK_BODY_TOO_LARGE", audit.getFirst().code());
    }

    @Test
    void unauthenticatedFloodIsBoundedAndOnlyOneBlockedRequestPerWindowIsAudited() throws Exception {
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        List<WorkerCallbackAuditEvent> audit = new ArrayList<>();
        WorkerCallbackRateLimiter limiter = new WorkerCallbackRateLimiter(
                Clock.fixed(NOW, ZoneOffset.UTC), 2, 2, 16, Duration.ofSeconds(1));
        WorkerCallbackController controller = new WorkerCallbackController(
                authenticator(), artifacts, ignored -> {}, audit::add, limiter);

        for (int index = 0; index < 4; index++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setContent(new byte[0]);
            int status = controller.receive(
                            "unknown-key",
                            Long.toString(NOW.getEpochSecond()),
                            NONCE,
                            "0".repeat(64),
                            request)
                    .getStatusCode()
                    .value();
            assertEquals(index < 2 ? 401 : 429, status);
        }

        assertTrue(artifacts.values.isEmpty());
        assertEquals(3, audit.size());
        assertEquals(2, audit.stream()
                .filter(event -> event.code().equals("WORKER_CALLBACK_AUTH_REJECTED"))
                .count());
        assertEquals(1, audit.stream()
                .filter(event -> event.code().equals("WORKER_CALLBACK_RATE_LIMITED"))
                .count());
    }

    @Test
    void configuredTenantBucketsDoNotConsumeEachOthersAdmissionCapacity() {
        WorkerCallbackRateLimiter limiter = new WorkerCallbackRateLimiter(
                Clock.fixed(NOW, ZoneOffset.UTC), 2, 8, 16, Duration.ofSeconds(1));

        assertTrue(limiter.acquireAuthenticated("8:tenant-a9:binding-a5:key-a").allowed());
        assertTrue(limiter.acquireAuthenticated("8:tenant-a9:binding-a5:key-a").allowed());
        assertFalse(limiter.acquireAuthenticated("8:tenant-a9:binding-a5:key-a").allowed());
        assertTrue(limiter.acquireAuthenticated("8:tenant-b9:binding-b5:key-b").allowed());
    }

    @Test
    void highCardinalitySourceFloodHasGloballyBoundedDurableAuditSamples() {
        WorkerCallbackRateLimiter limiter = new WorkerCallbackRateLimiter(
                Clock.fixed(NOW, ZoneOffset.UTC), 32, 8, 128, Duration.ofSeconds(1));
        int selectedAuditSamples = 0;

        for (int source = 0; source < 2_048; source++) {
            for (int request = 0; request < 9; request++) {
                if (limiter.acquirePreAuthentication("198.51.100." + source)
                        .auditSelected()) {
                    selectedAuditSamples++;
                }
            }
        }

        assertTrue(selectedAuditSamples <= 16,
                "global admission must bound durable samples even when source buckets are evicted");
    }

    @Test
    void invalidSignaturesUsingAConfiguredKeyCannotConsumeItsAuthenticatedBucket() throws Exception {
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        List<WorkerCallbackAuditEvent> audit = new ArrayList<>();
        AtomicReference<WorkerCallbackCommand> handled = new AtomicReference<>();
        WorkerCallbackRateLimiter limiter = new WorkerCallbackRateLimiter(
                Clock.fixed(NOW, ZoneOffset.UTC), 1, 8, 16, Duration.ofSeconds(1));
        WorkerCallbackController controller = new WorkerCallbackController(
                authenticator(), artifacts, handled::set, audit::add, limiter);
        byte[] body = "{\"schemaVersion\":\"coding-worker-completion.v1\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String timestamp = Long.toString(NOW.getEpochSecond());

        for (int index = 0; index < 2; index++) {
            MockHttpServletRequest forged = new MockHttpServletRequest();
            forged.setRemoteAddr("192.0.2." + (10 + index));
            forged.setContent(body);
            assertEquals(401, controller.receive(
                            KEY_ID,
                            timestamp,
                            NONCE,
                            "0".repeat(64),
                            forged)
                    .getStatusCode()
                    .value());
        }

        MockHttpServletRequest authentic = new MockHttpServletRequest();
        authentic.setRemoteAddr("198.51.100.25");
        authentic.setContent(body);
        assertEquals(202, controller.receive(
                        KEY_ID,
                        timestamp,
                        NONCE,
                        WorkerCallbackAuthenticator.signForTesting(
                                SECRET, KEY_ID, timestamp, NONCE, body),
                        authentic)
                .getStatusCode()
                .value());
        assertEquals(new TenantId("tenant-a"), handled.get().trustedContext().tenantId());
        assertEquals(2, audit.stream()
                .filter(event -> event.code().equals("WORKER_CALLBACK_AUTH_REJECTED"))
                .count());
    }

    @Test
    void credentialAndAuthenticatorNeverRenderSecret() {
        WorkerCallbackCredential credential = credential();

        assertTrue(credential.toString().contains("[REDACTED]"));
        assertFalse(credential.toString().contains(new String(SECRET, java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(authenticator().authenticate(
                        KEY_ID,
                        Long.toString(NOW.getEpochSecond()),
                        NONCE,
                        WorkerCallbackAuthenticator.signForTesting(
                                SECRET, KEY_ID, Long.toString(NOW.getEpochSecond()), NONCE, new byte[0]),
                        new byte[0])
                .isPresent());
    }

    private static WorkerCallbackAuthenticator authenticator() {
        WorkerCallbackCredential credential = credential();
        return new WorkerCallbackAuthenticator(
                keyId -> credential.keyId().equals(keyId) ? Optional.of(credential) : Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));
    }

    private static WorkerCallbackCredential credential() {
        return new WorkerCallbackCredential(
                KEY_ID,
                new TenantId("tenant-a"),
                "codex-primary",
                "credential:codex-primary",
                SECRET);
    }

    private static WorkerCallbackController controller(
            ArtifactStore artifacts,
            WorkerCallbackCommandHandler handler,
            WorkerCallbackSecurityAudit audit) {
        return new WorkerCallbackController(
                authenticator(),
                artifacts,
                handler,
                audit,
                new WorkerCallbackRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static final class InMemoryArtifactStore implements ArtifactStore {
        private final Map<String, Artifact> values = new HashMap<>();

        @Override
        public ArtifactReference store(Artifact artifact) {
            String key = artifact.tenantId().value() + ':' + artifact.reference().value();
            Artifact current = values.putIfAbsent(key, artifact);
            if (current != null && (!current.contentHash().equals(artifact.contentHash())
                    || !Arrays.equals(current.content(), artifact.content()))) {
                throw new IllegalStateException("immutable artifact conflict");
            }
            return artifact.reference();
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(values.get(tenantId.value() + ':' + reference.value()));
        }
    }
}
