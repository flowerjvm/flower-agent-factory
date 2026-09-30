package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseAction;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ActionBackedReferenceAssemblyReleaseLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAttemptTokens;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntent;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchOperationIds;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchTransaction;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Atomically binds the exact governed Action owner and creates its durable release intent.
 *
 * <p>The lock order is deliberately fixed as ReferenceAssembly, BuildSession, DecisionPoint,
 * component Certification, then ActionRun. Release completion uses the same prefix so cancellation,
 * revocation, and dispatch cannot observe a bind-without-intent residue.
 */
public final class JdbcReferenceAssemblyReleaseDispatchTransaction
        implements ReferenceAssemblyReleaseDispatchTransaction {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> JSON_STRINGS = new TypeReference<>() {};
    private static final String RELEASE_SUBJECT_REFERENCE_PREFIX =
            "factory-reference-assembly/release-subject/sha256/";
    private static final String RELEASE_DECISION_ID_PREFIX =
            "reference-assembly-release-review-";
    private static final Set<String> CANONICAL_ACTION_CONTEXT_KEYS = Set.of(
            "actor.permissions", "resource.type", "resource.id");

    private static final String LOCK_ASSEMBLY = """
            SELECT * FROM factory_reference_assembly
            WHERE tenant_id = ? AND reference_assembly_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_SESSION = """
            SELECT product_line_id, status, current_phase,
                   requirements_artifact_ref, requirements_hash,
                   current_certification_id, cancellation_requested_at,
                   deadline_at, updated_at
            FROM factory_build_session
            WHERE tenant_id = ? AND build_session_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_DECISION_POINT = """
            SELECT * FROM factory_decision_point
            WHERE tenant_id = ? AND decision_point_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_COMPONENT_CERTIFICATION = """
            SELECT product_line_id, artifact_type, candidate_hash,
                   certification_manifest_ref, certification_manifest_hash,
                   status, expires_at, updated_at
            FROM factory_certification
            WHERE tenant_id = ? AND certification_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_ACTION_RUN = """
            SELECT tenant_id, user_id, trace_id, action_id, requester_id,
                   request_channel, proposer_type, status, attempt_token,
                   input_json, context_metadata_json, duplicate_key
            FROM action_run
            WHERE run_id = ?
            FOR UPDATE
            """;
    private static final String BIND_ACTION = """
            UPDATE factory_reference_assembly
            SET release_action_run_id = ?, version = ?, updated_at = ?
            WHERE tenant_id = ? AND reference_assembly_id = ?
              AND status = 'INSPECTED' AND version = ?
              AND release_action_run_id IS NULL
            """;

    private final DataSource dataSource;
    private final JdbcReferenceAssemblyReleaseDispatchIntentRepository intents;
    private final ObjectMapper json;

    public JdbcReferenceAssemblyReleaseDispatchTransaction(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcReferenceAssemblyReleaseDispatchTransaction(
            DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.intents = new JdbcReferenceAssemblyReleaseDispatchIntentRepository(dataSource);
        this.json = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public ReferenceAssemblyReleaseDispatchIntent prepare(
            TenantId tenantId,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            String attemptTokenHash,
            Instant preparedAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(input, "input");
        requireText(actionRunId, "actionRunId", 64);
        requireHash(attemptTokenHash);
        requireCanonicalInstant(preparedAt, "preparedAt");
        return withConnection(dataSource, "prepare Reference Assembly release dispatch", connection ->
                inTransaction(
                        connection,
                        tenantId,
                        input,
                        actionRunId,
                        attemptTokenHash,
                        preparedAt));
    }

    @Override
    public Optional<ReferenceAssemblyReleaseDispatchIntent> findExact(
            TenantId tenantId,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            String attemptTokenHash) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(input, "input");
        requireText(actionRunId, "actionRunId", 64);
        requireHash(attemptTokenHash);
        return intents.find(ReferenceAssemblyReleaseDispatchOperationIds.derive(tenantId, input))
                .filter(intent -> exact(
                        intent,
                        tenantId,
                        input,
                        actionRunId,
                        attemptTokenHash,
                        null));
    }

    private ReferenceAssemblyReleaseDispatchIntent inTransaction(
            Connection connection,
            TenantId tenantId,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            String attemptTokenHash,
            Instant preparedAt) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            ReferenceAssembly assembly = lockAssembly(connection, tenantId, input);
            PreparationMode mode = requireAssembly(
                    assembly, tenantId, input, actionRunId, preparedAt);
            LockedSession session = lockSession(connection, tenantId, assembly);
            DecisionPoint decisionPoint = lockDecisionPoint(connection, tenantId, input);
            LockedCertification component = lockComponentCertification(
                    connection, tenantId, assembly.componentCertificationId());
            LockedActionRun actionRun = lockActionRun(
                    connection,
                    tenantId,
                    input,
                    actionRunId,
                    attemptTokenHash,
                    assembly);

            requireSession(session, assembly, preparedAt);
            requireDecisionPoint(decisionPoint, assembly, session, input, preparedAt);
            requireComponent(component, assembly, preparedAt);
            requireActionRun(actionRun);

            String operationId =
                    ReferenceAssemblyReleaseDispatchOperationIds.derive(tenantId, input);
            Optional<ReferenceAssemblyReleaseDispatchIntent> existing =
                    intents.find(connection, operationId);
            if (mode == PreparationMode.EXACT_RETRY) {
                ReferenceAssemblyReleaseDispatchIntent canonical = existing
                        .filter(intent -> exact(
                                intent,
                                tenantId,
                                input,
                                actionRunId,
                                attemptTokenHash,
                                session.deadlineAt()))
                        .orElseThrow(() -> new IllegalStateException(
                                "Action-bound Reference Assembly has no exact release intent"));
                connection.commit();
                return canonical;
            }
            if (existing.isPresent()) {
                throw new IllegalStateException(
                        "unowned Reference Assembly already has a deterministic release intent");
            }

            ReferenceAssembly actionBound = assembly.bindReleaseAction(actionRunId, preparedAt);
            bindReleaseAction(connection, assembly, actionBound);
            ReferenceAssembly canonicalAssembly =
                    lockAssembly(connection, tenantId, input);
            if (!canonicalAssembly.equals(actionBound)) {
                throw persistenceFailure(
                        "bound Reference Assembly differs from its canonical snapshot");
            }

            ReferenceAssemblyReleaseDispatchIntent pending =
                    ReferenceAssemblyReleaseDispatchIntent.pending(
                            operationId,
                            tenantId,
                            input,
                            actionRunId,
                            attemptTokenHash,
                            session.deadlineAt(),
                            preparedAt);
            intents.create(connection, pending);
            ReferenceAssemblyReleaseDispatchIntent canonical = intents
                    .find(connection, operationId)
                    .filter(pending::equals)
                    .orElseThrow(() -> persistenceFailure(
                            "created Reference Assembly release intent is not canonical"));
            connection.commit();
            return canonical;
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static ReferenceAssembly lockAssembly(
            Connection connection, TenantId tenantId, ReferenceAssemblyReleaseInput input)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ASSEMBLY)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, input.referenceAssemblyId().value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException(
                            "Reference Assembly is not visible in tenant scope");
                }
                return mapAssembly(row);
            }
        }
    }

    private static LockedSession lockSession(
            Connection connection, TenantId tenantId, ReferenceAssembly assembly)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SESSION)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, assembly.buildSessionId().value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("BuildSession is not visible in tenant scope");
                }
                return new LockedSession(
                        row.getString("product_line_id"),
                        row.getString("status"),
                        row.getString("current_phase"),
                        new CertificationArtifactLock(
                                new ArtifactReference(row.getString("requirements_artifact_ref")),
                                new ContentHash(row.getString("requirements_hash"))),
                        getOptionalText(row, "current_certification_id"),
                        row.getTimestamp("cancellation_requested_at") != null,
                        getInstant(row, "deadline_at"),
                        getInstant(row, "updated_at"));
            }
        }
    }

    private DecisionPoint lockDecisionPoint(
            Connection connection, TenantId tenantId, ReferenceAssemblyReleaseInput input)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_DECISION_POINT)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, input.releaseDecisionPointId().value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException(
                            "release DecisionPoint is not visible in tenant scope");
                }
                try {
                    return new DecisionPoint(
                            new DecisionPointId(row.getString("decision_point_id")),
                            new TenantId(row.getString("tenant_id")),
                            new BuildSessionId(row.getString("build_session_id")),
                            row.getString("type"),
                            DecisionPointStatus.valueOf(row.getString("status")),
                            row.getString("subject_type"),
                            row.getString("subject_id"),
                            row.getLong("subject_version"),
                            new ContentHash(row.getString("subject_hash")),
                            new ArtifactReference(row.getString("question_artifact_ref")),
                            row.getString("options_schema_id"),
                            readExactStringSet(
                                    row.getString("required_permissions_json"),
                                    "DecisionPoint required permissions"),
                            row.getInt("minimum_approvers"),
                            new ArtifactReference(row.getString("policy_snapshot_ref")),
                            getInstant(row, "opened_at"),
                            getInstant(row, "due_at"),
                            getOptionalInstant(row, "decided_at"),
                            getOptionalText(row, "terminal_decision_id").map(DecisionId::new),
                            row.getLong("version"));
                } catch (FactoryPersistenceException invalid) {
                    throw invalid;
                } catch (RuntimeException invalid) {
                    throw persistenceFailure(
                            "release DecisionPoint row failed strict reconstruction", invalid);
                }
            }
        }
    }

    private static LockedCertification lockComponentCertification(
            Connection connection, TenantId tenantId, CertificationId certificationId)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(LOCK_COMPONENT_CERTIFICATION)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, certificationId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException(
                            "component Certification is not visible in tenant scope");
                }
                return new LockedCertification(
                        row.getString("product_line_id"),
                        row.getString("artifact_type"),
                        new ContentHash(row.getString("candidate_hash")),
                        optionalLock(
                                row,
                                "certification_manifest_ref",
                                "certification_manifest_hash"),
                        row.getString("status"),
                        getOptionalInstant(row, "expires_at"),
                        getInstant(row, "updated_at"));
            }
        }
    }

    private LockedActionRun lockActionRun(
            Connection connection,
            TenantId tenantId,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            String attemptTokenHash,
            ReferenceAssembly assembly) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACTION_RUN)) {
            statement.setString(1, actionRunId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException(
                            "ActionRun is not visible for Reference Assembly release dispatch");
                }
                ReferenceAssemblyReleaseInput storedInput;
                try {
                    storedInput = ReferenceAssemblyReleaseInput.from(
                            readMap(row.getString("input_json"), "release Action input"));
                } catch (FactoryPersistenceException invalid) {
                    throw invalid;
                } catch (RuntimeException invalid) {
                    throw new IllegalStateException(
                            "ActionRun release input is not strict v1", invalid);
                }
                Map<String, Object> metadata = readMap(
                        row.getString("context_metadata_json"), "release Action context");
                String storedAttemptHash;
                try {
                    storedAttemptHash = ReferenceAssemblyReleaseAttemptTokens.hash(
                            row.getString("attempt_token"));
                } catch (RuntimeException invalidAttempt) {
                    throw new IllegalStateException(
                            "ActionRun is missing the exact release attempt", invalidAttempt);
                }
                String expectedDuplicateKey = ReferenceAssemblyReleaseIdempotencyKeys.derive(
                        assembly, input.expectedReferenceAssemblyVersion());
                boolean exact = tenantId.value().equals(row.getString("tenant_id"))
                        && ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID.equals(
                                row.getString("user_id"))
                        && ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID.equals(
                                row.getString("requester_id"))
                        && hasText(row.getString("trace_id"))
                        && ReferenceAssemblyReleaseAction.ACTION_ID.equals(
                                row.getString("action_id"))
                        && ActionRequestChannel.INTERNAL.name().equals(
                                row.getString("request_channel"))
                        && ActionProposerType.SERVICE.name().equals(
                                row.getString("proposer_type"))
                        && storedInput.equals(input)
                        && metadata.keySet().equals(CANONICAL_ACTION_CONTEXT_KEYS)
                        && ReferenceAssemblyReleaseAction.RESOURCE_TYPE.equals(
                                metadata.get("resource.type"))
                        && input.referenceAssemblyId().value().equals(
                                metadata.get("resource.id"))
                        && hasExactPermission(
                                metadata.get("actor.permissions"),
                                ReferenceAssemblyReleaseAction.PERMISSION)
                        && expectedDuplicateKey.equals(row.getString("duplicate_key"))
                        && attemptTokenHash.equals(storedAttemptHash);
                if (!exact) {
                    throw new IllegalStateException(
                            "ActionRun is not the exact Reference Assembly release owner");
                }
                return new LockedActionRun(row.getString("status"));
            }
        }
    }

    private static PreparationMode requireAssembly(
            ReferenceAssembly assembly,
            TenantId tenantId,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            Instant preparedAt) {
        boolean immutable = assembly.tenantId().equals(tenantId)
                && assembly.referenceAssemblyId().equals(input.referenceAssemblyId())
                && assembly.status() == ReferenceAssemblyStatus.INSPECTED
                && assembly.releaseManifest().isEmpty()
                && assembly.assemblyManifest()
                        .map(lock -> lock.hash().equals(input.assemblyManifestHash()))
                        .orElse(false)
                && assembly.inspectionReport()
                        .map(lock -> lock.hash().equals(input.inspectionReportHash()))
                        .orElse(false)
                && assembly.releaseDecisionPointId()
                        .filter(input.releaseDecisionPointId()::equals)
                        .isPresent()
                && assembly.releaseSubjectHash()
                        .filter(input.releaseSubjectHash()::equals)
                        .isPresent()
                && !preparedAt.isBefore(assembly.updatedAt());
        if (!immutable) {
            throw new IllegalStateException(
                    "Reference Assembly is not the exact INSPECTED release version");
        }
        if (assembly.version() == input.expectedReferenceAssemblyVersion()
                && assembly.releaseActionRunId().isEmpty()) {
            return PreparationMode.NEW;
        }
        if (input.expectedReferenceAssemblyVersion() < Long.MAX_VALUE
                && assembly.version() == input.expectedReferenceAssemblyVersion() + 1
                && assembly.releaseActionRunId().filter(actionRunId::equals).isPresent()) {
            return PreparationMode.EXACT_RETRY;
        }
        throw new IllegalStateException(
                "Reference Assembly release version or Action owner changed");
    }

    private static void requireSession(
            LockedSession session, ReferenceAssembly assembly, Instant preparedAt) {
        boolean exact = ProductLineId.REFERENCE_ASSEMBLY.value().equals(session.productLineId())
                && BuildSessionStatus.RUNNING.name().equals(session.status())
                && BuildSessionPhase.PACKAGE_RELEASE.id().equals(session.currentPhase())
                && session.requirement().equals(assembly.requirement())
                && session.currentCertificationId().isEmpty()
                && !session.cancelled()
                && !preparedAt.isBefore(session.updatedAt())
                && preparedAt.isBefore(session.deadlineAt());
        if (!exact) {
            throw new IllegalStateException(
                    "BuildSession is not the exact live Reference Assembly release phase");
        }
    }

    private static void requireDecisionPoint(
            DecisionPoint point,
            ReferenceAssembly assembly,
            LockedSession session,
            ReferenceAssemblyReleaseInput input,
            Instant preparedAt) {
        ArtifactReference canonicalQuestion = new ArtifactReference(
                RELEASE_SUBJECT_REFERENCE_PREFIX + input.releaseSubjectHash().sha256());
        DecisionPointId canonicalId = deriveReleaseDecisionPointId(
                assembly.tenantId(), assembly.referenceAssemblyId(), input.releaseSubjectHash());
        boolean exact = point.decisionPointId().equals(input.releaseDecisionPointId())
                && point.decisionPointId().equals(canonicalId)
                && point.tenantId().equals(assembly.tenantId())
                && point.buildSessionId().equals(assembly.buildSessionId())
                && ReferenceAssemblyReleaseReviewService.DECISION_TYPE.equals(point.type())
                && point.status() == DecisionPointStatus.APPROVED
                && point.version() == 1
                && ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE.equals(
                        point.subjectType())
                && point.subjectId().equals(assembly.referenceAssemblyId().value())
                && point.subjectVersion() < Long.MAX_VALUE
                && point.subjectVersion() + 1 == input.expectedReferenceAssemblyVersion()
                && point.subjectHash().equals(input.releaseSubjectHash())
                && point.questionArtifactRef().equals(canonicalQuestion)
                && ReferenceAssemblyReleaseReviewService.OPTIONS_SCHEMA_ID.equals(
                        point.optionsSchemaId())
                && point.requiredPermissions().equals(
                        Set.of(ReferenceAssemblyReleaseReviewService.REQUIRED_PERMISSION))
                && point.minimumApprovers() == 1
                && point.policySnapshotRef().equals(assembly.policySnapshot().reference())
                && point.dueAt().equals(session.deadlineAt())
                && !point.openedAt().isBefore(assembly.createdAt())
                && point.openedAt().isBefore(point.dueAt())
                && !point.openedAt().isAfter(preparedAt)
                && point.decidedAt()
                        .filter(value -> !value.isBefore(point.openedAt()))
                        .filter(value -> value.isBefore(point.dueAt()))
                        .filter(value -> !value.isAfter(preparedAt))
                        .isPresent()
                && point.terminalDecisionId().isPresent();
        if (!exact) {
            throw new IllegalStateException(
                    "release DecisionPoint is not the exact approved canonical review");
        }
    }

    private static void requireComponent(
            LockedCertification component,
            ReferenceAssembly assembly,
            Instant preparedAt) {
        boolean exact = ProductLineId.AGENT_PACK.value().equals(component.productLineId())
                && CertifiedArtifactType.AGENT_PACK.name().equals(component.artifactType())
                && CertificationStatus.CERTIFIED.name().equals(component.status())
                && component.candidateHash().equals(assembly.componentCandidateHash())
                && component.certificationManifest()
                        .filter(assembly.componentCertificationManifest()::equals)
                        .isPresent()
                && !preparedAt.isBefore(component.updatedAt())
                && component.expiresAt().map(preparedAt::isBefore).orElse(true);
        if (!exact) {
            throw new IllegalStateException(
                    "component Certification is not the exact current CERTIFIED input");
        }
    }

    private static void requireActionRun(LockedActionRun actionRun) {
        if (!ActionRunStatus.RUNNING.name().equals(actionRun.status())) {
            throw new IllegalStateException(
                    "ActionRun is not RUNNING at Reference Assembly release dispatch");
        }
    }

    private static void bindReleaseAction(
            Connection connection, ReferenceAssembly expected, ReferenceAssembly next)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(BIND_ACTION)) {
            int index = 1;
            statement.setString(index++, next.releaseActionRunId().orElseThrow());
            statement.setLong(index++, next.version());
            setInstant(statement, index++, next.updatedAt());
            statement.setString(index++, expected.tenantId().value());
            statement.setString(index++, expected.referenceAssemblyId().value());
            statement.setLong(index, expected.version());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "Reference Assembly release Action binding CAS lost");
            }
        }
    }

    private static ReferenceAssembly mapAssembly(ResultSet row) throws SQLException {
        try {
            return new ReferenceAssembly(
                    new ReferenceAssemblyId(row.getString("reference_assembly_id")),
                    new TenantId(row.getString("tenant_id")),
                    new BuildSessionId(row.getString("build_session_id")),
                    lock(row, "requirement_ref", "requirement_hash"),
                    lock(row, "consumer_contract_ref", "consumer_contract_hash"),
                    lock(row, "host_fixture_ref", "host_fixture_hash"),
                    lock(row, "policy_snapshot_ref", "policy_snapshot_hash"),
                    new CertificationId(row.getString("component_certification_id")),
                    new ContentHash(row.getString("component_candidate_hash")),
                    lock(
                            row,
                            "component_certification_manifest_ref",
                            "component_certification_manifest_hash"),
                    ReferenceAssemblyStatus.valueOf(row.getString("status")),
                    optionalLock(row, "assembly_manifest_ref", "assembly_manifest_hash"),
                    optionalLock(row, "inspection_report_ref", "inspection_report_hash"),
                    optionalLock(row, "release_manifest_ref", "release_manifest_hash"),
                    getOptionalText(row, "release_decision_point_id").map(DecisionPointId::new),
                    getOptionalText(row, "release_subject_hash").map(ContentHash::new),
                    getOptionalText(row, "release_action_run_id"),
                    getOptionalText(row, "stable_code"),
                    row.getLong("version"),
                    getInstant(row, "created_at"),
                    getInstant(row, "updated_at"));
        } catch (FactoryPersistenceException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw persistenceFailure(
                    "Reference Assembly row failed strict reconstruction", invalid);
        }
    }

    private Map<String, Object> readMap(String value, String subject) {
        try {
            return json.readValue(value, JSON_MAP);
        } catch (Exception invalid) {
            throw persistenceFailure(subject + " is not strict JSON", invalid);
        }
    }

    private Set<String> readExactStringSet(String value, String subject) {
        try {
            List<String> parsed = json.readValue(value, JSON_STRINGS);
            Set<String> exact = new HashSet<>();
            for (String entry : parsed) {
                if (!hasText(entry) || !exact.add(entry)) {
                    throw new IllegalArgumentException(
                            subject + " must contain unique nonblank strings");
                }
            }
            return Set.copyOf(exact);
        } catch (FactoryPersistenceException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw persistenceFailure(subject + " is not a strict string set", invalid);
        }
    }

    private static CertificationArtifactLock lock(
            ResultSet row, String referenceColumn, String hashColumn) throws SQLException {
        return new CertificationArtifactLock(
                new ArtifactReference(row.getString(referenceColumn)),
                new ContentHash(row.getString(hashColumn)));
    }

    private static Optional<CertificationArtifactLock> optionalLock(
            ResultSet row, String referenceColumn, String hashColumn) throws SQLException {
        String reference = row.getString(referenceColumn);
        String hash = row.getString(hashColumn);
        if (reference == null && hash == null) {
            return Optional.empty();
        }
        if (reference == null || hash == null) {
            throw persistenceFailure(
                    "artifact lock contains only one of reference and hash");
        }
        return Optional.of(new CertificationArtifactLock(
                new ArtifactReference(reference), new ContentHash(hash)));
    }

    private static boolean exact(
            ReferenceAssemblyReleaseDispatchIntent intent,
            TenantId tenantId,
            ReferenceAssemblyReleaseInput input,
            String actionRunId,
            String attemptTokenHash,
            Instant deadlineAt) {
        return intent.tenantId().equals(tenantId)
                && intent.referenceAssemblyId().equals(input.referenceAssemblyId())
                && intent.assemblyManifestHash().equals(input.assemblyManifestHash())
                && intent.inspectionReportHash().equals(input.inspectionReportHash())
                && intent.releaseDecisionPointId().equals(input.releaseDecisionPointId())
                && intent.releaseSubjectHash().equals(input.releaseSubjectHash())
                && intent.expectedReferenceAssemblyVersion()
                        == input.expectedReferenceAssemblyVersion()
                && intent.actionRunId().equals(actionRunId)
                && intent.attemptTokenHash().equals(attemptTokenHash)
                && (deadlineAt == null || intent.deadlineAt().equals(deadlineAt));
    }

    private static boolean hasExactPermission(Object raw, String required) {
        if (!(raw instanceof Collection<?> values)) {
            return false;
        }
        Set<String> exact = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text) || !hasText(text) || !exact.add(text)) {
                return false;
            }
        }
        return exact.equals(Set.of(required));
    }

    private static DecisionPointId deriveReleaseDecisionPointId(
            TenantId tenantId,
            ReferenceAssemblyId referenceAssemblyId,
            ContentHash subjectHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, tenantId.value());
            updateLengthPrefixed(digest, referenceAssemblyId.value());
            updateLengthPrefixed(digest, subjectHash.sha256());
            return new DecisionPointId(
                    RELEASE_DECISION_ID_PREFIX + HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static boolean hasText(String value) {
        return value != null
                && !value.isBlank()
                && value.equals(value.trim())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static void requireText(String value, String name, int maximumLength) {
        if (!hasText(value) || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be bounded exact text");
        }
    }

    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "attemptTokenHash must be lowercase SHA-256 hex");
        }
    }

    private static void requireCanonicalInstant(Instant value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.equals(value.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException(name + " must use microsecond precision");
        }
    }

    private static FactoryPersistenceException persistenceFailure(String message) {
        return persistenceFailure(message, new IllegalStateException(message));
    }

    private static FactoryPersistenceException persistenceFailure(
            String message, Throwable cause) {
        return new FactoryPersistenceException(message, cause);
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private enum PreparationMode {
        NEW,
        EXACT_RETRY
    }

    private record LockedSession(
            String productLineId,
            String status,
            String currentPhase,
            CertificationArtifactLock requirement,
            Optional<String> currentCertificationId,
            boolean cancelled,
            Instant deadlineAt,
            Instant updatedAt) {}

    private record LockedCertification(
            String productLineId,
            String artifactType,
            ContentHash candidateHash,
            Optional<CertificationArtifactLock> certificationManifest,
            String status,
            Optional<Instant> expiresAt,
            Instant updatedAt) {}

    private record LockedActionRun(String status) {}
}
