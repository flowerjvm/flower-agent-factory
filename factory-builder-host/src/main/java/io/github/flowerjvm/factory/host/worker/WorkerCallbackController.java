package io.github.flowerjvm.factory.host.worker;

import io.github.flowerjvm.factory.application.work.WorkerCallbackCommand;
import io.github.flowerjvm.factory.application.work.WorkerCallbackAuditEvent;
import io.github.flowerjvm.factory.application.work.WorkerCallbackSecurityAudit;
import io.github.flowerjvm.factory.application.work.TrustedWorkerCallbackContext;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, bounded, non-disclosing Coding Worker callback endpoint. */
@RestController
@RequestMapping("/internal/v1/coding-worker")
public final class WorkerCallbackController {
    public static final int MAX_CALLBACK_BYTES = 64 * 1024;
    public static final String KEY_ID_HEADER = "X-Factory-Worker-Key-Id";
    public static final String TIMESTAMP_HEADER = "X-Factory-Worker-Timestamp";
    public static final String NONCE_HEADER = "X-Factory-Worker-Nonce";
    public static final String SIGNATURE_HEADER = "X-Factory-Worker-Signature";

    private final WorkerCallbackAuthenticator authenticator;
    private final ArtifactStore artifactStore;
    private final WorkerCallbackCommandHandler handler;
    private final WorkerCallbackSecurityAudit audit;
    private final WorkerCallbackRateLimiter rateLimiter;

    public WorkerCallbackController(
            WorkerCallbackAuthenticator authenticator,
            ArtifactStore artifactStore,
            WorkerCallbackCommandHandler handler,
            WorkerCallbackSecurityAudit audit,
            WorkerCallbackRateLimiter rateLimiter) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    }

    @PostMapping(path = "/callbacks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(
            @RequestHeader(name = KEY_ID_HEADER, required = false) String keyId,
            @RequestHeader(name = TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(name = NONCE_HEADER, required = false) String nonce,
            @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request) throws IOException {
        WorkerCallbackRateLimiter.Decision preAuthentication =
                rateLimiter.acquirePreAuthentication(request.getRemoteAddr());
        if (!preAuthentication.allowed()) {
            if (preAuthentication.auditSelected()) {
                rejectedAudit(
                        Optional.empty(),
                        "WORKER_CALLBACK_RATE_LIMITED",
                        preAuthentication.observedAt());
            }
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        Optional<TrustedWorkerCallbackContext> configured = authenticator.configuredContext(keyId);
        byte[] body;
        try {
            body = readBounded(request.getInputStream(), MAX_CALLBACK_BYTES);
        } catch (RequestTooLarge tooLarge) {
            rejectedAudit(
                    Optional.empty(),
                    "WORKER_CALLBACK_BODY_TOO_LARGE",
                    preAuthentication.observedAt());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        var authenticated = authenticator.authenticate(keyId, timestamp, nonce, signature, body);
        if (authenticated.isEmpty()) {
            rejectedAudit(configured, "WORKER_CALLBACK_AUTH_REJECTED", preAuthentication.observedAt());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        var authority = authenticated.orElseThrow();
        WorkerCallbackRateLimiter.Decision trustedAdmission = rateLimiter.acquireAuthenticated(
                bucketKey(
                        authority.trustedContext().tenantId().value(),
                        authority.trustedContext().workerBindingId(),
                        keyId));
        if (!trustedAdmission.allowed()) {
            if (trustedAdmission.auditSelected()) {
                rejectedAudit(
                        Optional.of(authority.trustedContext()),
                        "WORKER_CALLBACK_RATE_LIMITED",
                        trustedAdmission.observedAt());
            }
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        ContentHash hash = new ContentHash(authority.bodySha256());
        ArtifactReference reference = new ArtifactReference(
                "factory-worker/callback/v1/" + hash.sha256());
        artifactStore.store(new Artifact(
                authority.trustedContext().tenantId(),
                reference,
                hash,
                MediaType.APPLICATION_JSON_VALUE,
                body));
        handler.handle(new WorkerCallbackCommand(authority.trustedContext(), reference, hash));

        // Do not disclose whether the tenant-scoped WorkerRun/event existed or won a race.
        return ResponseEntity.accepted().build();
    }

    private void rejectedAudit(
            Optional<TrustedWorkerCallbackContext> configured,
            String code,
            java.time.Instant observedAt) {
        audit.record(new WorkerCallbackAuditEvent(
                configured.map(TrustedWorkerCallbackContext::tenantId),
                configured.map(TrustedWorkerCallbackContext::workerBindingId).orElse("UNRESOLVED"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                code,
                false,
                observedAt));
    }

    private static String bucketKey(String tenantId, String bindingId, String keyId) {
        return tenantId.length() + ":" + tenantId
                + bindingId.length() + ":" + bindingId
                + keyId.length() + ":" + keyId;
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            total += read;
            if (total > maximumBytes) {
                throw new RequestTooLarge();
            }
            output.write(buffer, 0, read);
        }
    }

    private static final class RequestTooLarge extends IOException {}
}
