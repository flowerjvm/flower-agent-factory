package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.production.AgentPackWorkPreparationTransaction;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * One-connection input/session and phase-order preparation. The BuildSession row lock is shared
 * with dispatch and cancellation: a stale phase cannot create a new Worker attempt. No external
 * Worker call or successful production/verification/approval record is created here.
 */
public final class JdbcAgentPackWorkPreparationTransaction implements AgentPackWorkPreparationTransaction {
    private final DataSource dataSource;
    private final JdbcBuildSessionRepository sessions;
    private final JdbcWorkOrderRepository orders;
    private final JdbcWorkerRunRepository runs;
    private final JdbcArtifactStore artifacts;
    private final JacksonWorkerProtocolArtifactDecoder decoder;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcAgentPackWorkPreparationTransaction(DataSource dataSource, ObjectMapper mapper, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.clock = Objects.requireNonNull(clock, "clock");
        sessions = new JdbcBuildSessionRepository(dataSource);
        orders = new JdbcWorkOrderRepository(dataSource, mapper);
        runs = new JdbcWorkerRunRepository(dataSource, mapper);
        artifacts = new JdbcArtifactStore(dataSource, clock);
        decoder = new JacksonWorkerProtocolArtifactDecoder(mapper);
    }

    @Override
    public BuildSession accept(BuildSession requested, List<Artifact> stagedArtifacts) {
        Objects.requireNonNull(requested, "requested");
        var staged = validateArtifacts(requested.tenantId(), stagedArtifacts);
        require(requested.productLineId().equals(ProductLineId.AGENT_PACK)
                        && requested.status() == BuildSessionStatus.RUNNING
                        && requested.currentPhase() == BuildSessionPhase.UNDERSTAND_CUSTOMER
                        && requested.version() == 0 && requested.repairRound() == 0
                        && requested.currentBlueprintRef().isEmpty() && requested.currentCandidateId().isEmpty()
                        && requested.currentCertificationId().isEmpty() && requested.cancellationRequestedAt().isEmpty()
                        && requested.terminalCode().isEmpty() && requested.terminalMessage().isEmpty()
                        && requested.selectedManagerWorkerBinding().isPresent()
                        && requested.selectedCodingWorkerBinding().isPresent()
                        && requested.startedAt().equals(requested.createdAt())
                        && requested.updatedAt().equals(requested.createdAt()),
                "acceptance requires a pristine bounded RUNNING/UNDERSTAND_CUSTOMER Agent Pack session");
        Artifact receipt = acceptanceReceipt(requested, staged);
        return withConnection(dataSource, "accept Agent Pack production request", connection -> transaction(connection, ignored -> {
            // This immutable receipt also serializes equal tenant/request keys across different
            // session or project IDs; the active-session uniqueness constraint alone is insufficient.
            artifacts.store(connection, receipt);
            var existing = sessions.find(connection, requested.tenantId(), requested.buildSessionId(), true);
            if (existing.isPresent()) {
                BuildSession canonical = existing.orElseThrow();
                require(sameAcceptance(canonical, requested), "acceptance identity conflicts with existing session");
                requireExistingArtifacts(connection, staged);
                exact(connection, requested.tenantId(), requested.requirementsArtifactRef(), requested.requirementsHash());
                return canonical;
            }
            Instant now = clock.instant();
            require(!now.isBefore(requested.createdAt()) && now.isBefore(requested.deadlineAt()),
                    "new production request is not inside its persisted deadline");
            storeArtifacts(connection, staged);
            exact(connection, requested.tenantId(), requested.requirementsArtifactRef(), requested.requirementsHash());
            sessions.create(connection, requested);
            return requested;
        }));
    }

