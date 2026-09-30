package io.github.flowerjvm.factory.infrastructure.worker.codex;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerAttemptProofs;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorker;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.PortableRelativePath;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerCancelRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerCancelResult;
import io.github.flowerjvm.factory.contracts.worker.WorkerAttemptToken;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilityCatalog;
import io.github.flowerjvm.factory.contracts.worker.WorkerDispatchException;
import io.github.flowerjvm.factory.contracts.worker.WorkerDispatchRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerEffectCertainty;
import io.github.flowerjvm.factory.contracts.worker.WorkerLookupState;
import io.github.flowerjvm.factory.contracts.worker.WorkerOperationNotFoundException;
import io.github.flowerjvm.factory.contracts.worker.WorkerProtocol;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunSnapshot;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.contracts.worker.WorkerStatusObservation;
import io.github.flowerjvm.factory.contracts.worker.WorkerStatusRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerSubmission;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Production Codex-only adapter backed by the durable local Node operation protocol. */
public final class CodexCodingWorker implements CodingWorker {
    public static final String ADAPTER_VERSION = CodexWorkerProtocolClient.PROTOCOL_VERSION;
    private static final String STATUS_SNAPSHOT_PROOF_VERSION =
            "factory.worker.status-snapshot-proof.v2";
    private static final Map<String, WorkerCapability> CAPABILITY_CATALOG = catalog();
    private static final Set<WorkerCapability> SUPPORTED = Set.of(
            WorkerCapabilityCatalog.REPOSITORY_READ,
            WorkerCapabilityCatalog.BOUNDED_PATCH_WRITE,
            WorkerCapabilityCatalog.FILE_CREATE,
            WorkerCapabilityCatalog.COMMAND_BUILD_TEST,
            WorkerCapabilityCatalog.STRUCTURED_OUTPUT,
            WorkerCapabilityCatalog.PROGRESS_EVENTS,
            WorkerCapabilityCatalog.COOPERATIVE_CANCEL,
            WorkerCapabilityCatalog.USAGE_REPORTING,
            WorkerCapabilityCatalog.SANDBOX_ENFORCEMENT);

    private final CodexWorkerBinding binding;
    private final WorkOrderRepository workOrders;
    private final CodexWorkerProtocolClient protocol;
    private final CodexWorkerInputMaterializer inputs;
    private final CodexWorkerOutputPromoter outputs;
    private final CodexCredentialIsolationVerifier credentialIsolation;

    public CodexCodingWorker(
            CodexWorkerBinding binding,
            ArtifactStore artifacts,
            WorkOrderRepository workOrders) {
        this(binding, artifacts, workOrders, new UnprovenCodexCredentialIsolationVerifier());
    }

    public CodexCodingWorker(
            CodexWorkerBinding binding,
            ArtifactStore artifacts,
            WorkOrderRepository workOrders,
            CodexCredentialIsolationVerifier credentialIsolation) {
        this(binding, artifacts, workOrders, credentialIsolation, null);
    }

