package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseAction;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionExecutor;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ActionBackedReferenceAssemblyReleaseLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyArtifactCodec;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAttemptTokens;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDeadlines;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntent;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchIntentStatus;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseTransaction;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import io.github.flowerjvm.factory.infrastructure.referenceassembly.JacksonReferenceAssemblyArtifactCodec;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Final, fail-closed JDBC commit boundary for one governed Reference Assembly release.
 *
 * <p>A fresh release locks ledgers in the single global order ReferenceAssembly, BuildSession,
 * DecisionPoint, component Certification, ActionRun, then release intent. It advances version
 * fences on the BuildSession and release Action before the dedicated package-private release CAS
 * may make {@code RELEASED} visible. Component Certification remains independently revocable;
 * current-status consumer read gates quarantine a release after such revocation. An exact
 * already-committed release is a read-only crash-recovery observation and therefore returns after
 * the first lock.
 */
public final class JdbcReferenceAssemblyReleaseTransaction
        implements ReferenceAssemblyReleaseTransaction {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private static final String ACTION_STAGE = "execute-action";
    private static final String RELEASE_SUBJECT_PREFIX =
            "factory-reference-assembly/release-subject/sha256/";
    private static final String RELEASE_MANIFEST_PREFIX =
            "factory-reference-assembly/release-manifest/sha256/";
    private static final String ACTION_DEFERRED_CODE = "ACTION_DEFERRED";
    private static final String ACTION_DEFERRED_MESSAGE =
            "Action was dispatched and is awaiting completion.";

    private static final String LOCK_ACTION_RUN = """
            SELECT run_id, version, tenant_id, user_id, trace_id, context_metadata_json,
                   action_id, requester_id, request_channel, proposer_type,
                   input_json, duplicate_key, status, current_stage,
                   due_at, attempt_token, external_operation_id,
                   external_operation_metadata_json, result_status, result_code,
                   result_message, result_output_json, result_retry_disposition,
                   failure_reason,
                   created_at, updated_at
            FROM action_run
            WHERE run_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_RELEASE_INTENT = """
            SELECT operation_id
            FROM factory_reference_assembly_release_intent
            WHERE operation_id = ?
            FOR UPDATE
            """;
    private static final String FENCE_BUILD_SESSION = """
            UPDATE factory_build_session
            SET version = ?, updated_at = ?
            WHERE tenant_id = ?
              AND build_session_id = ?
              AND product_line_id = ?
              AND version = ?
              AND status = 'RUNNING'
              AND current_phase = 'package-release'
              AND cancellation_requested_at IS NULL
            """;
    private static final String FENCE_ACTION_RUN = """
            UPDATE action_run
            SET version = ?, updated_at = ?
            WHERE run_id = ?
              AND version = ?
              AND status = 'WAITING_EXTERNAL'
              AND attempt_token = ?
            """;

    private final DataSource dataSource;
    private final JdbcReferenceAssemblyRepository assemblies;
    private final JdbcBuildSessionRepository sessions;
    private final JdbcDecisionPointRepository decisions;
    private final JdbcCertificationRepository certifications;
    private final JdbcReferenceAssemblyReleaseDispatchIntentRepository intents;
    private final JdbcArtifactStore artifacts;
    private final ReferenceAssemblyArtifactCodec releaseCodec;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcReferenceAssemblyReleaseTransaction(DataSource dataSource) {
        this(dataSource, new ObjectMapper(), Clock.systemUTC());
    }

    public JdbcReferenceAssemblyReleaseTransaction(DataSource dataSource, Clock clock) {
        this(dataSource, new ObjectMapper(), clock);
    }

    public JdbcReferenceAssemblyReleaseTransaction(
            DataSource dataSource, ObjectMapper objectMapper, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.assemblies = new JdbcReferenceAssemblyRepository(dataSource);
        this.sessions = new JdbcBuildSessionRepository(dataSource);
        this.decisions = new JdbcDecisionPointRepository(dataSource, objectMapper);
        this.certifications = new JdbcCertificationRepository(dataSource);
        this.intents = new JdbcReferenceAssemblyReleaseDispatchIntentRepository(dataSource);
        this.artifacts = new JdbcArtifactStore(dataSource, clock);
        this.releaseCodec = new JacksonReferenceAssemblyArtifactCodec(objectMapper);
        this.json = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ReleaseCommit commit(
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssembly expectedActionBound,
            ReferenceAssembly proposedReleased) {
        validateProposal(intent, expectedActionBound, proposedReleased);
        return withConnection(dataSource, "commit Reference Assembly release", connection ->
                inTransaction(connection, intent, expectedActionBound, proposedReleased));
    }

    private ReleaseCommit inTransaction(
            Connection connection,
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssembly expected,
            ReferenceAssembly proposed) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            // Global lock order. Do not reorder without changing every release authority path.
            ReferenceAssembly current = JdbcReferenceAssemblyRepository.find(
                            connection,
                            intent.tenantId(),
                            intent.referenceAssemblyId(),
                            true)
                    .orElseThrow(() -> invalid(
                            "ReferenceAssembly is not visible in trusted tenant scope"));
            if (current.status() == ReferenceAssemblyStatus.RELEASED) {
                if (!isExactPriorRelease(current, expected, proposed)) {
                    throw invalid("prior RELEASED ReferenceAssembly has a different exact owner");
                }
                connection.commit();
                return new ReleaseCommit(current, false);
            }
            if (!current.equals(expected)) {
                throw invalid("ReferenceAssembly is not the exact Action-bound INSPECTED version");
            }

            BuildSession session = sessions
                    .find(connection, intent.tenantId(), expected.buildSessionId(), true)
                    .orElseThrow(() -> invalid(
                            "BuildSession is not visible in trusted tenant scope"));

            DecisionPoint decision = decisions
                    .find(connection, intent.tenantId(), intent.releaseDecisionPointId(), true)
                    .orElseThrow(() -> invalid(
                            "release DecisionPoint is not visible in trusted tenant scope"));

            Certification component = certifications
                    .find(
                            connection,
                            intent.tenantId(),
                            expected.componentCertificationId(),
                            true)
                    .orElseThrow(() -> invalid(
                            "component Certification is not visible in trusted tenant scope"));

            LockedActionRun action = lockActionRun(connection, intent.actionRunId());
            ReferenceAssemblyReleaseDispatchIntent storedIntent =
                    lockIntent(connection, intent.operationId());

            Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            requireFreshIntent(intent, storedIntent, observedAt);
            requireLiveSession(intent, expected, proposed, session, observedAt);
            requireApprovedDecision(intent, expected, session, decision, observedAt);
            requireCurrentComponent(intent, expected, component, observedAt);
            requireWaitingAction(intent, expected, action, observedAt);
            requireCanonicalReleaseSubject(connection, intent, expected, decision);
            requireCanonicalReleaseManifest(connection, intent, expected, proposed, component);

            fenceReleaseAuthorities(connection, session, action, observedAt);
            if (!assemblies.compareAndSetReleased(connection, expected, proposed)) {
                throw invalid("locked ReferenceAssembly release CAS did not persist");
            }
            ReferenceAssembly canonical = JdbcReferenceAssemblyRepository.find(
                            connection,
                            intent.tenantId(),
                            intent.referenceAssemblyId(),
                            false)
                    .orElseThrow(() -> invalid(
                            "released ReferenceAssembly disappeared before commit"));
            if (!canonical.equals(proposed)) {
                throw invalid("release transaction produced a non-canonical ReferenceAssembly");
            }
            connection.commit();
            return new ReleaseCommit(canonical, true);
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private LockedActionRun lockActionRun(Connection connection, String actionRunId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACTION_RUN)) {
            statement.setString(1, actionRunId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw invalid("release ActionRun is not visible");
                }
                long dueAtMillis = row.getLong("due_at");
                Instant dueAt = row.wasNull() ? null : Instant.ofEpochMilli(dueAtMillis);
                long createdAtMillis = row.getLong("created_at");
                long updatedAtMillis = row.getLong("updated_at");
                return new LockedActionRun(
                        row.getString("run_id"),
                        row.getLong("version"),
                        row.getString("tenant_id"),
                        row.getString("user_id"),
                        row.getString("trace_id"),
                        readMap(row.getString("context_metadata_json"), "Action context"),
                        row.getString("action_id"),
                        row.getString("requester_id"),
                        row.getString("request_channel"),
                        row.getString("proposer_type"),
                        readMap(row.getString("input_json"), "Action input"),
                        row.getString("duplicate_key"),
                        row.getString("status"),
                        row.getString("current_stage"),
                        dueAt,
                        row.getString("attempt_token"),
                        row.getString("external_operation_id"),
                        readMap(
                                row.getString("external_operation_metadata_json"),
                                "Action external-operation metadata"),
                        row.getString("result_status"),
                        row.getString("result_code"),
                        row.getString("result_message"),
                        readMap(row.getString("result_output_json"), "Action result output"),
                        row.getString("result_retry_disposition"),
                        row.getString("failure_reason"),
                        Instant.ofEpochMilli(createdAtMillis),
                        Instant.ofEpochMilli(updatedAtMillis));
            }
        }
    }

    /**
     * Advances the mutable shipment-control authorities under the locks already held by this
     * transaction. A BuildSession or Action cancellation formed before shipment can therefore no
     * longer commit after the RELEASED row becomes visible. Certification revocation intentionally
     * remains an independent post-release invalidation handled by current-status read gates.
     */
    private static void fenceReleaseAuthorities(
            Connection connection,
            BuildSession session,
            LockedActionRun action,
            Instant observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FENCE_BUILD_SESSION)) {
            statement.setLong(1, Math.addExact(session.version(), 1));
            setInstant(statement, 2, observedAt);
            statement.setString(3, session.tenantId().value());
            statement.setString(4, session.buildSessionId().value());
            statement.setString(5, ProductLineId.REFERENCE_ASSEMBLY.value());
            statement.setLong(6, session.version());
            if (statement.executeUpdate() != 1) {
                throw invalid("BuildSession release authority fence did not persist");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(FENCE_ACTION_RUN)) {
            statement.setLong(1, Math.addExact(action.version(), 1));
            statement.setLong(2, observedAt.toEpochMilli());
            statement.setString(3, action.runId());
            statement.setLong(4, action.version());
            statement.setString(5, action.attemptToken());
            if (statement.executeUpdate() != 1) {
                throw invalid("ActionRun release authority fence did not persist");
            }
        }
    }

    private ReferenceAssemblyReleaseDispatchIntent lockIntent(
            Connection connection, String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_RELEASE_INTENT)) {
            statement.setString(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw invalid("release intent is not visible");
                }
            }
        }
        return intents.find(connection, operationId)
                .orElseThrow(() -> invalid("locked release intent is not readable"));
    }

    private static void validateProposal(
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssembly expected,
            ReferenceAssembly proposed) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(expected, "expectedActionBound");
        Objects.requireNonNull(proposed, "proposedReleased");
        if (intent.status() != ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING
                || intent.claimToken().isEmpty()
                || intent.leaseUntil().isEmpty()
                || expected.status() != ReferenceAssemblyStatus.INSPECTED
                || expected.version() != Math.addExact(
                        intent.expectedReferenceAssemblyVersion(), 1)
                || !expected.tenantId().equals(intent.tenantId())
                || !expected.referenceAssemblyId().equals(intent.referenceAssemblyId())
                || expected.assemblyManifest()
                        .map(lock -> lock.hash().equals(intent.assemblyManifestHash()))
                        .orElse(false)
                        == false
                || expected.inspectionReport()
                        .map(lock -> lock.hash().equals(intent.inspectionReportHash()))
                        .orElse(false)
                        == false
                || expected.releaseDecisionPointId()
                        .filter(intent.releaseDecisionPointId()::equals)
                        .isEmpty()
                || expected.releaseSubjectHash()
                        .filter(intent.releaseSubjectHash()::equals)
                        .isEmpty()
                || expected.releaseActionRunId()
                        .filter(intent.actionRunId()::equals)
                        .isEmpty()
                || expected.releaseManifest().isPresent()
                || proposed.status() != ReferenceAssemblyStatus.RELEASED
                || proposed.releaseManifest().isEmpty()
                || !proposed.updatedAt().isBefore(intent.deadlineAt())) {
            throw new IllegalArgumentException(
                    "release proposal is not bound to the exact active Action-owned intent");
        }
        var releaseLock = proposed.releaseManifest().orElseThrow();
        ArtifactReference canonicalReference = new ArtifactReference(
                RELEASE_MANIFEST_PREFIX + releaseLock.hash().sha256());
        if (!releaseLock.reference().equals(canonicalReference)) {
            throw new IllegalArgumentException(
                    "release manifest lock is not a canonical content-addressed reference");
        }
        ReferenceAssembly exactDerived = expected.release(
                releaseLock,
                intent.releaseDecisionPointId(),
                intent.releaseSubjectHash(),
                intent.actionRunId(),
                proposed.updatedAt());
        if (!exactDerived.equals(proposed)) {
            throw new IllegalArgumentException(
                    "proposed RELEASED snapshot is not the exact aggregate derivation");
        }
    }

    private static boolean isExactPriorRelease(
            ReferenceAssembly current,
            ReferenceAssembly expected,
            ReferenceAssembly proposed) {
        if (current.version() != proposed.version()
                || !current.releaseManifest().equals(proposed.releaseManifest())
                || !current.releaseActionRunId().equals(proposed.releaseActionRunId())) {
            return false;
        }
        try {
            return expected.release(
                            current.releaseManifest().orElseThrow(),
                            current.releaseDecisionPointId().orElseThrow(),
                            current.releaseSubjectHash().orElseThrow(),
                            current.releaseActionRunId().orElseThrow(),
                            current.updatedAt())
                    .equals(current);
        } catch (RuntimeException mismatch) {
            return false;
        }
    }

    private static void requireFreshIntent(
            ReferenceAssemblyReleaseDispatchIntent supplied,
            ReferenceAssemblyReleaseDispatchIntent stored,
            Instant observedAt) {
        if (!stored.equals(supplied)
                || stored.status() != ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING
                || stored.claimToken().isEmpty()
                || stored.leaseUntil().filter(observedAt::isBefore).isEmpty()
                || !observedAt.isBefore(stored.deadlineAt())
                || stored.updatedAt().isAfter(observedAt)) {
            throw invalid("release intent lost its exact live claim before commit");
        }
    }

    private static void requireLiveSession(
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssembly expected,
            ReferenceAssembly proposed,
            BuildSession session,
            Instant observedAt) {
        boolean exact = session.tenantId().equals(intent.tenantId())
                && session.buildSessionId().equals(expected.buildSessionId())
                && ProductLineId.REFERENCE_ASSEMBLY.equals(session.productLineId())
                && session.status() == BuildSessionStatus.RUNNING
                && session.currentPhase() == BuildSessionPhase.PACKAGE_RELEASE
                && session.cancellationRequestedAt().isEmpty()
                && session.currentCertificationId().isEmpty()
                && session.requirementsArtifactRef().equals(expected.requirement().reference())
                && session.requirementsHash().equals(expected.requirement().hash())
                && session.deadlineAt().equals(intent.deadlineAt())
                && session.updatedAt().compareTo(observedAt) <= 0
                && proposed.updatedAt().compareTo(session.updatedAt()) >= 0
                && proposed.updatedAt().compareTo(observedAt) <= 0
                && observedAt.isBefore(session.deadlineAt());
        if (!exact) {
            throw invalid("BuildSession lost live PACKAGE_RELEASE authority before commit");
        }
    }

    private static void requireApprovedDecision(
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssembly expected,
            BuildSession session,
            DecisionPoint decision,
            Instant observedAt) {
        ArtifactReference canonicalQuestion = new ArtifactReference(
                RELEASE_SUBJECT_PREFIX + intent.releaseSubjectHash().sha256());
        boolean exact = decision.decisionPointId().equals(intent.releaseDecisionPointId())
                && decision.tenantId().equals(intent.tenantId())
                && decision.buildSessionId().equals(expected.buildSessionId())
                && ReferenceAssemblyReleaseReviewService.DECISION_TYPE.equals(decision.type())
                && decision.status() == DecisionPointStatus.APPROVED
                && decision.version() == 1
                && ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE.equals(
                        decision.subjectType())
                && decision.subjectId().equals(expected.referenceAssemblyId().value())
                && Math.addExact(decision.subjectVersion(), 1)
                        == intent.expectedReferenceAssemblyVersion()
                && decision.subjectHash().equals(intent.releaseSubjectHash())
                && decision.questionArtifactRef().equals(canonicalQuestion)
                && ReferenceAssemblyReleaseReviewService.OPTIONS_SCHEMA_ID.equals(
                        decision.optionsSchemaId())
                && decision.requiredPermissions().equals(Set.of(
                        ReferenceAssemblyReleaseReviewService.REQUIRED_PERMISSION))
                && decision.minimumApprovers() == 1
                && decision.policySnapshotRef().equals(expected.policySnapshot().reference())
                && decision.dueAt().equals(session.deadlineAt())
                && decision.dueAt().equals(intent.deadlineAt())
                && decision.openedAt().isBefore(decision.dueAt())
                && decision.decidedAt().filter(value -> !value.isAfter(observedAt)).isPresent()
                && decision.terminalDecisionId().isPresent();
        if (!exact) {
            throw invalid("release DecisionPoint is not the exact approved canonical subject");
        }
    }

    private static void requireCurrentComponent(
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssembly expected,
            Certification component,
            Instant observedAt) {
        boolean exact = component.certificationId().equals(expected.componentCertificationId())
                && component.inputLock().tenantId().equals(intent.tenantId())
                && ProductLineId.AGENT_PACK.equals(component.inputLock().productLineId())
                && component.inputLock().artifactType() == CertifiedArtifactType.AGENT_PACK
                && component.inputLock().candidateHash().equals(expected.componentCandidateHash())
                && component.status() == CertificationStatus.CERTIFIED
                && component.certificationManifest()
                        .filter(expected.componentCertificationManifest()::equals)
                        .isPresent()
                && component.revokedAt().isEmpty()
                && component.expiresAt().map(observedAt::isBefore).orElse(true)
                && !component.updatedAt().isAfter(observedAt);
        if (!exact) {
            throw invalid("component Certification is no longer the exact active Agent Pack");
        }
    }

    private static void requireWaitingAction(
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssembly expected,
            LockedActionRun action,
            Instant observedAt) {
        ReferenceAssemblyReleaseInput expectedInput = new ReferenceAssemblyReleaseInput(
                intent.referenceAssemblyId(),
                intent.assemblyManifestHash(),
                intent.inspectionReportHash(),
                intent.releaseDecisionPointId(),
                intent.releaseSubjectHash(),
                intent.expectedReferenceAssemblyVersion());
        ReferenceAssemblyReleaseInput storedInput;
        String attemptHash;
        try {
            storedInput = ReferenceAssemblyReleaseInput.from(action.input());
            attemptHash = ReferenceAssemblyReleaseAttemptTokens.hash(action.attemptToken());
        } catch (RuntimeException invalidOwner) {
            throw invalid("release ActionRun contains non-canonical owner data", invalidOwner);
        }
        Map<String, Object> externalMetadata = Map.of(
                "dispatchMode", ReferenceAssemblyReleaseActionExecutor.DISPATCH_MODE,
                ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                intent.referenceAssemblyId().value());
        Map<String, Object> deferredReceipt = Map.of(
                "runId", intent.actionRunId(),
                "operationId", intent.operationId(),
                "dueAt", ReferenceAssemblyReleaseDeadlines.actionDueAt(intent.deadlineAt()).toString());
        boolean exactContext = action.contextMetadata().keySet().equals(Set.of(
                        "actor.permissions", "resource.type", "resource.id"))
                && ReferenceAssemblyReleaseAction.RESOURCE_TYPE.equals(
                        action.contextMetadata().get("resource.type"))
                && intent.referenceAssemblyId().value().equals(
                        action.contextMetadata().get("resource.id"))
                && exactSinglePermission(
                        action.contextMetadata().get("actor.permissions"),
                        ReferenceAssemblyReleaseAction.PERMISSION);
        boolean exact = intent.tenantId().value().equals(action.tenantId())
                && ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID.equals(
                        action.userId())
                && ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID.equals(
                        action.requesterId())
                && action.traceId() != null
                && !action.traceId().isBlank()
                && ReferenceAssemblyReleaseAction.ACTION_ID.equals(action.actionId())
                && "INTERNAL".equals(action.requestChannel())
                && "SERVICE".equals(action.proposerType())
                && expectedInput.equals(storedInput)
                && ReferenceAssemblyReleaseIdempotencyKeys
                        .derive(expected, intent.expectedReferenceAssemblyVersion())
                        .equals(action.duplicateKey())
                && "WAITING_EXTERNAL".equals(action.status())
                && ACTION_STAGE.equals(action.currentStage())
                && ReferenceAssemblyReleaseDeadlines.actionDueAt(intent.deadlineAt()).equals(action.dueAt())
                && intent.attemptTokenHash().equals(attemptHash)
                && intent.operationId().equals(action.externalOperationId())
                && externalMetadata.equals(action.externalOperationMetadata())
                && ActionExecutionStatus.ACCEPTED.name().equals(action.resultStatus())
                && ACTION_DEFERRED_CODE.equals(action.resultCode())
                && ACTION_DEFERRED_MESSAGE.equals(action.resultMessage())
                && deferredReceipt.equals(action.resultOutput())
                && RetryDisposition.NEVER.name().equals(action.resultRetryDisposition())
                && "".equals(action.failureReason())
                && !action.updatedAt().isBefore(action.createdAt())
                && !action.updatedAt().isAfter(observedAt)
                && observedAt.isBefore(action.dueAt())
                && exactContext;
        if (!exact) {
            throw invalid("ActionRun is not the exact WAITING_EXTERNAL release owner");
        }
    }

    private void requireCanonicalReleaseManifest(
            Connection connection,
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssembly expected,
            ReferenceAssembly proposed,
            Certification component) {
        CertificationArtifactLock lock = proposed.releaseManifest().orElseThrow();
        Artifact artifact;
        try {
            artifact = artifacts.find(connection, intent.tenantId(), lock.reference()).orElseThrow();
        } catch (SQLException | RuntimeException missingOrCorrupt) {
            throw invalid("release manifest artifact is not exact immutable content", missingOrCorrupt);
        }
        if (!artifact.tenantId().equals(intent.tenantId())
                || !artifact.reference().equals(lock.reference())
                || !artifact.contentHash().equals(lock.hash())
                || !ReferenceAssemblyArtifactCodec.MEDIA_TYPE.equals(artifact.mediaType())) {
            throw invalid("release manifest artifact lock or media type differs");
        }
        ReferenceAssemblyReleaseManifest stored;
        try {
            stored = releaseCodec.readReleaseManifest(artifact.content());
        } catch (RuntimeException nonCanonical) {
            throw invalid("release manifest artifact is not strict canonical v1", nonCanonical);
        }
        var input = component.inputLock();
        CertifiedAgentComponentRef componentRef = new CertifiedAgentComponentRef(
                CertifiedAgentComponentRef.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.COMPONENT_ROLE,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                component.certificationId(),
                component.certificationManifest().orElseThrow(),
                input.candidateId(),
                input.candidateHash(),
                input.sourceManifest(),
                component.inputLockArtifact(),
                input.verificationRunId(),
                input.verificationResultManifest(),
                input.compatibilityDescriptor(),
                component.certificationEvidence().orElseThrow(),
                input.certificationProfile());
        ReferenceAssemblyReleaseManifest exact = new ReferenceAssemblyReleaseManifest(
                ReferenceAssemblyReleaseManifest.SCHEMA_VERSION,
                ProductLineId.REFERENCE_ASSEMBLY,
                ReferenceAssemblyReleaseManifest.RELEASE_ALGORITHM_ID,
                expected.referenceAssemblyId(),
                expected.requirement(),
                expected.consumerContract(),
                expected.hostFixture(),
                expected.policySnapshot(),
                componentRef,
                expected.assemblyManifest().orElseThrow(),
                expected.inspectionReport().orElseThrow(),
                intent.releaseDecisionPointId(),
                intent.releaseSubjectHash(),
                intent.actionRunId());
        if (!exact.equals(stored)) {
            throw invalid("release manifest differs from the locked product and authority graph");
        }
    }

    private void requireCanonicalReleaseSubject(
            Connection connection,
            ReferenceAssemblyReleaseDispatchIntent intent,
            ReferenceAssembly expected,
            DecisionPoint decision) {
        CertificationArtifactLock lock = new CertificationArtifactLock(
                decision.questionArtifactRef(), decision.subjectHash());
        Artifact artifact;
        try {
            artifact = artifacts.find(connection, intent.tenantId(), lock.reference()).orElseThrow();
        } catch (SQLException | RuntimeException missingOrCorrupt) {
            throw invalid("release subject artifact is not exact immutable content", missingOrCorrupt);
        }
        if (!artifact.tenantId().equals(intent.tenantId())
                || !artifact.reference().equals(lock.reference())
                || !artifact.contentHash().equals(lock.hash())
                || !ReferenceAssemblyArtifactCodec.MEDIA_TYPE.equals(artifact.mediaType())) {
            throw invalid("release subject artifact lock or media type differs");
        }
        ReferenceAssemblyReleaseSubject stored;
        try {
            stored = releaseCodec.readReleaseSubject(artifact.content());
        } catch (RuntimeException nonCanonical) {
            throw invalid("release subject artifact is not strict canonical v1", nonCanonical);
        }
        ReferenceAssemblyReleaseSubject exact = new ReferenceAssemblyReleaseSubject(
                ReferenceAssemblyReleaseSubject.SCHEMA_VERSION,
                ProductLineId.REFERENCE_ASSEMBLY,
                expected.referenceAssemblyId(),
                decision.subjectVersion(),
                expected.assemblyManifest().orElseThrow(),
                expected.inspectionReport().orElseThrow(),
                expected.componentCertificationId(),
                expected.componentCandidateHash(),
                expected.componentCertificationManifest(),
                expected.policySnapshot());
        if (!exact.equals(stored)) {
            throw invalid("release subject differs from the locked inspected product graph");
        }
    }

    private Map<String, Object> readMap(String value, String subject) {
        try {
            return json.readValue(value, JSON_MAP);
        } catch (Exception invalid) {
            throw new FactoryPersistenceException(subject + " is not strict JSON", invalid);
        }
    }

    private static boolean exactSinglePermission(Object value, String required) {
        return value instanceof Collection<?> permissions
                && permissions.size() == 1
                && permissions.stream().allMatch(required::equals);
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException invalid(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LockedActionRun(
            String runId,
            long version,
            String tenantId,
            String userId,
            String traceId,
            Map<String, Object> contextMetadata,
            String actionId,
            String requesterId,
            String requestChannel,
            String proposerType,
            Map<String, Object> input,
            String duplicateKey,
            String status,
            String currentStage,
            Instant dueAt,
            String attemptToken,
            String externalOperationId,
            Map<String, Object> externalOperationMetadata,
            String resultStatus,
            String resultCode,
            String resultMessage,
            Map<String, Object> resultOutput,
            String resultRetryDisposition,
            String failureReason,
            Instant createdAt,
            Instant updatedAt) {}
}
