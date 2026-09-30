package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.CertificationIssueAction;
import io.github.flowerjvm.factory.application.action.CertificationIssueIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationArtifactCodec;
import io.github.flowerjvm.factory.application.certification.CertificationAttemptTokens;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchIntent;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchOperationIds;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchTransaction;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.infrastructure.certification.JacksonCertificationArtifactCodec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Atomically validates the exact Certification Action owner and creates its PENDING intent. */
public final class JdbcCertificationDispatchTransaction implements CertificationDispatchTransaction {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private static final String LOCK_CERTIFICATION = """
            SELECT product_line_id, artifact_type, build_session_id, candidate_id, candidate_hash,
                   input_lock_json, input_lock_manifest_ref, input_lock_manifest_hash,
                   status, version, created_at
            FROM factory_certification
            WHERE tenant_id = ? AND certification_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_SESSION = """
            SELECT product_line_id, status, current_phase, current_candidate_id,
                   current_candidate_hash, cancellation_requested_at, deadline_at
            FROM factory_build_session
            WHERE tenant_id = ? AND build_session_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_ACTION_RUN = """
            SELECT tenant_id, action_id, status, attempt_token, input_json,
                   context_metadata_json, duplicate_key
            FROM action_run
            WHERE run_id = ?
            FOR UPDATE
            """;

    private final DataSource dataSource;
    private final JdbcCertificationDispatchIntentRepository intents;
    private final CertificationArtifactCodec certificationCodec;
    private final ObjectMapper json;

