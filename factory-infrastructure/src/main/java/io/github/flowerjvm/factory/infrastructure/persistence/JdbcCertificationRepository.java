package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.requireCas;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.requireSame;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.action.VerificationRunIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicyCatalog;
import io.github.flowerjvm.factory.application.certification.CertificationArtifactCodec;
import io.github.flowerjvm.factory.application.certification.CertificationRepository;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.verification.VerificationAttemptTokens;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchRunner;
import io.github.flowerjvm.factory.application.verification.VerificationReviewEvidenceOutput;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.infrastructure.certification.JacksonCertificationArtifactCodec;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC version-CAS ledger for immutable PR6-A certification input locks. */
public final class JdbcCertificationRepository implements CertificationRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private static final String INSERT = """
            INSERT INTO factory_certification (
                certification_id, tenant_id, product_line_id, artifact_type,
                build_session_id, generation_work_order_id, candidate_id, candidate_hash,
                source_manifest_ref, source_manifest_hash,
                dependency_lock_ref, dependency_lock_hash,
                toolchain_lock_ref, toolchain_lock_hash,
                generation_input_manifest_ref, generation_input_manifest_hash,
                product_contract_bundle_ref, product_contract_bundle_hash,
                api_signature_index_ref, api_signature_index_hash,
                source_lock_algorithm_id, gate_profile,
                verification_run_id, verification_action_run_id,
                verification_result_manifest_ref, verification_result_manifest_hash,
                verification_fixture_set_hash, policy_snapshot_ref, policy_snapshot_hash,
                compatibility_descriptor_ref, compatibility_descriptor_hash,
                certification_profile, factory_version, flower_version, action_runtime_version,
                input_lock_json, input_lock_manifest_ref, input_lock_manifest_hash,
                status, active_key,
                certification_manifest_ref, certification_manifest_hash,
                certification_evidence_ref, certification_evidence_hash,
                action_run_id, stable_code, issued_at, expires_at, revoked_at,
                version, created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND = """
            SELECT * FROM factory_certification
            WHERE tenant_id = ? AND certification_id = ?
            """;

    private static final String FIND_LATEST_FOR_CANDIDATE = """
            SELECT * FROM factory_certification
            WHERE tenant_id = ? AND build_session_id = ?
              AND candidate_id = ? AND candidate_hash = ?
            ORDER BY created_at DESC, certification_id DESC
            FETCH FIRST 1 ROW ONLY
            """;

    private static final String UPDATE = """
            UPDATE factory_certification
            SET status = ?, active_key = ?,
                certification_manifest_ref = ?, certification_manifest_hash = ?,
                certification_evidence_ref = ?, certification_evidence_hash = ?,
                action_run_id = ?, stable_code = ?, issued_at = ?, expires_at = ?, revoked_at = ?,
                version = ?, updated_at = ?
            WHERE tenant_id = ? AND certification_id = ? AND version = ? AND status = ?
            """;

    private final DataSource dataSource;
    private final CertificationArtifactCodec codec;
    private final ObjectMapper json;
    private final WorkerProtocolArtifactDecoder workerDecoder;

    public JdbcCertificationRepository(DataSource dataSource) {
        this(dataSource, new JacksonCertificationArtifactCodec(),
                new JacksonWorkerProtocolArtifactDecoder(new ObjectMapper()));
    }

    public JdbcCertificationRepository(DataSource dataSource, CertificationArtifactCodec codec) {
        this(dataSource, codec, new JacksonWorkerProtocolArtifactDecoder(new ObjectMapper()));
    }

    public JdbcCertificationRepository(
            DataSource dataSource,
            CertificationArtifactCodec codec,
            WorkerProtocolArtifactDecoder workerDecoder) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.workerDecoder = Objects.requireNonNull(workerDecoder, "workerDecoder");
        this.json = new ObjectMapper()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public void create(Certification certification) {
        withConnection(dataSource, "create Certification", connection -> {
            create(connection, certification);
            return null;
        });
    }

    void create(Connection connection, Certification certification) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(certification, "certification");
        if (certification.status() != CertificationStatus.REQUESTED || certification.version() != 0) {
            throw new IllegalArgumentException("Certification create accepts only version-zero REQUESTED state");
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            byte[] canonicalInput = codec.writeInputLock(certification.inputLock());
            validateTrustedInputs(connection, certification, canonicalInput);
            bindInsert(statement, certification, canonicalInput);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<Certification> find(TenantId tenantId, CertificationId certificationId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(certificationId, "certificationId");
        return withConnection(dataSource, "find Certification", connection ->
                find(connection, tenantId, certificationId, false));
    }

    Optional<Certification> find(
            Connection connection,
            TenantId tenantId,
            CertificationId certificationId,
            boolean forUpdate) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(certificationId, "certificationId");
        String sql = forUpdate ? FIND + " FOR UPDATE" : FIND;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, certificationId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<Certification> findLatestForCandidate(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            CandidateId candidateId,
            ContentHash candidateHash) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(candidateHash, "candidateHash");
        return withConnection(dataSource, "find latest candidate Certification", connection ->
                findLatestForCandidate(
                        connection, tenantId, buildSessionId, candidateId, candidateHash, false));
    }

    Optional<Certification> findLatestForCandidate(
            Connection connection,
            TenantId tenantId,
            BuildSessionId buildSessionId,
            CandidateId candidateId,
            ContentHash candidateHash,
            boolean forUpdate) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(candidateHash, "candidateHash");
        String sql = forUpdate ? FIND_LATEST_FOR_CANDIDATE + " FOR UPDATE" : FIND_LATEST_FOR_CANDIDATE;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, buildSessionId.value());
            statement.setString(3, candidateId.value());
            statement.setString(4, candidateHash.sha256());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public boolean compareAndSet(Certification expected, Certification next) {
        validateCas(expected, next);
        return withConnection(dataSource, "CAS Certification", connection ->
                compareAndSet(connection, expected, next));
    }

    boolean compareAndSet(
            Connection connection, Certification expected, Certification next) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        validateCas(expected, next);
        try (PreparedStatement statement = connection.prepareStatement(UPDATE)) {
            bindUpdate(statement, expected, next);
            return statement.executeUpdate() == 1;
        }
    }

    private static void validateCas(Certification expected, Certification next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        requireCas(expected.version(), next.version());
        requireSame(expected.certificationId().equals(next.certificationId()), "certificationId");
        requireSame(expected.inputLock().equals(next.inputLock()), "inputLock");
        requireSame(expected.inputLockArtifact().equals(next.inputLockArtifact()), "inputLockArtifact");
        requireSame(expected.createdAt().equals(next.createdAt()), "createdAt");
        requireTransition(expected.status(), next.status());
        if (expected.status() == CertificationStatus.CERTIFIED && next.status() == CertificationStatus.REVOKED) {
            requireSame(expected.certificationManifest().equals(next.certificationManifest()),
                    "certificationManifest");
            requireSame(expected.certificationEvidence().equals(next.certificationEvidence()),
                    "certificationEvidence");
            requireSame(expected.actionRunId().equals(next.actionRunId()), "actionRunId");
            requireSame(expected.issuedAt().equals(next.issuedAt()), "issuedAt");
            requireSame(expected.expiresAt().equals(next.expiresAt()), "expiresAt");
            if (next.revokedAt().orElseThrow().isBefore(next.issuedAt().orElseThrow())) {
                throw new IllegalArgumentException("revokedAt must not be before issuedAt");
            }
        }
    }

    private void validateTrustedInputs(
            Connection connection, Certification certification, byte[] canonicalInput) throws SQLException {
        try {
            CertificationInputLock lock = certification.inputLock();
            validateInputArtifact(connection, lock, certification.inputLockArtifact(), canonicalInput);
            validateWorkOrder(connection, lock);
            validateGenerationInput(connection, lock);
            CandidateVersion candidate = validateCandidate(connection, lock);
            VerificationRun verification = validateVerification(connection, lock);
            validateVerificationOwner(connection, lock, candidate, verification);
        } catch (FactoryPersistenceException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw corrupt("Certification trusted input validation failed", invalid);
        }
    }

    private static void validateInputArtifact(
            Connection connection,
            CertificationInputLock input,
            CertificationArtifactLock artifactLock,
            byte[] canonicalInput) throws SQLException {
        String sql = """
                SELECT content_hash, media_type, content_size, content_base64
                FROM factory_artifact
                WHERE tenant_id = ? AND artifact_ref = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, input.tenantId().value());
            statement.setString(2, artifactLock.reference().value());
            try (ResultSet row = statement.executeQuery()) {
                requireTrusted(row.next(), "input lock artifact is missing from the trusted tenant");
                byte[] stored;
                try {
                    stored = Base64.getDecoder().decode(row.getString("content_base64"));
                } catch (RuntimeException invalid) {
                    throw corrupt("input lock artifact content is not valid base64", invalid);
                }
                requireTrusted(row.getString("content_hash").equals(artifactLock.hash().sha256()),
                        "input lock artifact hash differs");
                requireTrusted(CertificationArtifactCodec.MEDIA_TYPE.equals(row.getString("media_type")),
                        "input lock artifact media type differs");
                requireTrusted(row.getLong("content_size") == stored.length,
                        "input lock artifact size differs");
                requireTrusted(java.util.Arrays.equals(stored, canonicalInput),
                        "input lock artifact bytes differ from canonical input JSON");
                requireTrusted(sha256(canonicalInput).equals(artifactLock.hash()),
                        "canonical input JSON differs from its exact hash");
            }
        }
    }

    private static String validateWorkOrder(Connection connection, CertificationInputLock lock)
            throws SQLException {
        String sql = """
                SELECT tenant_id, build_session_id, work_order_id, phase,
                       input_artifact_manifest_ref, input_manifest_hash, policy_snapshot_ref
                FROM factory_work_order
                WHERE work_order_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, lock.generationWorkOrderId().value());
            try (ResultSet row = statement.executeQuery()) {
                requireTrusted(row.next(), "generation WorkOrder is missing");
                requireTrusted(row.getString("tenant_id").equals(lock.tenantId().value()),
                        "generation WorkOrder tenant differs");
                requireTrusted(row.getString("build_session_id").equals(lock.buildSessionId().value()),
                        "generation WorkOrder BuildSession differs");
                requireTrusted(row.getString("work_order_id").equals(lock.generationWorkOrderId().value()),
                        "generation WorkOrder identity differs");
                requireTrusted(row.getString("input_artifact_manifest_ref")
                                .equals(lock.generationInputManifest().reference().value())
                                && row.getString("input_manifest_hash")
                                        .equals(lock.generationInputManifest().hash().sha256()),
                        "generation WorkOrder input manifest differs");
                requireTrusted(row.getString("policy_snapshot_ref")
                                .equals(lock.policySnapshot().reference().value()),
                        "generation WorkOrder policy snapshot differs");
                return row.getString("phase");
            }
        }
    }

    private CodingWorkerInputManifest validateGenerationInput(Connection connection, CertificationInputLock lock)
            throws SQLException {
        byte[] bytes = readExactJsonArtifact(connection, lock.tenantId(), lock.generationInputManifest(),
                "generation input manifest");
        CodingWorkerInputManifest input;
        try {
            input = workerDecoder.decodeInputManifest(bytes);
        } catch (RuntimeException invalid) {
            throw corrupt("generation input manifest failed strict decoding", invalid);
        }
        requireTrusted(input.workOrderId().equals(lock.generationWorkOrderId())
                        && input.buildSessionId().equals(lock.buildSessionId()),
                "generation input owner differs");
        requireTrusted(input.dependencyLockRef().equals(lock.dependencyLock().reference())
                        && input.dependencyLockHash().equals(lock.dependencyLock().hash()),
                "generation input dependency lock differs");
        requireTrusted(input.toolchainLockRef().equals(lock.toolchainLock().reference())
                        && input.toolchainLockHash().equals(lock.toolchainLock().hash()),
                "generation input toolchain lock differs");
        requireTrusted(input.apiSignatureIndexRef().equals(lock.apiSignatureIndex().reference())
                        && input.apiSignatureIndexHash().equals(lock.apiSignatureIndex().hash()),
                "generation input API signature lock differs");
        requireTrusted(input.productContractBundleRef().equals(lock.productContractBundle().reference())
                        && input.productContractBundleHash().equals(lock.productContractBundle().hash()),
                "generation input product contract differs");
        requireTrusted(input.gateProfile().equals(lock.gateProfile()),
                "generation input gate profile differs");
        requireTrusted(input.sourceLockAlgorithmId().equals(lock.sourceLockAlgorithmId()),
                "generation input source-lock algorithm differs");
        return input;
    }

    void validatePolicyProvenance(
            Connection connection, CertificationInputLock lock, AgentPackCertificationPolicyCatalog policies)
            throws SQLException {
        requireTrusted(BuildSessionPhase.GENERATE_CANDIDATE.id().equals(validateWorkOrder(connection, lock)),
                "certification provenance is not a candidate-generation WorkOrder");
        CodingWorkerInputManifest input = validateGenerationInput(connection, lock);
        requireTrusted(policies.matchesGeneration(lock, input),
                "generation inputs are not admitted by the exact certification policy");
        for (CertificationArtifactLock required : policies.requiredGenerationArtifacts(input)) {
            readExactJsonArtifact(connection, lock.tenantId(), required, "required certification profile artifact");
        }
    }

    private static byte[] readExactJsonArtifact(
            Connection connection,
            TenantId tenantId,
            CertificationArtifactLock lock,
            String subject) throws SQLException {
        String sql = """
                SELECT content_hash, media_type, content_size, content_base64
                FROM factory_artifact
                WHERE tenant_id = ? AND artifact_ref = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, lock.reference().value());
            try (ResultSet row = statement.executeQuery()) {
                requireTrusted(row.next(), subject + " is missing");
                byte[] content;
                try {
                    content = Base64.getDecoder().decode(row.getString("content_base64"));
                } catch (RuntimeException invalid) {
                    throw corrupt(subject + " is not valid base64", invalid);
                }
                requireTrusted(row.getString("content_hash").equals(lock.hash().sha256())
                                && sha256(content).equals(lock.hash()),
                        subject + " hash differs");
                requireTrusted(CertificationArtifactCodec.MEDIA_TYPE.equals(row.getString("media_type")),
                        subject + " media type differs");
                requireTrusted(row.getLong("content_size") == content.length,
                        subject + " size differs");
                return content;
            }
        }
    }

    private static CandidateVersion validateCandidate(Connection connection, CertificationInputLock lock)
            throws SQLException {
        String sql = "SELECT * FROM factory_candidate_version WHERE candidate_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, lock.candidateId().value());
            try (ResultSet row = statement.executeQuery()) {
                requireTrusted(row.next(), "candidate is missing");
                CandidateVersion candidate = new CandidateVersion(
                        new CandidateId(row.getString("candidate_id")),
                        new TenantId(row.getString("tenant_id")),
                        new BuildSessionId(row.getString("build_session_id")),
                        getOptionalText(row, "parent_candidate_id").map(CandidateId::new),
                        new ArtifactReference(row.getString("source_manifest_ref")),
                        new ContentHash(row.getString("source_hash")),
                        new ArtifactReference(row.getString("dependency_lock_ref")),
                        new ContentHash(row.getString("dependency_lock_hash")),
                        new ArtifactReference(row.getString("toolchain_lock_ref")),
                        new ContentHash(row.getString("toolchain_lock_hash")),
                        CandidateVersionStatus.valueOf(row.getString("status")),
                        new WorkOrderId(row.getString("created_by_work_order_id")),
                        getInstant(row, "created_at"));
                requireTrusted(candidate.tenantId().equals(lock.tenantId()), "candidate tenant differs");
                requireTrusted(candidate.buildSessionId().equals(lock.buildSessionId()),
                        "candidate BuildSession differs");
                requireTrusted(candidate.candidateId().equals(lock.candidateId()), "candidate identity differs");
                requireTrusted(candidate.createdByWorkOrderId().equals(lock.generationWorkOrderId()),
                        "candidate generation WorkOrder differs");
                requireTrusted(candidate.sourceManifestRef().equals(lock.sourceManifest().reference())
                                && candidate.sourceHash().equals(lock.candidateHash()),
                        "candidate source lock differs");
                requireTrusted(candidate.dependencyLockRef().equals(lock.dependencyLock().reference())
                                && candidate.dependencyLockHash().equals(lock.dependencyLock().hash()),
                        "candidate dependency lock differs");
                requireTrusted(candidate.toolchainLockRef().equals(lock.toolchainLock().reference())
                                && candidate.toolchainLockHash().equals(lock.toolchainLock().hash()),
                        "candidate toolchain lock differs");
                return candidate;
            }
        }
    }

    private static VerificationRun validateVerification(
            Connection connection, CertificationInputLock lock) throws SQLException {
        String sql = "SELECT * FROM factory_verification_run WHERE verification_run_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, lock.verificationRunId().value());
            try (ResultSet row = statement.executeQuery()) {
                requireTrusted(row.next(), "verification run is missing");
                VerificationRun verification = new VerificationRun(
                        new VerificationRunId(row.getString("verification_run_id")),
                        new TenantId(row.getString("tenant_id")),
                        new BuildSessionId(row.getString("build_session_id")),
                        new CandidateId(row.getString("candidate_id")),
                        new ContentHash(row.getString("candidate_hash")),
                        row.getString("gate_profile"),
                        new ContentHash(row.getString("toolchain_lock_hash")),
                        new ContentHash(row.getString("fixture_set_hash")),
                        VerificationRunStatus.valueOf(row.getString("status")),
                        getOptionalText(row, "result_manifest_ref").map(ArtifactReference::new),
                        getOptionalText(row, "result_manifest_hash").map(ContentHash::new),
                        getOptionalText(row, "terminal_code"),
                        getOptionalText(row, "disposition").map(VerificationDisposition::valueOf),
                        getOptionalInstant(row, "started_at"),
                        getOptionalInstant(row, "completed_at"),
                        row.getLong("version"),
                        getInstant(row, "created_at"),
                        getInstant(row, "updated_at"));
                requireTrusted(verification.tenantId().equals(lock.tenantId()), "verification tenant differs");
                requireTrusted(verification.buildSessionId().equals(lock.buildSessionId()),
                        "verification BuildSession differs");
                requireTrusted(verification.verificationRunId().equals(lock.verificationRunId()),
                        "verification identity differs");
                requireTrusted(verification.candidateId().equals(lock.candidateId())
                                && verification.candidateHash().equals(lock.candidateHash()),
                        "verification candidate lock differs");
                requireTrusted(verification.gateProfile().equals(lock.gateProfile()),
                        "verification gate profile differs");
                requireTrusted(verification.toolchainLockHash().equals(lock.toolchainLock().hash()),
                        "verification toolchain lock differs");
                requireTrusted(verification.fixtureSetHash().equals(lock.verificationFixtureSetHash()),
                        "verification fixture lock differs");
                requireTrusted(verification.resultManifestRef()
                                .equals(Optional.of(lock.verificationResultManifest().reference()))
                                && verification.resultManifestHash()
                                        .equals(Optional.of(lock.verificationResultManifest().hash())),
                        "verification result lock differs");
                requireTrusted(verification.status() == VerificationRunStatus.PASSED
                                && verification.disposition()
                                        .filter(value -> value == VerificationDisposition.REVIEW_ELIGIBLE)
                                        .isPresent()
                                && !VerificationRun.LEGACY_RESULT_MANIFEST_HASH
                                        .equals(lock.verificationResultManifest().hash()),
                        "verification is not current PASSED review evidence");
                return verification;
            }
        }
    }

    private void validateVerificationOwner(
            Connection connection,
            CertificationInputLock lock,
            CandidateVersion candidate,
            VerificationRun verification) throws SQLException {
        String sql = """
                SELECT
                    i.operation_id AS intent_operation_id,
                    i.tenant_id AS intent_tenant_id,
                    i.verification_run_id AS intent_verification_run_id,
                    i.candidate_id AS intent_candidate_id,
                    i.expected_verification_run_version AS intent_expected_version,
                    i.action_run_id AS intent_action_run_id,
                    i.attempt_token_hash AS intent_attempt_token_hash,
                    i.status AS intent_status,
                    i.last_code AS intent_last_code,
                    a.tenant_id AS action_tenant_id,
                    a.action_id AS action_id,
                    a.input_json AS action_input_json,
                    a.context_metadata_json AS action_context_json,
                    a.duplicate_key AS action_duplicate_key,
                    a.status AS action_status,
                    a.attempt_token AS action_attempt_token,
                    a.external_operation_id AS action_external_operation_id,
                    a.result_status AS action_result_status,
                    a.result_output_json AS action_result_output_json
                FROM factory_verification_dispatch_intent i
                JOIN action_run a ON a.run_id = i.action_run_id
                WHERE i.tenant_id = ? AND i.verification_run_id = ? AND i.action_run_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, lock.tenantId().value());
            statement.setString(2, lock.verificationRunId().value());
            statement.setString(3, lock.verificationActionRunId());
            try (ResultSet row = statement.executeQuery()) {
                requireTrusted(row.next(), "verification Action owner is missing");
                long expectedVersion = row.getLong("intent_expected_version");
                requireTrusted(row.getString("intent_tenant_id").equals(lock.tenantId().value())
                                && row.getString("intent_verification_run_id")
                                        .equals(lock.verificationRunId().value())
                                && row.getString("intent_candidate_id").equals(lock.candidateId().value())
                                && row.getString("intent_action_run_id").equals(lock.verificationActionRunId())
                                && "COMPLETED".equals(row.getString("intent_status"))
                                && verification.version() == expectedVersion + 2,
                        "verification intent does not canonically own the terminal run");
                requireTrusted(row.getString("action_tenant_id").equals(lock.tenantId().value())
                                && VerificationRunAction.ACTION_ID.equals(row.getString("action_id"))
                                && "SUCCEEDED".equals(row.getString("action_status"))
                                && "SUCCEEDED".equals(row.getString("action_result_status"))
                                && row.getString("intent_operation_id")
                                        .equals(row.getString("action_external_operation_id")),
                        "verification ActionRun is not the canonical succeeded owner");

                VerificationRunInput input = VerificationRunInput.from(
                        readMap(row.getString("action_input_json"), "verification Action input"));
                requireTrusted(input.verificationRunId().equals(lock.verificationRunId())
                                && input.candidateId().equals(lock.candidateId())
                                && input.expectedVerificationRunVersion() == expectedVersion,
                        "verification Action input differs");
                Map<String, Object> context = readMap(
                        row.getString("action_context_json"), "verification Action context");
                requireTrusted(VerificationRunAction.RESOURCE_TYPE.equals(context.get("resource.type"))
                                && lock.candidateId().value().equals(context.get("resource.id")),
                        "verification Action resource context differs");
                requireTrusted(row.getString("intent_attempt_token_hash")
                                .equals(VerificationAttemptTokens.hash(row.getString("action_attempt_token"))),
                        "verification Action attempt token differs");
                requireTrusted(row.getString("action_duplicate_key")
                                .equals(VerificationRunIdempotencyKeys.derive(
                                        verification, candidate, expectedVersion)),
                        "verification Action duplicate key differs");

                Map<String, Object> output = readMap(
                        row.getString("action_result_output_json"), "verification Action result");
                requireTrusted(VerificationDispatchRunner.exactTerminalResult(
                                verification, ActionExecutionResult.succeeded(output)),
                        "verification Action result differs from terminal evidence");
                requireTrusted(VerificationReviewEvidenceOutput.lock(output).isEmpty()
                                || VerificationDispatchRunner.COMPLETED.equals(row.getString("intent_last_code")),
                        "verification review evidence has no completed dispatch receipt");
            }
        }
    }

    private Map<String, Object> readMap(String value, String subject) {
        try {
            return json.readValue(value, JSON_MAP);
        } catch (Exception invalid) {
            throw corrupt(subject + " is not valid JSON", invalid);
        }
    }

    private void bindInsert(PreparedStatement statement, Certification certification, byte[] canonicalInput)
            throws SQLException {
        CertificationInputLock lock = certification.inputLock();
        int index = 1;
        statement.setString(index++, certification.certificationId().value());
        statement.setString(index++, lock.tenantId().value());
        statement.setString(index++, lock.productLineId().value());
        statement.setString(index++, lock.artifactType().name());
        statement.setString(index++, lock.buildSessionId().value());
        statement.setString(index++, lock.generationWorkOrderId().value());
        statement.setString(index++, lock.candidateId().value());
        statement.setString(index++, lock.candidateHash().sha256());
        index = bindLock(statement, index, lock.sourceManifest());
        index = bindLock(statement, index, lock.dependencyLock());
        index = bindLock(statement, index, lock.toolchainLock());
        index = bindLock(statement, index, lock.generationInputManifest());
        index = bindLock(statement, index, lock.productContractBundle());
        index = bindLock(statement, index, lock.apiSignatureIndex());
        statement.setString(index++, lock.sourceLockAlgorithmId());
        statement.setString(index++, lock.gateProfile());
        statement.setString(index++, lock.verificationRunId().value());
        statement.setString(index++, lock.verificationActionRunId());
        index = bindLock(statement, index, lock.verificationResultManifest());
        statement.setString(index++, lock.verificationFixtureSetHash().sha256());
        index = bindLock(statement, index, lock.policySnapshot());
        index = bindLock(statement, index, lock.compatibilityDescriptor());
        statement.setString(index++, lock.certificationProfile());
        statement.setString(index++, lock.factoryVersion());
        statement.setString(index++, lock.flowerVersion());
        statement.setString(index++, lock.actionRuntimeVersion());
        statement.setString(index++, new String(canonicalInput, StandardCharsets.UTF_8));
        index = bindLock(statement, index, certification.inputLockArtifact());
        statement.setString(index++, certification.status().name());
        statement.setString(index++, activeKey(lock));
        index = bindOptionalLock(statement, index, certification.certificationManifest());
        index = bindOptionalLock(statement, index, certification.certificationEvidence());
        setOptionalText(statement, index++, certification.actionRunId());
        setOptionalText(statement, index++, certification.stableCode());
        setOptionalInstant(statement, index++, certification.issuedAt());
        setOptionalInstant(statement, index++, certification.expiresAt());
        setOptionalInstant(statement, index++, certification.revokedAt());
        statement.setLong(index++, certification.version());
        setInstant(statement, index++, certification.createdAt());
        setInstant(statement, index, certification.updatedAt());
    }

    private static void bindUpdate(
            PreparedStatement statement, Certification expected, Certification next) throws SQLException {
        int index = 1;
        statement.setString(index++, next.status().name());
        if (next.status() == CertificationStatus.REQUESTED) {
            statement.setString(index++, activeKey(next.inputLock()));
        } else {
            statement.setNull(index++, Types.VARCHAR);
        }
        index = bindOptionalLock(statement, index, next.certificationManifest());
        index = bindOptionalLock(statement, index, next.certificationEvidence());
        setOptionalText(statement, index++, next.actionRunId());
        setOptionalText(statement, index++, next.stableCode());
        setOptionalInstant(statement, index++, next.issuedAt());
        setOptionalInstant(statement, index++, next.expiresAt());
        setOptionalInstant(statement, index++, next.revokedAt());
        statement.setLong(index++, next.version());
        setInstant(statement, index++, next.updatedAt());
        statement.setString(index++, next.inputLock().tenantId().value());
        statement.setString(index++, next.certificationId().value());
        statement.setLong(index++, expected.version());
        statement.setString(index, expected.status().name());
    }

    private Certification map(ResultSet resultSet) throws SQLException {
        try {
            CertificationInputLock lock = codec.readInputLock(
                    resultSet.getString("input_lock_json").getBytes(StandardCharsets.UTF_8));
            requireMirrors(resultSet, lock);
            return new Certification(
                    new CertificationId(resultSet.getString("certification_id")),
                    lock,
                    lock(resultSet, "input_lock_manifest_ref", "input_lock_manifest_hash"),
                    CertificationStatus.valueOf(resultSet.getString("status")),
                    optionalLock(resultSet, "certification_manifest_ref", "certification_manifest_hash"),
                    optionalLock(resultSet, "certification_evidence_ref", "certification_evidence_hash"),
                    getOptionalText(resultSet, "action_run_id"),
                    getOptionalText(resultSet, "stable_code"),
                    getOptionalInstant(resultSet, "issued_at"),
                    getOptionalInstant(resultSet, "expires_at"),
                    getOptionalInstant(resultSet, "revoked_at"),
                    resultSet.getLong("version"),
                    getInstant(resultSet, "created_at"),
                    getInstant(resultSet, "updated_at"));
        } catch (FactoryPersistenceException exception) {
            throw exception;
        } catch (RuntimeException invalid) {
            throw corrupt("Certification row failed strict canonical reconstruction", invalid);
        }
    }

    private static void requireMirrors(ResultSet row, CertificationInputLock lock) throws SQLException {
        requireMirror(row.getString("tenant_id").equals(lock.tenantId().value()), "tenant_id");
        requireMirror(row.getString("product_line_id").equals(lock.productLineId().value()), "product_line_id");
        requireMirror(row.getString("artifact_type").equals(lock.artifactType().name()), "artifact_type");
        requireMirror(row.getString("build_session_id").equals(lock.buildSessionId().value()), "build_session_id");
        requireMirror(row.getString("generation_work_order_id").equals(lock.generationWorkOrderId().value()),
                "generation_work_order_id");
        requireMirror(row.getString("candidate_id").equals(lock.candidateId().value()), "candidate_id");
        requireMirror(row.getString("candidate_hash").equals(lock.candidateHash().sha256()), "candidate_hash");
        requireLockMirror(row, "source_manifest", lock.sourceManifest());
        requireLockMirror(row, "dependency_lock", lock.dependencyLock());
        requireLockMirror(row, "toolchain_lock", lock.toolchainLock());
        requireLockMirror(row, "generation_input_manifest", lock.generationInputManifest());
        requireLockMirror(row, "product_contract_bundle", lock.productContractBundle());
        requireLockMirror(row, "api_signature_index", lock.apiSignatureIndex());
        requireMirror(row.getString("source_lock_algorithm_id").equals(lock.sourceLockAlgorithmId()),
                "source_lock_algorithm_id");
        requireMirror(row.getString("gate_profile").equals(lock.gateProfile()), "gate_profile");
        requireMirror(row.getString("verification_run_id").equals(lock.verificationRunId().value()),
                "verification_run_id");
        requireMirror(row.getString("verification_action_run_id").equals(lock.verificationActionRunId()),
                "verification_action_run_id");
        requireLockMirror(row, "verification_result_manifest", lock.verificationResultManifest());
        requireMirror(row.getString("verification_fixture_set_hash")
                .equals(lock.verificationFixtureSetHash().sha256()), "verification_fixture_set_hash");
        requireLockMirror(row, "policy_snapshot", lock.policySnapshot());
        requireLockMirror(row, "compatibility_descriptor", lock.compatibilityDescriptor());
        requireMirror(row.getString("certification_profile").equals(lock.certificationProfile()),
                "certification_profile");
        requireMirror(row.getString("factory_version").equals(lock.factoryVersion()), "factory_version");
        requireMirror(row.getString("flower_version").equals(lock.flowerVersion()), "flower_version");
        requireMirror(row.getString("action_runtime_version").equals(lock.actionRuntimeVersion()),
                "action_runtime_version");
    }

    private static int bindLock(
            PreparedStatement statement, int index, CertificationArtifactLock lock) throws SQLException {
        statement.setString(index++, lock.reference().value());
        statement.setString(index++, lock.hash().sha256());
        return index;
    }

    private static int bindOptionalLock(
            PreparedStatement statement, int index, Optional<CertificationArtifactLock> lock) throws SQLException {
        if (lock.isPresent()) {
            return bindLock(statement, index, lock.orElseThrow());
        }
        statement.setNull(index++, Types.VARCHAR);
        statement.setNull(index++, Types.CHAR);
        return index;
    }

    private static CertificationArtifactLock lock(ResultSet row, String refColumn, String hashColumn)
            throws SQLException {
        return new CertificationArtifactLock(
                new ArtifactReference(row.getString(refColumn)), new ContentHash(row.getString(hashColumn)));
    }

    private static Optional<CertificationArtifactLock> optionalLock(
            ResultSet row, String refColumn, String hashColumn) throws SQLException {
        String reference = row.getString(refColumn);
        String hash = row.getString(hashColumn);
        if (reference == null && hash == null) {
            return Optional.empty();
        }
        if (reference == null || hash == null) {
            throw corrupt("Certification row contains a partial artifact lock", new IllegalStateException());
        }
        return Optional.of(new CertificationArtifactLock(
                new ArtifactReference(reference), new ContentHash(hash)));
    }

    private static void requireLockMirror(
            ResultSet row, String prefix, CertificationArtifactLock lock) throws SQLException {
        requireMirror(row.getString(prefix + "_ref").equals(lock.reference().value()), prefix + "_ref");
        requireMirror(row.getString(prefix + "_hash").equals(lock.hash().sha256()), prefix + "_hash");
    }

    private static void requireMirror(boolean matches, String field) {
        if (!matches) {
            throw corrupt("Certification canonical input differs from mirrored " + field,
                    new IllegalStateException(field));
        }
    }

    private static void requireTrusted(boolean condition, String message) {
        if (!condition) {
            throw corrupt("Certification trusted input validation failed: " + message,
                    new IllegalStateException(message));
        }
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static void requireTransition(CertificationStatus expected, CertificationStatus next) {
        boolean allowed = (expected == CertificationStatus.REQUESTED
                        && (next == CertificationStatus.CERTIFIED
                                || next == CertificationStatus.NOT_CERTIFIED))
                || (expected == CertificationStatus.CERTIFIED && next == CertificationStatus.REVOKED);
        if (!allowed) {
            throw new IllegalArgumentException("unsupported Certification CAS transition");
        }
    }

    private static String activeKey(CertificationInputLock lock) {
        return segment(lock.candidateId().value())
                + segment(lock.candidateHash().sha256())
                + segment(lock.certificationProfile());
    }

    private static String segment(String value) {
        return value.length() + ":" + value;
    }

    private static FactoryPersistenceException corrupt(String message, Throwable cause) {
        return new FactoryPersistenceException(message, cause);
    }
}
