package io.github.flowerjvm.factory.infrastructure.verification;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPlan;
import io.github.flowerjvm.factory.application.production.AgentPackProductionService;
import io.github.flowerjvm.factory.application.production.MaintenanceProductionRecipe;
import io.github.flowerjvm.factory.application.verification.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.infrastructure.production.JacksonAgentPackProductionCodec;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Off-tick recorder of a complete source/evidence read, plus a small immutable-receipt reader.
 * A receipt proves a completed read of its exact immutable graph, not continuous detection of
 * physical database corruption. Final full verification remains a separate integrity boundary.
 * No source file, result manifest, diagnostic log or source manifest is loaded by the receipt reader.
 */
public final class ArtifactVerificationReviewEvidence
        implements VerificationReviewEvidenceRecorder, VerificationEvidenceValidator {
    public static final int MAX_RECEIPT_BYTES = 64 * 1024;
    private static final int MAX_SOURCE_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final String INVALID = "VERIFICATION_REVIEW_EVIDENCE_INVALID";
    private static final String REFERENCE_PREFIX = "artifact:verification-review-evidence:";
    private static final Set<String> OWNER_CONTEXT_KEYS = Set.of("actor.permissions", "resource.type", "resource.id");
    private final ArtifactStore artifacts;
    private final BuildSessionRepository sessions;
    private final CandidateVersionRepository candidates;
    private final VerificationRunRepository runs;
    private final VerificationDispatchIntentRepository intents;
    private final RunStore actionRuns;
    private final VerificationEvidenceValidator fullValidator;
    private final ObjectMapper mapper;
    private final JacksonAgentPackProductionCodec codec;
    private final Clock clock;

    public ArtifactVerificationReviewEvidence(ArtifactStore artifacts, BuildSessionRepository sessions,
            CandidateVersionRepository candidates, VerificationRunRepository runs,
            VerificationDispatchIntentRepository intents, RunStore actionRuns,
            VerificationEvidenceValidator fullValidator, ObjectMapper mapper) {
        this(artifacts, sessions, candidates, runs, intents, actionRuns, fullValidator, mapper, Clock.systemUTC());
    }

    public ArtifactVerificationReviewEvidence(ArtifactStore artifacts, BuildSessionRepository sessions,
            CandidateVersionRepository candidates, VerificationRunRepository runs,
            VerificationDispatchIntentRepository intents, RunStore actionRuns,
            VerificationEvidenceValidator fullValidator, ObjectMapper mapper, Clock clock) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.actionRuns = Objects.requireNonNull(actionRuns, "actionRuns");
        this.fullValidator = Objects.requireNonNull(fullValidator, "fullValidator");
        Objects.requireNonNull(mapper, "mapper");
        this.mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                        .maxStringLength(4096).maxNumberLength(20).build()).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.codec = new JacksonAgentPackProductionCodec(this.mapper);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Called only by the durable verification runner, never by a Flower tick or policy/guard. */
    @Override
    public Optional<CertificationArtifactLock> validateAndRecord(
            VerificationDispatchIntent intent, ActionRun action, VerificationRun run) {
        Objects.requireNonNull(intent, "intent"); Objects.requireNonNull(action, "action"); Objects.requireNonNull(run, "run");
        BuildSession session = sessions.find(run.tenantId(), run.buildSessionId()).orElseThrow(ArtifactVerificationReviewEvidence::invalid);
        require(session.tenantId().equals(run.tenantId()) && session.buildSessionId().equals(run.buildSessionId()));
        Optional<Artifact> planArtifact = planArtifact(session);
        // Historical orders and failed gates keep their existing Action output bytes.
        if (planArtifact.isEmpty() || run.status() != VerificationRunStatus.PASSED) return Optional.empty();
        Snapshot before = snapshot(run, intent, action, planArtifact.orElseThrow(), true);
        Artifact source = exact(run, before.candidate().sourceManifestRef(), MAX_SOURCE_MANIFEST_BYTES);
        require(fullValidator.isReviewEligible(run));
        // Source JSON and all mutable domain/claim fences must still describe the same check.
        Artifact afterSource = exact(run, before.candidate().sourceManifestRef(), MAX_SOURCE_MANIFEST_BYTES);
        require(source.contentHash().equals(afterSource.contentHash()));
        Snapshot after = snapshot(run, intent, action, requirePlan(before.session()), true);
        require(before.equals(after));
        byte[] bytes = bytes(receipt(before, source.contentHash()));
        require(bytes.length <= MAX_RECEIPT_BYTES);
        ContentHash hash = hash(bytes);
        var reference = new ArtifactReference(REFERENCE_PREFIX + hash.sha256());
        require(reference.equals(artifacts.store(new Artifact(run.tenantId(), reference, hash, "application/json", bytes))));
        Artifact stored = exact(run, reference, MAX_RECEIPT_BYTES);
        require(stored.contentHash().equals(hash));
        require(before.equals(snapshot(run, intent, action, requirePlan(before.session()), true)));
        return Optional.of(new CertificationArtifactLock(reference, hash));
    }

    /** Historical validity has no wall-clock expiry; current-operation deadline/cancellation is the caller's responsibility. */
    @Override
    public boolean isReviewEligible(VerificationRun run) {
        try {
            BuildSession session = sessions.find(run.tenantId(), run.buildSessionId()).orElseThrow(ArtifactVerificationReviewEvidence::invalid);
            require(session.tenantId().equals(run.tenantId()) && session.buildSessionId().equals(run.buildSessionId()));
            var plan = planArtifact(session);
            var intent = intents.findLatest(run.tenantId(), run.verificationRunId());
            var action = intent.flatMap(value -> actionRuns.find(value.actionRunId()));
            if (plan.isEmpty()) {
                // Once an owner declared a receipt, losing its production plan cannot downgrade to legacy.
                if (action.isPresent() && action.orElseThrow().result() != null
                        && VerificationReviewEvidenceOutput.lock(action.orElseThrow().result().output()).isPresent()) return false;
                return fullValidator.isReviewEligible(run);
            }
            Snapshot before = snapshot(run, intent.orElseThrow(ArtifactVerificationReviewEvidence::invalid),
                    action.orElseThrow(ArtifactVerificationReviewEvidence::invalid), plan.orElseThrow(), false);
            CertificationArtifactLock lock = VerificationReviewEvidenceOutput.lock(before.action().result().output())
                    .orElseThrow(ArtifactVerificationReviewEvidence::invalid);
            require(lock.reference().value().equals(REFERENCE_PREFIX + lock.hash().sha256()));
            Artifact stored = exact(run, lock.reference(), MAX_RECEIPT_BYTES);
            require(stored.contentHash().equals(lock.hash()));
            JsonNode value = mapper.readTree(stored.content());
            require(value != null && value.isObject() && value.path("sourceManifestHash").isTextual());
            var sourceManifestHash = new ContentHash(value.path("sourceManifestHash").textValue());
            // Compare the code-owned canonical bytes. JsonNode.equals distinguishes LongNode(2)
            // built from a Java long from IntNode(2) parsed from JSON, despite identical JSON.
            require(java.util.Arrays.equals(bytes(receipt(before, sourceManifestHash)), stored.content()));
            require(before.equals(snapshot(run, before.intent(), before.action(), requirePlan(before.session()), false)));
            return true;
        } catch (RuntimeException | java.io.IOException rejected) { return false; }
    }

    private Snapshot snapshot(VerificationRun run, VerificationDispatchIntent expectedIntent, ActionRun expectedAction,
                              Artifact planArtifact, boolean recording) {
        BuildSession session = sessions.find(run.tenantId(), run.buildSessionId()).orElseThrow(ArtifactVerificationReviewEvidence::invalid);
        CandidateVersion candidate = candidates.find(run.tenantId(), run.candidateId()).orElseThrow(ArtifactVerificationReviewEvidence::invalid);
        VerificationRun current = runs.find(run.tenantId(), run.verificationRunId()).orElseThrow(ArtifactVerificationReviewEvidence::invalid);
        VerificationDispatchIntent intent = intents.findLatest(run.tenantId(), run.verificationRunId()).orElseThrow(ArtifactVerificationReviewEvidence::invalid);
        ActionRun action = actionRuns.find(intent.actionRunId()).orElseThrow(ArtifactVerificationReviewEvidence::invalid);
        require(current.equals(run) && intent.equals(expectedIntent) && action.equals(expectedAction));
        require(run.status() == VerificationRunStatus.PASSED
                && run.disposition().filter(VerificationDisposition.REVIEW_ELIGIBLE::equals).isPresent()
                && run.terminalCode().filter("VERIFIED"::equals).isPresent()
                && run.resultManifestRef().isPresent() && run.resultManifestHash().isPresent()
                && !run.resultManifestHash().orElseThrow().equals(VerificationRun.LEGACY_RESULT_MANIFEST_HASH)
                && session.tenantId().equals(run.tenantId()) && session.buildSessionId().equals(run.buildSessionId())
                && session.productLineId().equals(ProductLineId.AGENT_PACK)
                && session.currentCandidateId().filter(candidate.candidateId()::equals).isPresent()
                && session.currentCandidateHash().filter(candidate.sourceHash()::equals).isPresent()
                && candidate.tenantId().equals(run.tenantId()) && candidate.candidateId().equals(run.candidateId())
                && candidate.buildSessionId().equals(run.buildSessionId()) && candidate.sourceHash().equals(run.candidateHash())
                && runs.findLatestForCandidate(run.tenantId(), run.buildSessionId(), run.candidateId(), run.candidateHash(),
                        run.gateProfile()).filter(run::equals).isPresent());
        AgentPackProductionPlan plan = codec.readPlan(planArtifact.content());
        new MaintenanceProductionRecipe(codec).validatePlan(plan);
        require(plan.tenantId().equals(session.tenantId()) && plan.buildSessionId().equals(session.buildSessionId())
                && plan.requirements().reference().equals(session.requirementsArtifactRef())
                && plan.requirements().hash().equals(session.requirementsHash())
                && session.selectedManagerWorkerBinding().filter(plan.manager().bindingId()::equals).isPresent()
                && session.selectedCodingWorkerBinding().filter(plan.coding().bindingId()::equals).isPresent()
                && plan.dependencyLock().reference().equals(candidate.dependencyLockRef())
                && plan.dependencyLock().hash().equals(candidate.dependencyLockHash())
                && plan.toolchainLock().reference().equals(candidate.toolchainLockRef())
                && plan.toolchainLock().hash().equals(candidate.toolchainLockHash())
                && plan.gateProfile().equals(run.gateProfile()));
        require(VerificationDispatchRunner.hasExactOwnerBinding(intent, action, run, candidate)
                && intent.operationId().equals(action.externalOperationId())
                && "factory-verifier".equals(action.userId()) && "factory-verifier".equals(action.requesterId())
                && action.requestChannel() == ActionRequestChannel.INTERNAL && action.proposerType() == ActionProposerType.SERVICE
                && ("verification:" + run.verificationRunId().value()).equals(action.traceId())
                && action.contextMetadata().keySet().equals(OWNER_CONTEXT_KEYS)
                && action.contextMetadata().get("actor.permissions") instanceof Collection<?> permissions
                && permissions.size() == 1 && permissions.contains(VerificationRunAction.PERMISSION));
        if (recording) {
            require(intent.status() == VerificationDispatchIntentStatus.RUNNING && intent.claimToken().isPresent()
                    && action.status() == ActionRunStatus.WAITING_EXTERNAL
                    && session.cancellationRequestedAt().isEmpty() && session.currentCertificationId().isEmpty()
                    && !session.status().isTerminal() && session.terminalCode().isEmpty()
                    && !clock.instant().isBefore(session.updatedAt()) && clock.instant().isBefore(session.deadlineAt())
                    && clock.instant().isBefore(intent.deadlineAt())
                    && intent.leaseUntil().filter(clock.instant()::isBefore).isPresent());
        } else {
            require(intent.status() == VerificationDispatchIntentStatus.COMPLETED
                    && intent.lastCode().filter(VerificationDispatchRunner.COMPLETED::equals).isPresent()
                    && action.status() == ActionRunStatus.SUCCEEDED
                    && VerificationDispatchRunner.exactTerminalResult(run, action.result()));
        }
        return new Snapshot(session, candidate, run, intent, action,
                new CertificationArtifactLock(planArtifact.reference(), planArtifact.contentHash()));
    }

    private ObjectNode receipt(Snapshot snapshot, ContentHash sourceManifestHash) {
        var session = snapshot.session(); var candidate = snapshot.candidate(); var run = snapshot.run();
        var intent = snapshot.intent(); var value = mapper.createObjectNode();
        value.put("schemaVersion", VerificationReviewEvidenceOutput.SCHEMA_VERSION);
        value.put("tenantId", run.tenantId().value()); value.put("buildSessionId", run.buildSessionId().value());
        value.put("projectId", session.projectId().value()); value.put("productLineId", session.productLineId().value());
        value.put("requestIdempotencyKey", session.requestIdempotencyKey()); value.put("createdBy", session.createdBy());
        value.put("sessionDeadlineAt", session.deadlineAt().toString()); value.put("maxRepairRounds", session.maxRepairRounds());
        value.put("repairRound", session.repairRound());
        value.put("requirementsRef", session.requirementsArtifactRef().value()); value.put("requirementsHash", session.requirementsHash().sha256());
        value.put("productionPlanRef", snapshot.plan().reference().value()); value.put("productionPlanHash", snapshot.plan().hash().sha256());
        value.put("candidateId", candidate.candidateId().value()); value.put("candidateHash", candidate.sourceHash().sha256());
        value.put("parentCandidateId", candidate.parentCandidateId().map(id -> id.value()).orElse(""));
        value.put("generationWorkOrderId", candidate.createdByWorkOrderId().value());
        value.put("sourceManifestRef", candidate.sourceManifestRef().value()); value.put("sourceManifestHash", sourceManifestHash.sha256());
        value.put("dependencyLockRef", candidate.dependencyLockRef().value()); value.put("dependencyLockHash", candidate.dependencyLockHash().sha256());
        value.put("toolchainLockRef", candidate.toolchainLockRef().value()); value.put("toolchainLockHash", candidate.toolchainLockHash().sha256());
        value.put("verificationRunId", run.verificationRunId().value()); value.put("verificationVersion", run.version());
        value.put("gateProfile", run.gateProfile()); value.put("fixtureSetHash", run.fixtureSetHash().sha256());
        value.put("resultManifestRef", run.resultManifestRef().orElseThrow().value());
        value.put("resultManifestHash", run.resultManifestHash().orElseThrow().sha256());
        value.put("verificationActionRunId", intent.actionRunId()); value.put("verificationOperationId", intent.operationId());
        value.put("verificationProposalVersion", intent.expectedVerificationRunVersion());
        value.put("verificationAttemptTokenHash", intent.attemptTokenHash());
        value.put("verificationDuplicateKey", snapshot.action().duplicateKey());
        return value;
    }

    private Optional<Artifact> planArtifact(BuildSession session) {
        var reference = AgentPackProductionService.planReference(session.tenantId(), session.buildSessionId());
        return artifacts.find(session.tenantId(), reference).map(artifact -> validateArtifact(
                session.tenantId(), reference, artifact, JacksonAgentPackProductionCodec.MAX_PLAN_BYTES));
    }
    private Artifact requirePlan(BuildSession session) { return planArtifact(session).orElseThrow(ArtifactVerificationReviewEvidence::invalid); }
    private Artifact exact(VerificationRun run, ArtifactReference reference, int maximum) {
        return validateArtifact(run.tenantId(), reference, artifacts.find(run.tenantId(), reference)
                .orElseThrow(ArtifactVerificationReviewEvidence::invalid), maximum);
    }
    private static Artifact validateArtifact(io.github.flowerjvm.factory.contracts.ids.TenantId tenant,
            ArtifactReference reference, Artifact artifact, int maximum) {
        require(tenant.equals(artifact.tenantId()) && reference.equals(artifact.reference())
                && "application/json".equals(artifact.mediaType()));
        byte[] content = artifact.content();
        require(content.length <= maximum && hash(content).equals(artifact.contentHash()));
        return artifact;
    }
    private byte[] bytes(JsonNode value) {
        try { return mapper.writeValueAsBytes(value); } catch (java.io.IOException failure) { throw invalid(); }
    }
    private static ContentHash hash(byte[] bytes) {
        try { return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Snapshot(BuildSession session, CandidateVersion candidate, VerificationRun run,
                            VerificationDispatchIntent intent, ActionRun action, CertificationArtifactLock plan) {}
    private static void require(boolean allowed) { if (!allowed) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException(INVALID); }
}