    public JdbcCertificationDispatchTransaction(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcCertificationDispatchTransaction(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.intents = new JdbcCertificationDispatchIntentRepository(dataSource);
        this.certificationCodec = new JacksonCertificationArtifactCodec();
        this.json = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public CertificationDispatchIntent prepare(
            TenantId tenantId,
            CertificationIssueInput input,
            String actionRunId,
            String attemptTokenHash,
            Instant preparedAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(input, "input");
        requireText(actionRunId, "actionRunId");
        requireHash(attemptTokenHash);
        Objects.requireNonNull(preparedAt, "preparedAt");
        return withConnection(dataSource, "prepare Certification dispatch", connection ->
                inTransaction(connection, tenantId, input, actionRunId, attemptTokenHash, preparedAt));
    }

    @Override
    public Optional<CertificationDispatchIntent> findExact(
            TenantId tenantId,
            CertificationIssueInput input,
            String actionRunId,
            String attemptTokenHash) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(input, "input");
        requireText(actionRunId, "actionRunId");
        requireHash(attemptTokenHash);
        return intents.find(CertificationDispatchOperationIds.derive(tenantId, input))
                .filter(intent -> exact(intent, tenantId, input, actionRunId, attemptTokenHash, null));
    }

    private CertificationDispatchIntent inTransaction(
            Connection connection,
            TenantId tenantId,
            CertificationIssueInput input,
            String actionRunId,
            String attemptTokenHash,
            Instant preparedAt) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            LockedCertification certification = lockCertification(connection, tenantId, input);
            LockedSession session = lockSession(connection, tenantId, certification);
            LockedActionRun actionRun = lockActionRun(
                    connection,
                    tenantId,
                    input,
                    actionRunId,
                    attemptTokenHash,
                    certification.requestedIdentity());
            validateLaunchable(certification, session, actionRun, input, preparedAt);
            String operationId = CertificationDispatchOperationIds.derive(tenantId, input);
            Optional<CertificationDispatchIntent> existing = intents.find(connection, operationId);
            if (existing.isPresent()) {
                CertificationDispatchIntent canonical = existing.orElseThrow();
                if (!exact(canonical, tenantId, input, actionRunId, attemptTokenHash, session.deadlineAt())) {
                    throw new IllegalStateException(
                            "deterministic Certification operation has a different exact owner");
                }
                connection.commit();
                return canonical;
            }

            CertificationDispatchIntent pending = CertificationDispatchIntent.pending(
                    operationId,
                    tenantId,
                    input.certificationId(),
                    input.inputLockManifestHash(),
                    input.expectedCertificationVersion(),
                    actionRunId,
                    attemptTokenHash,
                    session.deadlineAt(),
                    preparedAt);
            intents.create(connection, pending);
            CertificationDispatchIntent canonical = intents.find(connection, operationId).orElseThrow();
            connection.commit();
            return canonical;
        } catch (SQLException | RuntimeException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private LockedCertification lockCertification(
            Connection connection, TenantId tenantId, CertificationIssueInput input) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_CERTIFICATION)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, input.certificationId().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Certification is not visible in tenant scope");
                }
                CertificationInputLock lock;
                try {
                    lock = certificationCodec.readInputLock(
                            resultSet.getString("input_lock_json").getBytes(StandardCharsets.UTF_8));
                } catch (RuntimeException invalidLock) {
                    throw new FactoryPersistenceException(
                            "Certification input lock JSON is not canonical", invalidLock);
                }
                CertificationArtifactLock inputArtifact = new CertificationArtifactLock(
                        new ArtifactReference(resultSet.getString("input_lock_manifest_ref")),
                        new ContentHash(resultSet.getString("input_lock_manifest_hash")));
                if (!ProductLineId.AGENT_PACK.value().equals(resultSet.getString("product_line_id"))
                        || !CertifiedArtifactType.AGENT_PACK.name().equals(resultSet.getString("artifact_type"))
                        || !lock.tenantId().equals(tenantId)
                        || !lock.productLineId().equals(ProductLineId.AGENT_PACK)
                        || lock.artifactType() != CertifiedArtifactType.AGENT_PACK
                        || !lock.buildSessionId().value().equals(resultSet.getString("build_session_id"))
                        || !lock.candidateId().value().equals(resultSet.getString("candidate_id"))
                        || !lock.candidateHash().sha256().equals(resultSet.getString("candidate_hash"))
                        || !input.inputLockManifestHash().equals(inputArtifact.hash())) {
                    throw new IllegalStateException(
                            "Certification is not bound to the exact Agent Pack input lock");
                }
                return new LockedCertification(
                        input.certificationId(),
                        lock,
                        inputArtifact,
                        resultSet.getString("status"),
                        resultSet.getLong("version"),
                        getInstant(resultSet, "created_at"));
            }
        }
    }

    private static LockedSession lockSession(
            Connection connection, TenantId tenantId, LockedCertification certification)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SESSION)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, certification.inputLock().buildSessionId().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("BuildSession is not visible in tenant scope");
                }
                return new LockedSession(
                        resultSet.getString("product_line_id"),
                        resultSet.getString("status"),
                        resultSet.getString("current_phase"),
                        resultSet.getString("current_candidate_id"),
                        resultSet.getString("current_candidate_hash"),
                        resultSet.getTimestamp("cancellation_requested_at") != null,
                        getInstant(resultSet, "deadline_at"));
            }
        }
    }

    private LockedActionRun lockActionRun(
            Connection connection,
            TenantId tenantId,
            CertificationIssueInput expectedInput,
            String actionRunId,
            String attemptTokenHash,
            Certification requestedIdentity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACTION_RUN)) {
            statement.setString(1, actionRunId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException(
                            "ActionRun is not visible for Certification dispatch");
                }
                Map<String, Object> storedInput = readMap(
                        resultSet.getString("input_json"), "Certification Action input");
                Map<String, Object> metadata = readMap(
                        resultSet.getString("context_metadata_json"),
                        "Certification Action context");
                String storedAttemptToken = resultSet.getString("attempt_token");
                String storedAttemptHash;
                try {
                    storedAttemptHash = CertificationAttemptTokens.hash(storedAttemptToken);
                } catch (RuntimeException invalidAttempt) {
                    throw new IllegalStateException(
                            "ActionRun is missing the exact Certification attempt", invalidAttempt);
                }
                CertificationIssueInput parsedInput;
                try {
                    parsedInput = CertificationIssueInput.from(storedInput);
                } catch (RuntimeException invalidInput) {
                    throw new IllegalStateException(
                            "ActionRun Certification input is not strict v1", invalidInput);
                }
                String expectedDuplicateKey = CertificationIssueIdempotencyKeys.derive(
                        requestedIdentity, expectedInput.expectedCertificationVersion());
                if (!tenantId.value().equals(resultSet.getString("tenant_id"))
                        || !CertificationIssueAction.ACTION_ID.equals(resultSet.getString("action_id"))
                        || !expectedInput.equals(parsedInput)
                        || !CertificationIssueAction.RESOURCE_TYPE.equals(metadata.get("resource.type"))
                        || !expectedInput.certificationId().value().equals(metadata.get("resource.id"))
                        || !expectedDuplicateKey.equals(resultSet.getString("duplicate_key"))
                        || !attemptTokenHash.equals(storedAttemptHash)) {
                    throw new IllegalStateException(
                            "ActionRun is not the exact Certification issuance owner");
                }
                return new LockedActionRun(resultSet.getString("status"));
            }
        }
    }

    private static void validateLaunchable(
            LockedCertification certification,
            LockedSession session,
            LockedActionRun actionRun,
            CertificationIssueInput input,
            Instant preparedAt) {
        if (!"REQUESTED".equals(certification.status())
                || certification.version() != input.expectedCertificationVersion()) {
            throw new IllegalStateException("Certification is not the exact REQUESTED version");
        }
        if (!ProductLineId.AGENT_PACK.value().equals(session.productLineId())
                || !"CERTIFYING".equals(session.status())
                || !"certify".equals(session.currentPhase())
                || !certification.inputLock().candidateId().value().equals(session.currentCandidateId())
                || !certification.inputLock().candidateHash().sha256().equals(session.currentCandidateHash())
                || session.cancelled()
                || !preparedAt.isBefore(session.deadlineAt())) {
            throw new IllegalStateException(
                    "BuildSession is not launchable for Agent Pack Certification");
        }
        if (!"RUNNING".equals(actionRun.status())) {
            throw new IllegalStateException("ActionRun is not RUNNING at Certification dispatch");
        }
    }

    private Map<String, Object> readMap(String value, String subject) {
        try {
            return json.readValue(value, JSON_MAP);
        } catch (Exception invalid) {
            throw new FactoryPersistenceException(subject + " is not strict JSON", invalid);
        }
    }

    private static boolean exact(
            CertificationDispatchIntent intent,
            TenantId tenantId,
            CertificationIssueInput input,
            String actionRunId,
            String attemptTokenHash,
            Instant deadlineAt) {
        return intent.tenantId().equals(tenantId)
                && intent.certificationId().equals(input.certificationId())
                && intent.inputLockManifestHash().equals(input.inputLockManifestHash())
                && intent.expectedCertificationVersion() == input.expectedCertificationVersion()
                && intent.actionRunId().equals(actionRunId)
                && intent.attemptTokenHash().equals(attemptTokenHash)
                && (deadlineAt == null || intent.deadlineAt().equals(deadlineAt));
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("attemptTokenHash must be SHA-256 hex");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record LockedCertification(
            CertificationId certificationId,
            CertificationInputLock inputLock,
            CertificationArtifactLock inputLockArtifact,
            String status,
            long version,
            Instant createdAt) {
        Certification requestedIdentity() {
            return Certification.requested(
                    certificationId, inputLock, inputLockArtifact, createdAt);
        }
    }

    private record LockedSession(
            String productLineId,
            String status,
            String currentPhase,
            String currentCandidateId,
            String currentCandidateHash,
            boolean cancelled,
            Instant deadlineAt) {}

    private record LockedActionRun(String status) {}
}