    public CodexCodingWorker(
            CodexWorkerBinding binding,
            ArtifactStore artifacts,
            WorkOrderRepository workOrders,
            CodexCredentialIsolationVerifier credentialIsolation,
            CandidateVersionRepository candidates) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.protocol = new CodexWorkerProtocolClient(binding);
        this.inputs = candidates == null
                ? new CodexWorkerInputMaterializer(artifacts, protocol.objectMapper())
                : new CodexWorkerInputMaterializer(artifacts, protocol.objectMapper(), candidates);
        this.outputs = new CodexWorkerOutputPromoter(artifacts, protocol.objectMapper());
        this.credentialIsolation = Objects.requireNonNull(credentialIsolation, "credentialIsolation");
    }

    CodexCodingWorker(
            CodexWorkerBinding binding,
            WorkOrderRepository workOrders,
            CodexWorkerProtocolClient protocol,
            CodexWorkerInputMaterializer inputs,
            CodexWorkerOutputPromoter outputs,
            CodexCredentialIsolationVerifier credentialIsolation) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
        this.credentialIsolation = Objects.requireNonNull(credentialIsolation, "credentialIsolation");
    }

    @Override
    public WorkerCapabilities capabilities() {
        try {
            requireCredentialIsolation();
            var advertised = protocol.capabilities().values().stream().map(value -> {
                WorkerCapability capability = CAPABILITY_CATALOG.get(value);
                if (capability == null) {
                    throw new CodexWorkerProtocolException("CODING_WORKER_CAPABILITY_INVALID", false);
                }
                return capability;
            }).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!advertised.equals(SUPPORTED)) {
                throw new CodexWorkerProtocolException("CODING_WORKER_CAPABILITY_MISMATCH", false);
            }
            return new WorkerCapabilities(advertised);
        } catch (CodexWorkerProtocolException exception) {
            throw dispatchException(exception);
        }
    }

    @Override
    public WorkerSubmission submit(WorkerDispatchRequest request) {
        Objects.requireNonNull(request, "request");
        WorkOrder order = request.workOrder();
        if (!SUPPORTED.containsAll(order.requiredCapabilities())) {
            throw new WorkerDispatchException(
                    "CODING_WORKER_CAPABILITY_MISMATCH",
                    WorkerEffectCertainty.NO_EFFECT,
                    WorkerRetryDisposition.AFTER_CORRECTION);
        }
        try {
            requireCredentialIsolation();
            Path configuredWorkspace = binding.resolveWorkspace(order.workspaceRef());
            var materialized = inputs.materialize(
                    order, request.operationId(), binding.stateRoot(), configuredWorkspace);
            CodexWorkerProtocolClient.OperationReply reply = protocol.submit(
                    submitRequest(
                            order, request.workerRunId(), request.operationId(), materialized.workspaceRoot()),
                    request.attemptToken());
            CodexWorkerProtocolClient.ProtocolSnapshot snapshot = reply.snapshot()
                    .orElseThrow(() -> new CodexWorkerProtocolException("CODING_WORKER_SUBMIT_NOT_FOUND", true));
            validateAuthenticatedSnapshot(
                    snapshot, order.tenantId(), order.workOrderId(), request.workerRunId(),
                    request.operationId(), request.attemptToken());
            WorkerRunStatus status = status(snapshot.status());
            return new WorkerSubmission(
                    request.workerRunId(), request.operationId(), status,
                    Optional.of(snapshot.externalSessionRef()));
        } catch (CodexWorkerProtocolException | IllegalArgumentException exception) {
            if (exception instanceof CodexWorkerProtocolException protocolException) {
                throw dispatchException(protocolException);
            }
            throw new WorkerDispatchException(
                    stableCode(exception.getMessage(), "CODING_WORKER_CONFIGURATION_INVALID"),
                    WorkerEffectCertainty.NO_EFFECT,
                    WorkerRetryDisposition.AFTER_CORRECTION);
        }
    }

    @Override
    public WorkerStatusObservation status(WorkerStatusRequest request) {
        Objects.requireNonNull(request, "request");
        WorkOrder order = workOrders.find(request.tenantId(), request.workOrderId()).orElse(null);
        if (order == null) {
            return WorkerStatusObservation.unknown("CODING_WORKER_WORK_ORDER_UNAVAILABLE");
        }
        try {
            requireCredentialIsolation();
            CodexWorkerProtocolClient.OperationReply reply = protocol.status(
                    ownerRequest("status", request.tenantId(), request.workOrderId(),
                            request.workerRunId(), request.operationId(), null),
                    request.attemptToken());
            if (reply.lookup() == CodexWorkerProtocolClient.Lookup.NOT_FOUND) {
                return WorkerStatusObservation.notFound();
            }
            CodexWorkerProtocolClient.ProtocolSnapshot raw = reply.snapshot().orElseThrow();
            validateAuthenticatedSnapshot(
                    raw, request.tenantId(), request.workOrderId(), request.workerRunId(),
                    request.operationId(), request.attemptToken());
            WorkerRunStatus status = status(raw.status());
            Optional<CodingWorkerCompletionPayload> terminalPayload = Optional.empty();
            Optional<ArtifactReference> resultReference = Optional.empty();
            if (status == WorkerRunStatus.SUCCEEDED || status == WorkerRunStatus.FAILED) {
                CodexWorkerProtocolClient.RawCompletion completion = raw.completion()
                        .orElseThrow(() -> new CodexWorkerProtocolException(
                                "CODING_WORKER_TERMINAL_ENVELOPE_INVALID", true));
                validateCompletion(completion, request);
                Optional<io.github.flowerjvm.factory.contracts.worker.CodingWorkerResultManifest> result = Optional.empty();
                if (status == WorkerRunStatus.SUCCEEDED) {
                    Path workspace = binding.resolveWorkspace(order.workspaceRef());
                    var materialized = inputs.materialize(order, request.operationId(), binding.stateRoot(), workspace);
                    var promoted = outputs.promote(order, request.workerRunId(), request.operationId(), completion, materialized);
                    result = Optional.of(promoted.manifest());
                    resultReference = Optional.of(promoted.manifestReference());
                }
                terminalPayload = Optional.of(new CodingWorkerCompletionPayload(
                        WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                        completion.eventId(),
                        request.workOrderId(),
                        request.workerRunId(),
                        request.operationId(),
                        completion.attemptProof(),
                        status,
                        result,
                        completion.stableCode()));
            }
            Optional<Instant> effectTerminalAt = raw.effectTerminalAt().map(Instant::parse);
            if (status.isTerminal() != effectTerminalAt.isPresent()) {
                throw new CodexWorkerProtocolException(
                        "CODING_WORKER_TERMINAL_TIME_INVALID", true);
            }
            WorkerRunSnapshot snapshot = new WorkerRunSnapshot(
                    request.tenantId(), request.workOrderId(), request.workerRunId(), request.operationId(),
                    status, Optional.of(Instant.parse(raw.effectAcceptedAt())),
                    effectTerminalAt,
                    Optional.of(raw.externalSessionRef()), resultReference,
                    Optional.of(raw.stableCode()));
            return terminalPayload.map(payload -> WorkerStatusObservation.terminal(snapshot, payload))
                    .orElseGet(() -> WorkerStatusObservation.found(snapshot));
        } catch (CodexWorkerProtocolException exception) {
            return exception.effectUncertain()
                    ? WorkerStatusObservation.unknown(exception.stableCode())
                    : WorkerStatusObservation.unavailable(exception.stableCode());
        } catch (RuntimeException exception) {
            return WorkerStatusObservation.unknown("CODING_WORKER_RECONCILIATION_FAILED");
        }
    }

    @Override
    public WorkerCancelResult cancel(WorkerCancelRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            CodexWorkerProtocolClient.OperationReply reply = protocol.cancel(
                    ownerRequest("cancel", request.tenantId(), request.workOrderId(), request.workerRunId(),
                            request.operationId(), request.reasonCode()),
                    request.attemptToken());
            if (reply.lookup() == CodexWorkerProtocolClient.Lookup.NOT_FOUND) {
                throw new WorkerOperationNotFoundException();
            }
            CodexWorkerProtocolClient.ProtocolSnapshot snapshot = reply.snapshot().orElseThrow();
            validateAuthenticatedSnapshot(
                    snapshot, request.tenantId(), request.workOrderId(), request.workerRunId(),
                    request.operationId(), request.attemptToken());
            return new WorkerCancelResult(
                    request.workerRunId(), status(snapshot.status()), snapshot.stableCode());
        } catch (WorkerOperationNotFoundException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return new WorkerCancelResult(
                    request.workerRunId(), WorkerRunStatus.MANUAL_REVIEW,
                    "CODING_WORKER_CANCEL_UNCONFIRMED");
        }
    }

    private ObjectNode submitRequest(
            WorkOrder order, WorkerRunId workerRunId, String operationId, Path workspace) {
        ObjectNode request = protocol.base("submit");
        request.put("schemaVersion", WorkerProtocol.DISPATCH_SCHEMA_VERSION);
        request.put("tenantId", order.tenantId().value());
        request.put("workOrderId", order.workOrderId().value());
        request.put("workerRunId", workerRunId.value());
        request.put("operationId", operationId);
        request.put("taskType", order.phase());
        request.put("purpose", order.purpose());
        request.put("workspaceRef", order.workspaceRef());
        request.put("workspaceRoot", workspace.toString());
        addPaths(request.putArray("allowedReadPaths"), order.allowedReadPaths());
        addPaths(request.putArray("allowedWritePaths"), order.allowedWritePaths());
        ArrayNode capabilities = request.putArray("requiredCapabilities");
        order.requiredCapabilities().stream()
                .map(WorkerCapability::value)
                .sorted()
                .forEach(capabilities::add);
        request.put("expectedOutputSchemaId", order.expectedOutputSchemaId());
        request.put("expectedOutputSchemaVersion", order.expectedOutputSchemaVersion());
        request.put("instructionArtifactRef", order.instructionArtifactRef().value());
        request.put("instructionSha256", order.instructionHash().sha256());
        request.put("inputManifestArtifactRef", order.inputArtifactManifestRef().value());
        request.put("inputManifestSha256", order.inputManifestHash().sha256());
        request.put("policySnapshotRef", order.policySnapshotRef().value());
        request.put("deadlineAt", order.deadlineAt().toString());
        return request;
    }

    private ObjectNode ownerRequest(
            String command,
            TenantId tenantId,
            WorkOrderId workOrderId,
            WorkerRunId workerRunId,
            String operationId,
            String reasonCode) {
        ObjectNode request = protocol.base(command);
        request.put("tenantId", tenantId.value());
        request.put("workOrderId", workOrderId.value());
        request.put("workerRunId", workerRunId.value());
        request.put("operationId", operationId);
        if (reasonCode != null) {
            request.put("reasonCode", reasonCode);
        }
        return request;
    }

    private static void addPaths(ArrayNode target, java.util.List<String> values) {
        values.stream().map(PortableRelativePath::require).forEach(target::add);
    }

    private static void validateIdentity(
            CodexWorkerProtocolClient.ProtocolSnapshot snapshot,
            TenantId tenantId,
            WorkOrderId workOrderId,
            WorkerRunId workerRunId,
            String operationId) {
        if (!snapshot.tenantId().equals(tenantId.value())
                || !snapshot.workOrderId().equals(workOrderId.value())
                || !snapshot.workerRunId().equals(workerRunId.value())
                || !snapshot.operationId().equals(operationId)) {
            throw new CodexWorkerProtocolException("CODING_WORKER_OPERATION_IDENTITY_MISMATCH", true);
        }
    }

    private static void validateAuthenticatedSnapshot(
            CodexWorkerProtocolClient.ProtocolSnapshot snapshot,
            TenantId tenantId,
            WorkOrderId workOrderId,
            WorkerRunId workerRunId,
            String operationId,
            WorkerAttemptToken attemptToken) {
        validateIdentity(snapshot, tenantId, workOrderId, workerRunId, operationId);
        String material = String.join(
                "\n",
                STATUS_SNAPSHOT_PROOF_VERSION,
                snapshot.tenantId(),
                snapshot.workOrderId(),
                snapshot.workerRunId(),
                snapshot.operationId(),
                snapshot.status(),
                snapshot.effectAcceptedAt(),
                snapshot.effectTerminalAt().orElse(""),
                snapshot.stableCode());
        String expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    attemptToken.reveal().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = java.util.HexFormat.of().formatHex(
                    mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException unavailable) {
            throw new CodexWorkerProtocolException("CODING_WORKER_STATUS_AUTH_UNAVAILABLE", true);
        }
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                snapshot.snapshotProof().getBytes(StandardCharsets.US_ASCII))) {
            throw new CodexWorkerProtocolException("CODING_WORKER_STATUS_AUTH_INVALID", true);
        }
    }

    private static void validateCompletion(
            CodexWorkerProtocolClient.RawCompletion completion, WorkerStatusRequest request) {
        if (!WorkerProtocol.COMPLETION_SCHEMA_VERSION.equals(completion.schemaVersion())
                || !completion.workOrderId().equals(request.workOrderId().value())
                || !completion.workerRunId().equals(request.workerRunId().value())
                || !completion.operationId().equals(request.operationId())
                || !("SUCCEEDED".equals(completion.status()) || "FAILED".equals(completion.status()))
                || !WorkerAttemptProofs.matches(
                        request.attemptToken().reveal(),
                        completion.eventId(),
                        completion.operationId(),
                        request.workerRunId(),
                        completion.attemptProof())) {
            throw new CodexWorkerProtocolException("CODING_WORKER_TERMINAL_ENVELOPE_INVALID", true);
        }
    }

    private static WorkerRunStatus status(String value) {
        try {
            return WorkerRunStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_STATUS_INVALID", true);
        }
    }

    private static WorkerDispatchException dispatchException(CodexWorkerProtocolException exception) {
        return new WorkerDispatchException(
                stableCode(exception.stableCode(), "CODING_WORKER_DISPATCH_FAILED"),
                exception.effectUncertain() ? WorkerEffectCertainty.UNCERTAIN : WorkerEffectCertainty.NO_EFFECT,
                exception.effectUncertain() ? WorkerRetryDisposition.MANUAL_REVIEW : WorkerRetryDisposition.AFTER_CORRECTION);
    }

    private void requireCredentialIsolation() {
        try {
            CodexCredentialIsolationVerifier.ProvenIsolation proof =
                    Objects.requireNonNull(credentialIsolation.verify(binding), "credential isolation proof");
            if (!binding.bindingId().equals(proof.bindingId())) {
                throw new CodexCredentialIsolationException();
            }
        } catch (RuntimeException unproven) {
            throw new CodexWorkerProtocolException(
                    CodexCredentialIsolationException.STABLE_CODE, false);
        }
    }

    private static String stableCode(String candidate, String fallback) {
        return candidate != null && candidate.matches("[A-Z][A-Z0-9_]{0,127}") ? candidate : fallback;
    }

    private static Map<String, WorkerCapability> catalog() {
        var values = new HashMap<String, WorkerCapability>();
        for (WorkerCapability capability : Set.of(
                WorkerCapabilityCatalog.REPOSITORY_READ,
                WorkerCapabilityCatalog.BOUNDED_PATCH_WRITE,
                WorkerCapabilityCatalog.FILE_CREATE,
                WorkerCapabilityCatalog.COMMAND_BUILD_TEST,
                WorkerCapabilityCatalog.STRUCTURED_OUTPUT,
                WorkerCapabilityCatalog.PROGRESS_EVENTS,
                WorkerCapabilityCatalog.COOPERATIVE_CANCEL,
                WorkerCapabilityCatalog.SESSION_RESUME,
                WorkerCapabilityCatalog.USAGE_REPORTING,
                WorkerCapabilityCatalog.SANDBOX_ENFORCEMENT)) {
            values.put(capability.value(), capability);
        }
        return Map.copyOf(values);
    }
}
