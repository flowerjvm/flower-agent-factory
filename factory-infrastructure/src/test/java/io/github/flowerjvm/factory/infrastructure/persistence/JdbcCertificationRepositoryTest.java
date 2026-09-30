package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.action.VerificationRunIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.verification.VerificationAttemptTokens;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntent;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.application.verification.VerificationReviewEvidenceOutput;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.AgentPackCompatibilityDescriptor;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationEvidenceManifest;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentManifest;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.infrastructure.certification.JacksonCertificationArtifactCodec;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcCertificationRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void roundTripsCanonicalInputWithTenantScopeAndOneVersionCasWinner() {
        Fixture fixture = Fixture.create("roundtrip");
        JdbcCertificationRepository repository = fixture.certifications();

        repository.create(fixture.requested());
        assertEquals(fixture.requested(), repository.find(fixture.tenant(), fixture.certificationId()).orElseThrow());
        assertTrue(repository.find(new TenantId("tenant-b"), fixture.certificationId()).isEmpty());

        Certification certified = fixture.certified();
        assertTrue(repository.compareAndSet(fixture.requested(), certified));
        assertFalse(repository.compareAndSet(fixture.requested(), certified));
        assertEquals(certified, repository.find(fixture.tenant(), fixture.certificationId()).orElseThrow());
    }

    @Test
    void databaseRejectsCandidateHashThatDoesNotMatchTheExactVerificationChain() throws Exception {
        Fixture fixture = Fixture.create("exact-fk");
        fixture.certifications().create(fixture.requested());

        try (var connection = fixture.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE factory_certification SET candidate_hash = ? WHERE certification_id = ?")) {
            statement.setString(1, "f".repeat(64));
            statement.setString(2, fixture.certificationId().value());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void strictReadFailsClosedWhenCanonicalInputJsonIsTampered() throws Exception {
        Fixture fixture = Fixture.create("canonical-tamper");
        fixture.certifications().create(fixture.requested());

        try (var connection = fixture.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE factory_certification SET input_lock_json = input_lock_json || ' '"
                                + " WHERE certification_id = ?")) {
            statement.setString(1, fixture.certificationId().value());
            assertEquals(1, statement.executeUpdate());
        }
        assertThrows(FactoryPersistenceException.class,
                () -> fixture.certifications().find(fixture.tenant(), fixture.certificationId()));
    }

    @Test
    void createRejectsTerminalSnapshotsAndActiveDuplicateSubjects() {
        Fixture fixture = Fixture.create("active-duplicate");
        fixture.certifications().create(fixture.requested());

        assertThrows(IllegalArgumentException.class,
                () -> fixture.certifications().create(fixture.certified()));

        CertificationId duplicateId = new CertificationId("certification-active-duplicate-2");
        CertificationArtifactLock duplicateInput = fixture.store(
                "artifact:input-lock:active-duplicate-2",
                JacksonCertificationArtifactCodec.MEDIA_TYPE,
                fixture.codec().writeInputLock(fixture.inputLock()));
        Certification duplicate = Certification.requested(duplicateId, fixture.inputLock(), duplicateInput, NOW);
        assertThrows(DuplicateLedgerRecordException.class,
                () -> fixture.certifications().create(duplicate));
    }

    @Test
    void createFailsClosedForNonCanonicalVerificationActionOwner() throws Exception {
        Fixture fixture = Fixture.create("wrong-verification-owner");
        try (var connection = fixture.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE action_run SET action_id = 'factory.untrusted.action' WHERE run_id = ?")) {
            statement.setString(1, fixture.inputLock().verificationActionRunId());
            assertEquals(1, statement.executeUpdate());
        }

        assertThrows(FactoryPersistenceException.class,
                () -> fixture.certifications().create(fixture.requested()));
    }

    @Test
    void createRejectsTrailingVerificationActionJson() throws Exception {
        Fixture fixture = Fixture.create("trailing-action-json");
        try (var connection = fixture.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE action_run SET input_json = input_json || '{}' WHERE run_id = ?")) {
            statement.setString(1, fixture.inputLock().verificationActionRunId());
            assertEquals(1, statement.executeUpdate());
        }

        assertThrows(FactoryPersistenceException.class,
                () -> fixture.certifications().create(fixture.requested()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "missing-schema", "wrong-hash", "unknown-field", "incomplete-dispatch"})
    void certificationTransactionUsesTheSameStrictVersionedVerificationOutputContract(String variant) throws Exception {
        // SQL-shaped fixture tests the transaction contract only, not a real recorded readback.
        Fixture fixture = Fixture.create("review-receipt-" + variant);
        var mapper = new ObjectMapper();
        var owner = new JdbcRunStore(fixture.dataSource(), mapper)
                .find(fixture.inputLock().verificationActionRunId()).orElseThrow();
        var output = new java.util.HashMap<>(owner.result().output());
        output.put(VerificationReviewEvidenceOutput.SCHEMA_KEY, VerificationReviewEvidenceOutput.SCHEMA_VERSION);
        output.put(VerificationReviewEvidenceOutput.REFERENCE_KEY, VerificationReviewEvidenceOutput.REFERENCE_PREFIX + "e".repeat(64));
        output.put(VerificationReviewEvidenceOutput.HASH_KEY, "e".repeat(64));
        switch (variant) {
            case "missing-schema" -> output.remove(VerificationReviewEvidenceOutput.SCHEMA_KEY);
            case "wrong-hash" -> output.put(VerificationReviewEvidenceOutput.HASH_KEY, "f".repeat(64));
            case "unknown-field" -> output.put("untrustedEvidence", true);
            default -> { }
        }
        try (var connection = fixture.dataSource().getConnection();
                var statement = connection.prepareStatement("UPDATE action_run SET result_output_json = ? WHERE run_id = ?")) {
            statement.setString(1, mapper.writeValueAsString(output));
            statement.setString(2, owner.runId());
            assertEquals(1, statement.executeUpdate());
        }
        try (var connection = fixture.dataSource().getConnection();
                var statement = connection.prepareStatement("UPDATE factory_verification_dispatch_intent SET last_code = ? WHERE action_run_id = ?")) {
            statement.setString(1, variant.equals("incomplete-dispatch") ? "NOT_CANONICAL"
                    : io.github.flowerjvm.factory.application.verification.VerificationDispatchRunner.COMPLETED);
            statement.setString(2, owner.runId());
            assertEquals(1, statement.executeUpdate());
        }
        if (variant.equals("valid")) {
            fixture.certifications().create(fixture.requested());
            assertEquals(fixture.requested(), fixture.certifications().find(fixture.tenant(), fixture.certificationId()).orElseThrow());
        } else {
            assertThrows(FactoryPersistenceException.class, () -> fixture.certifications().create(fixture.requested()));
        }
    }

    @Test
    void createRejectsGenerationManifestWhoseGateDoesNotMatchTheCertificationLock() {
        Fixture fixture = Fixture.create("generation-gate-mismatch", "strict");

        assertThrows(FactoryPersistenceException.class,
                () -> fixture.certifications().create(fixture.requested()));
    }

    @Test
    void revocationCasCannotReplaceIssuanceEvidence() {
        Fixture fixture = Fixture.create("revocation-evidence");
        fixture.certifications().create(fixture.requested());
        Certification certified = fixture.certified();
        assertTrue(fixture.certifications().compareAndSet(fixture.requested(), certified));
        Instant revokedAt = NOW.plusSeconds(10);
        Certification crafted = new Certification(
                certified.certificationId(), certified.inputLock(), certified.inputLockArtifact(),
                io.github.flowerjvm.factory.application.certification.CertificationStatus.REVOKED,
                Optional.of(certified.inputLockArtifact()),
                certified.certificationEvidence(), certified.actionRunId(),
                Optional.of("CERTIFICATION_REVOKED"), certified.issuedAt(), certified.expiresAt(),
                Optional.of(revokedAt), certified.version() + 1, certified.createdAt(), revokedAt);

        assertThrows(IllegalArgumentException.class,
                () -> fixture.certifications().compareAndSet(certified, crafted));
    }

    record Fixture(
            DataSource dataSource,
            TenantId tenant,
            CertificationId certificationId,
            CertificationInputLock inputLock,
            Certification requested,
            CertificationArtifactLock evidenceLock,
            CertificationArtifactLock manifestLock,
            JacksonCertificationArtifactCodec codec,
            JdbcArtifactStore artifacts,
            JdbcCertificationRepository certifications) {

        static Fixture create(String suffix) {
            return create(suffix, "internal");
        }

        static Fixture create(String suffix, String generationGateProfile) {
            DataSource dataSource = FactoryDatabaseMigrationsTest.h2("certification_" + suffix);
            return create(dataSource, suffix, generationGateProfile);
        }

        static Fixture create(DataSource dataSource, String suffix) {
            return create(dataSource, suffix, "internal");
        }

        static Fixture create(
                DataSource dataSource, String suffix, String generationGateProfile) {
            return create(
                    dataSource,
                    suffix,
                    generationGateProfile,
                    null,
                    null,
                    "sha256-ordinal-v1");
        }

        static Fixture createForCertifiedComponentConsumer(
                DataSource dataSource,
                String suffix,
                CertificationArtifactLock productContract,
                CertificationArtifactLock apiSignature,
                String sourceLockAlgorithmId) {
            return create(
                    dataSource,
                    suffix,
                    "internal",
                    productContract,
                    apiSignature,
                    sourceLockAlgorithmId);
        }

        private static Fixture create(
                DataSource dataSource,
                String suffix,
                String generationGateProfile,
                CertificationArtifactLock productContractOverride,
                CertificationArtifactLock apiSignatureOverride,
                String sourceLockAlgorithmId) {
            return create(dataSource, suffix, generationGateProfile, productContractOverride,
                    apiSignatureOverride, sourceLockAlgorithmId, "internal", null);
        }

        /** Synthetic verification fixture for catalog wiring, not native acceptance evidence. */
        static Fixture createForVersionedComponentConsumer(
                DataSource dataSource,
                String suffix,
                String gateProfile,
                CertificationArtifactLock productContract,
                CertificationArtifactLock apiSignature,
                CertificationArtifactLock requirementMatrix) {
            return create(dataSource, suffix, gateProfile, productContract, apiSignature,
                    CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID, gateProfile, requirementMatrix);
        }

        private static Fixture create(
                DataSource dataSource,
                String suffix,
                String generationGateProfile,
                CertificationArtifactLock productContractOverride,
                CertificationArtifactLock apiSignatureOverride,
                String sourceLockAlgorithmId,
                String verificationGateProfile,
                CertificationArtifactLock requirementMatrixOverride) {
            FactoryDatabaseMigrations.migrate(dataSource);
            TenantId tenant = PersistenceFixtures.TENANT;
            var codec = new JacksonCertificationArtifactCodec();
            var artifacts = new JdbcArtifactStore(
                    dataSource, Clock.fixed(NOW, ZoneOffset.UTC));

            var session = PersistenceFixtures.buildSession("cert-" + suffix);
            new JdbcBuildSessionRepository(dataSource).create(session);

            CertificationArtifactLock source = store(artifacts, tenant, "source:" + suffix,
                    "source".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
            CertificationArtifactLock dependency = store(artifacts, tenant, "dependency:" + suffix,
                    "dependency".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
            CertificationArtifactLock toolchain = store(artifacts, tenant, "toolchain:" + suffix,
                    "toolchain".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
            CertificationArtifactLock productContract = productContractOverride == null
                    ? store(artifacts, tenant, "product-contract:" + suffix,
                            "product-contract".getBytes(StandardCharsets.UTF_8), "application/octet-stream")
                    : requireStored(artifacts, tenant, productContractOverride);
            CertificationArtifactLock apiSignature = apiSignatureOverride == null
                    ? store(artifacts, tenant, "api-signature:" + suffix,
                            "api-signature".getBytes(StandardCharsets.UTF_8), "application/octet-stream")
                    : requireStored(artifacts, tenant, apiSignatureOverride);
            CertificationArtifactLock policy = store(artifacts, tenant, "policy:" + suffix,
                    "policy".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
            CertificationArtifactLock verificationResult = store(artifacts, tenant, "verification-result:" + suffix,
                    "verification-result".getBytes(StandardCharsets.UTF_8), "application/json");
            ContentHash fixtureHash = sha256(("fixtures:" + suffix).getBytes(StandardCharsets.UTF_8));
            if (requirementMatrixOverride != null) {
                requireStored(artifacts, tenant, requirementMatrixOverride);
                store(artifacts, tenant, "skill:agent-pack-generator:1.0.0",
                        "skill".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
            }

            WorkOrderId generationWorkOrderId = new WorkOrderId("work-cert-" + suffix);
            CertificationArtifactLock generationInput = store(
                    artifacts,
                    tenant,
                    "generation-input:" + suffix,
                    generationInputBytes(
                            generationWorkOrderId,
                            session.buildSessionId(),
                            dependency,
                            toolchain,
                            apiSignature,
                            productContract,
                            generationGateProfile,
                            sourceLockAlgorithmId,
                            requirementMatrixOverride),
                    "application/json");

            WorkOrder workOrder = workOrder(session, suffix, generationInput, policy);
            new JdbcWorkOrderRepository(dataSource).create(workOrder);
            CandidateId candidateId = new CandidateId("candidate-cert-" + suffix);
            CandidateVersion candidate = new CandidateVersion(
                    candidateId,
                    tenant,
                    session.buildSessionId(),
                    Optional.empty(),
                    source.reference(),
                    source.hash(),
                    dependency.reference(),
                    dependency.hash(),
                    toolchain.reference(),
                    toolchain.hash(),
                    CandidateVersionStatus.GENERATED,
                    workOrder.workOrderId(),
                    NOW.plusSeconds(1));
            new JdbcCandidateVersionRepository(dataSource).create(candidate);

            VerificationRunId verificationId = new VerificationRunId("verification-cert-" + suffix);
            VerificationRun requestedVerification = new VerificationRun(
                    verificationId,
                    tenant,
                    session.buildSessionId(),
                    candidateId,
                    source.hash(),
                    verificationGateProfile,
                    toolchain.hash(),
                    fixtureHash,
                    VerificationRunStatus.REQUESTED,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(),
                    0, NOW.plusSeconds(2), NOW.plusSeconds(2));
            VerificationRun running = requestedVerification.start(NOW.plusSeconds(3));
            VerificationRun passed = running.complete(
                    VerificationRunStatus.PASSED,
                    verificationResult.reference(),
                    verificationResult.hash(),
                    "VERIFICATION_PASSED",
                    VerificationDisposition.REVIEW_ELIGIBLE,
                    NOW.plusSeconds(4));
            var verifications = new JdbcVerificationRunRepository(dataSource);
            verifications.create(requestedVerification);
            if (!verifications.compareAndSet(requestedVerification, running)
                    || !verifications.compareAndSet(running, passed)) {
                throw new AssertionError("could not create terminal verification fixture");
            }

            String verificationActionRunId = "verification-action-" + suffix;
            String certificationActionRunId = "certification-action-" + suffix;
            createVerificationActionOwner(
                    dataSource, tenant, verificationActionRunId, candidate, passed);
            createActionRun(dataSource, tenant, certificationActionRunId);

            AgentPackCompatibilityDescriptor compatibility = new AgentPackCompatibilityDescriptor(
                    AgentPackCompatibilityDescriptor.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    candidateId,
                    source.hash(),
                    productContract,
                    apiSignature,
                    dependency,
                    toolchain,
                    verificationGateProfile,
                    sourceLockAlgorithmId,
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            CertificationArtifactLock compatibilityLock = store(
                    artifacts, tenant, "compatibility:" + suffix,
                    codec.writeCompatibilityDescriptor(compatibility),
                    JacksonCertificationArtifactCodec.MEDIA_TYPE);

            CertificationInputLock input = new CertificationInputLock(
                    CertificationInputLock.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    session.buildSessionId(),
                    workOrder.workOrderId(),
                    candidateId,
                    source.hash(),
                    source,
                    dependency,
                    toolchain,
                    generationInput,
                    productContract,
                    apiSignature,
                    sourceLockAlgorithmId,
                    verificationGateProfile,
                    verificationId,
                    verificationActionRunId,
                    verificationResult,
                    fixtureHash,
                    policy,
                    compatibilityLock,
                    "internal",
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            CertificationArtifactLock inputLock = store(
                    artifacts, tenant, "input-lock:" + suffix,
                    codec.writeInputLock(input), JacksonCertificationArtifactCodec.MEDIA_TYPE);
            CertificationId certificationId = new CertificationId("certification-" + suffix);
            Certification requested = Certification.requested(certificationId, input, inputLock, NOW.plusSeconds(5));

            CertificationEvidenceManifest evidence = new CertificationEvidenceManifest(
                    CertificationEvidenceManifest.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    certificationId,
                    candidateId,
                    source.hash(),
                    inputLock.hash(),
                    verificationId,
                    verificationActionRunId,
                    verificationResult.hash(),
                    compatibilityLock.hash(),
                    "internal",
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            CertificationArtifactLock evidenceLock = store(
                    artifacts, tenant, "evidence:" + suffix,
                    codec.writeEvidence(evidence), JacksonCertificationArtifactCodec.MEDIA_TYPE);
            Instant issuedAt = NOW.plusSeconds(5);
            CertifiedAgentComponentManifest manifest = new CertifiedAgentComponentManifest(
                    CertifiedAgentComponentManifest.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    certificationId,
                    candidateId,
                    source.hash(),
                    source,
                    inputLock,
                    verificationId,
                    verificationResult,
                    compatibilityLock,
                    evidenceLock,
                    "internal",
                    "0.2.0",
                    issuedAt,
                    issuedAt.plusSeconds(3600),
                    CertifiedAgentComponentManifest.CERTIFIED_STATUS);
            CertificationArtifactLock manifestLock = store(
                    artifacts, tenant, "manifest:" + suffix,
                    codec.writeComponentManifest(manifest), JacksonCertificationArtifactCodec.MEDIA_TYPE);

            return new Fixture(
                    dataSource,
                    tenant,
                    certificationId,
                    input,
                    requested,
                    evidenceLock,
                    manifestLock,
                    codec,
                    artifacts,
                    new JdbcCertificationRepository(dataSource, codec));
        }

        Certification certified() {
            return requested.certify(
                    manifestLock,
                    evidenceLock,
                    "certification-action-" + certificationId.value().substring("certification-".length()),
                    NOW.plusSeconds(6),
                    Optional.of(NOW.plusSeconds(3606)));
        }

        CertificationArtifactLock store(String reference, String mediaType, byte[] content) {
            return store(artifacts, tenant, reference, content, mediaType);
        }

        private static WorkOrder workOrder(
                io.github.flowerjvm.factory.application.build.BuildSession session,
                String suffix,
                CertificationArtifactLock generationInput,
                CertificationArtifactLock policy) {
            return new WorkOrder(
                    new WorkOrderId("work-cert-" + suffix),
                    session.tenantId(),
                    session.buildSessionId(),
                    "generate-candidate",
                    "Generate exact certification candidate",
                    1,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("base-revision"),
                    new ArtifactReference("artifact:instruction:" + suffix),
                    sha256(("instruction:" + suffix).getBytes(StandardCharsets.UTF_8)),
                    generationInput.reference(),
                    generationInput.hash(),
                    "workspace:" + suffix,
                    List.of("/workspace/input"),
                    List.of("/workspace/output"),
                    Set.of(),
                    "agent-pack-candidate",
                    "1",
                    policy.reference(),
                    NOW.plusSeconds(1800),
                    1,
                    "logical-cert-" + suffix,
                    WorkOrderCreatorType.SYSTEM,
                    "factory",
                    NOW);
        }

        private static CertificationArtifactLock store(
                JdbcArtifactStore artifacts,
                TenantId tenant,
                String suffix,
                byte[] content,
                String mediaType) {
            ArtifactReference reference = new ArtifactReference("artifact:" + suffix);
            ContentHash hash = sha256(content);
            artifacts.store(new Artifact(tenant, reference, hash, mediaType, content));
            return new CertificationArtifactLock(reference, hash);
        }

        private static CertificationArtifactLock requireStored(
                JdbcArtifactStore artifacts,
                TenantId tenant,
                CertificationArtifactLock expected) {
            Artifact stored = artifacts.find(tenant, expected.reference())
                    .orElseThrow(() -> new AssertionError("compatible fixture artifact is missing"));
            if (!stored.contentHash().equals(expected.hash())) {
                throw new AssertionError("compatible fixture artifact hash differs");
            }
            return expected;
        }

        private static void createActionRun(DataSource dataSource, TenantId tenant, String runId) {
            ActionProposal proposal = ActionProposal.builder("factory.test.certification")
                    .proposalId("proposal-" + runId)
                    .requestChannel(ActionRequestChannel.INTERNAL)
                    .proposerType(ActionProposerType.SERVICE)
                    .requesterId("factory-service")
                    .input(Map.of("run", runId))
                    .idempotencyKey("key-" + runId)
                    .build();
            ExecutionContext context = new ExecutionContext(
                    tenant.value(), "factory-service", runId, "trace-" + runId, Map.of());
            new JdbcRunStore(dataSource, new ObjectMapper()).create(ActionRun.requested(proposal, context));
        }

        private static void createVerificationActionOwner(
                DataSource dataSource,
                TenantId tenant,
                String runId,
                CandidateVersion candidate,
                VerificationRun verification) {
            long expectedVersion = verification.version() - 2;
            String operationId = "operation-" + runId;
            String attemptToken = "attempt-" + runId;
            VerificationRunInput input = new VerificationRunInput(
                    verification.verificationRunId(), candidate.candidateId(), expectedVersion);
            ActionProposal proposal = ActionProposal.builder(VerificationRunAction.ACTION_ID)
                    .proposalId("proposal-" + runId)
                    .requestChannel(ActionRequestChannel.INTERNAL)
                    .proposerType(ActionProposerType.SERVICE)
                    .requesterId("factory-service")
                    .input(input.toMap())
                    .idempotencyKey(VerificationRunIdempotencyKeys.derive(
                            verification, candidate, expectedVersion))
                    .build();
            ExecutionContext context = new ExecutionContext(
                    tenant.value(),
                    "factory-service",
                    runId,
                    "trace-" + runId,
                    Map.of(
                            "resource.type", VerificationRunAction.RESOURCE_TYPE,
                            "resource.id", candidate.candidateId().value()));
            ActionRun requested = ActionRun.requested(proposal, context);
            ActionRun succeeded = requested.toBuilder()
                    .version(requested.version() + 1)
                    .status(ActionRunStatus.SUCCEEDED)
                    .currentStage("TERMINAL")
                    .attemptToken(attemptToken)
                    .externalOperationId(operationId)
                    .result(ActionExecutionResult.succeeded(Map.of(
                            VerificationRunAction.VERIFICATION_RUN_ID,
                            verification.verificationRunId().value(),
                            "verificationStatus", verification.status().name(),
                            "terminalCode", verification.terminalCode().orElseThrow(),
                            "resultManifestRef", verification.resultManifestRef().orElseThrow().value(),
                            "executedNow", true)))
                    .updatedAt(requested.updatedAt().plusMillis(1))
                    .build();
            new JdbcRunStore(dataSource, new ObjectMapper()).create(succeeded);

            VerificationDispatchIntent pending = VerificationDispatchIntent.pending(
                    operationId,
                    tenant,
                    verification.verificationRunId(),
                    candidate.candidateId(),
                    expectedVersion,
                    runId,
                    VerificationAttemptTokens.hash(attemptToken),
                    NOW.plusSeconds(60),
                    NOW);
            var intents = new JdbcVerificationDispatchIntentRepository(dataSource);
            intents.create(pending);
            VerificationDispatchIntent running = pending.claim(
                    "claim-" + runId, NOW.plusSeconds(1), Duration.ofSeconds(10));
            if (!intents.compareAndSet(pending, running)) {
                throw new AssertionError("could not claim verification intent fixture");
            }
            VerificationDispatchIntent completed = running.complete(
                    "claim-" + runId, "VERIFICATION_COMPLETED", NOW.plusSeconds(2));
            if (!intents.compareAndSet(running, completed)) {
                throw new AssertionError("could not complete verification intent fixture");
            }
        }

        private static byte[] generationInputBytes(
                WorkOrderId workOrderId,
                io.github.flowerjvm.factory.contracts.ids.BuildSessionId buildSessionId,
                CertificationArtifactLock dependency,
                CertificationArtifactLock toolchain,
                CertificationArtifactLock apiSignature,
                CertificationArtifactLock productContract,
                String gateProfile,
                String sourceLockAlgorithmId,
                CertificationArtifactLock requirementMatrix) {
            Map<String, Object> json = new java.util.LinkedHashMap<>();
            json.put("schemaVersion", "factory.coding-worker-input-manifest.v1");
            json.put("workOrderId", workOrderId.value());
            json.put("buildSessionId", buildSessionId.value());
            json.put("skillId", "agent-pack-generator");
            json.put("skillVersion", "1.0.0");
            json.put("skillArtifactRef", "artifact:skill:agent-pack-generator:1.0.0");
            json.put("skillHash", sha256("skill".getBytes(StandardCharsets.UTF_8)).sha256());
            json.put("dependencyLockRef", dependency.reference().value());
            json.put("dependencyLockHash", dependency.hash().sha256());
            json.put("toolchainLockRef", toolchain.reference().value());
            json.put("toolchainLockHash", toolchain.hash().sha256());
            json.put("apiSignatureIndexRef", apiSignature.reference().value());
            json.put("apiSignatureIndexHash", apiSignature.hash().sha256());
            json.put("productContractBundleRef", productContract.reference().value());
            json.put("productContractBundleHash", productContract.hash().sha256());
            json.put("gateProfile", gateProfile);
            json.put("requirementTestMatrixRef", requirementMatrix == null
                    ? "artifact:requirement-matrix:001" : requirementMatrix.reference().value());
            json.put("requirementTestMatrixHash",
                    requirementMatrix == null
                            ? sha256("requirement-matrix".getBytes(StandardCharsets.UTF_8)).sha256()
                            : requirementMatrix.hash().sha256());
            json.put("sourceLockAlgorithmId", sourceLockAlgorithmId);
            json.put("repairLock", null);
            try {
                return new ObjectMapper().writeValueAsBytes(json);
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