    @Override
    public PreparationOutcome prepare(
            BuildSession expected, WorkOrder order, WorkerRunRecord requested, List<Artifact> stagedArtifacts) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(requested, "requested");
        var staged = validateArtifacts(expected.tenantId(), stagedArtifacts);
        validateNewAttempt(expected, order, requested);
        return withConnection(dataSource, "prepare Agent Pack phase work", connection -> transaction(connection, ignored -> {
            BuildSession current = sessions.find(connection, expected.tenantId(), expected.buildSessionId(), true)
                    .orElseThrow(() -> invalid("BuildSession is missing"));
            Instant now = clock.instant();
            require(current.equals(expected), "BuildSession snapshot or version changed");
            require((current.status() == BuildSessionStatus.RUNNING || current.status() == BuildSessionStatus.REPAIRING)
                            && current.cancellationRequestedAt().isEmpty()
                            && current.currentCertificationId().isEmpty()
                            && current.terminalCode().isEmpty() && current.terminalMessage().isEmpty()
                            && !now.isBefore(current.updatedAt()) && now.isBefore(current.deadlineAt())
                            && !now.isBefore(order.createdAt()) && now.isBefore(order.deadlineAt()),
                    "production phase is cancelled, closed or outside its persisted deadline");
            var existingOrder = orders.find(connection, expected.tenantId(), order.workOrderId());
            var existingRun = runs.find(connection, expected.tenantId(), requested.workerRunId());
            if (existingOrder.isPresent() || existingRun.isPresent()) {
                require(existingOrder.filter(order::equals).isPresent()
                                && existingRun.filter(value -> sameAttempt(value, requested)).isPresent(),
                        "phase request conflicts with an existing order or attempt identity");
                requireExistingArtifacts(connection, staged);
                validateLockedInputs(connection, current, order);
                return new PreparationOutcome(PreparationDisposition.EXISTING_EXACT,
                        existingOrder.orElseThrow(), existingRun.orElseThrow());
            }
            var latest = orders.findLatestByBuildSessionAndPhase(
                    connection, current.tenantId(), current.buildSessionId(), order.phase());
            require(latest.isEmpty() || latest.orElseThrow().revision() < order.revision(),
                    "a different request already owns this phase/repair round");
            require(order.supersedesWorkOrderId().isEmpty()
                            ? latest.isEmpty()
                            : latest.filter(value -> value.workOrderId().equals(order.supersedesWorkOrderId().orElseThrow())).isPresent(),
                    "new phase revision must preserve its exact predecessor");
            storeArtifacts(connection, staged);
            validateLockedInputs(connection, current, order);
            orders.create(connection, order);
            runs.create(connection, requested);
            return new PreparationOutcome(PreparationDisposition.CREATED, order, requested);
        }));
    }

    private static void validateNewAttempt(BuildSession session, WorkOrder order, WorkerRunRecord run) {
        require(session.productLineId().equals(ProductLineId.AGENT_PACK)
                        && (session.currentPhase() == BuildSessionPhase.DESIGN_AGENT
                            || session.currentPhase() == BuildSessionPhase.GENERATE_CANDIDATE)
                        && session.currentPhase().id().equals(order.phase())
                        && session.tenantId().equals(order.tenantId()) && session.tenantId().equals(run.tenantId())
                        && session.buildSessionId().equals(order.buildSessionId())
                        && session.buildSessionId().equals(run.buildSessionId())
                        && order.workOrderId().equals(run.workOrderId())
                        && order.revision() == session.repairRound() + 1
                        && !order.createdAt().isBefore(session.updatedAt())
                        && !order.deadlineAt().isAfter(session.deadlineAt())
                        && run.deadlineAt().equals(order.deadlineAt())
                        && run.createdAt().equals(order.createdAt())
                        && session.selectedCodingWorkerBinding().filter(run.workerBindingId()::equals).isPresent()
                        && run.workerCapabilitySnapshot().supportsAll(order.requiredCapabilities()),
                "phase order does not match its session, round, worker binding or deadline");
        require(run.status() == WorkerRunStatus.REQUESTED && run.version() == 0 && run.attemptNo() == 1
                        && run.createdAt().equals(run.updatedAt())
                        && run.actionRunId().isEmpty() && run.attemptTokenHash().isEmpty()
                        && run.externalSessionRef().isEmpty() && run.dispatchOutboxId().isEmpty()
                        && run.startedAt().isEmpty() && run.heartbeatAt().isEmpty()
                        && run.cancelRequestedAt().isEmpty() && run.completedAt().isEmpty()
                        && run.resultArtifactManifestRef().isEmpty() && run.resultHash().isEmpty()
                        && run.code().isEmpty() && run.message().isEmpty() && run.retryDisposition().isEmpty(),
                "phase preparation accepts only a pristine first REQUESTED WorkerRun");
    }

    private void validateLockedInputs(Connection connection, BuildSession session, WorkOrder order) throws SQLException {
        exact(connection, order.tenantId(), order.instructionArtifactRef(), order.instructionHash());
        var inputArtifact = exact(connection, order.tenantId(), order.inputArtifactManifestRef(), order.inputManifestHash());
        require(artifacts.find(connection, order.tenantId(), order.policySnapshotRef()).isPresent(), "policy artifact is missing");
        var input = decoder.decodeInputManifest(inputArtifact.content());
        require(input.workOrderId().equals(order.workOrderId()) && input.buildSessionId().equals(order.buildSessionId()),
                "input manifest has a different order owner");
        exact(connection, order.tenantId(), input.skillArtifactRef(), input.skillHash());
        exact(connection, order.tenantId(), input.dependencyLockRef(), input.dependencyLockHash());
        exact(connection, order.tenantId(), input.toolchainLockRef(), input.toolchainLockHash());
        exact(connection, order.tenantId(), input.apiSignatureIndexRef(), input.apiSignatureIndexHash());
        exact(connection, order.tenantId(), input.productContractBundleRef(), input.productContractBundleHash());
        exact(connection, order.tenantId(), input.requirementTestMatrixRef(), input.requirementTestMatrixHash());
        require(input.repairLock().isPresent() == order.candidateId().isPresent(), "repair candidate and input lock differ");
        if (input.repairLock().isPresent()) {
            var repair = input.repairLock().orElseThrow();
            require(order.candidateId().filter(repair.baseCandidateId()::equals).isPresent()
                            && session.currentCandidateId().filter(repair.baseCandidateId()::equals).isPresent()
                            && session.currentCandidateHash().filter(repair.baseCandidateHash()::equals).isPresent()
                            && repair.repairRound() == session.repairRound()
                            && repair.maxRepairRounds() == session.maxRepairRounds(),
                    "repair lineage or round changed");
            exact(connection, order.tenantId(), repair.findingManifestRef(), repair.findingManifestHash());
        }
    }

    private Artifact exact(Connection connection, TenantId tenant, ArtifactReference ref, ContentHash hash) throws SQLException {
        return artifacts.find(connection, tenant, ref).filter(value -> value.contentHash().equals(hash))
                .orElseThrow(() -> invalid("required immutable artifact is missing or has another hash"));
    }

    private void storeArtifacts(Connection connection, List<Artifact> staged) throws SQLException {
        for (Artifact artifact : staged) artifacts.store(connection, artifact);
    }

    private void requireExistingArtifacts(Connection connection, List<Artifact> staged) throws SQLException {
        for (Artifact artifact : staged) {
            var existing = exact(connection, artifact.tenantId(), artifact.reference(), artifact.contentHash());
            require(existing.mediaType().equals(artifact.mediaType()) && Arrays.equals(existing.content(), artifact.content()),
                    "retry artifact differs from existing immutable content");
        }
    }

    private static List<Artifact> validateArtifacts(TenantId tenant, List<Artifact> requested) {
        var staged = List.copyOf(Objects.requireNonNull(requested, "stagedArtifacts"));
        require(staged.size() <= 64, "too many staged input artifacts");
        var refs = new HashSet<ArtifactReference>();
        long bytes = 0;
        for (Artifact artifact : staged) {
            bytes += artifact.content().length;
            require(tenant.equals(artifact.tenantId()) && refs.add(artifact.reference())
                            && artifact.contentHash().equals(hash(artifact.content())),
                    "staged artifact tenant, reference or content hash is invalid");
        }
        require(bytes <= 64L * 1024 * 1024, "staged input artifacts exceed the transaction bound");
        return staged.stream().sorted(Comparator.comparing(value -> value.reference().value())).toList();
    }

    private Artifact acceptanceReceipt(BuildSession session, List<Artifact> staged) {
        try {
            var identity = List.of("factory.agent-pack-production-acceptance.v1", session.tenantId().value(),
                    session.buildSessionId().value(), session.projectId().value(), session.requestIdempotencyKey(),
                    session.createdBy(), session.requirementsArtifactRef().value(), session.requirementsHash().sha256(),
                    session.selectedManagerWorkerBinding().orElseThrow(), session.selectedCodingWorkerBinding().orElseThrow(),
                    Integer.toString(session.maxRepairRounds()), session.deadlineAt().toString(),
                    staged.stream().map(value -> List.of(value.reference().value(), value.contentHash().sha256(), value.mediaType())).toList());
            byte[] bytes = mapper.writeValueAsBytes(identity);
            String key = hash((session.tenantId().value() + "\n" + session.requestIdempotencyKey())
                    .getBytes(StandardCharsets.UTF_8)).sha256();
            return new Artifact(session.tenantId(), new ArtifactReference("artifact:production-acceptance:" + key),
                    hash(bytes), "application/json", bytes);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("production acceptance identity could not be encoded", failure);
        }
    }

    private static boolean sameAcceptance(BuildSession a, BuildSession b) {
        return a.tenantId().equals(b.tenantId()) && a.buildSessionId().equals(b.buildSessionId())
                && a.projectId().equals(b.projectId()) && a.productLineId().equals(b.productLineId())
                && a.requestIdempotencyKey().equals(b.requestIdempotencyKey()) && a.createdBy().equals(b.createdBy())
                && a.requirementsArtifactRef().equals(b.requirementsArtifactRef()) && a.requirementsHash().equals(b.requirementsHash())
                && a.selectedManagerWorkerBinding().equals(b.selectedManagerWorkerBinding())
                && a.selectedCodingWorkerBinding().equals(b.selectedCodingWorkerBinding())
                && a.maxRepairRounds() == b.maxRepairRounds() && a.deadlineAt().equals(b.deadlineAt());
    }

    private static boolean sameAttempt(WorkerRunRecord a, WorkerRunRecord b) {
        return a.tenantId().equals(b.tenantId()) && a.buildSessionId().equals(b.buildSessionId())
                && a.workOrderId().equals(b.workOrderId()) && a.workerRunId().equals(b.workerRunId())
                && a.attemptNo() == b.attemptNo() && a.workerBindingId().equals(b.workerBindingId())
                && a.workerAdapterVersion().equals(b.workerAdapterVersion())
                && a.workerCapabilitySnapshot().equals(b.workerCapabilitySnapshot())
                && a.operationId().equals(b.operationId()) && a.deadlineAt().equals(b.deadlineAt())
                && a.createdAt().equals(b.createdAt());
    }

    private static <T> T transaction(Connection connection, JdbcPersistenceSupport.SqlFunction<Void, T> body) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = body.apply(null);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static ContentHash hash(byte[] bytes) {
        try { return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw invalid(reason);
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("PRODUCTION_WORK_PREPARATION_CONFLICT: " + reason);
    }
}
