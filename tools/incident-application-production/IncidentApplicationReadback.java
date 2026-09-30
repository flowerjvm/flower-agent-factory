import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.certification.*;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.application.verification.ActionRuntimeVerificationEvidenceOwner;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.infrastructure.certification.JacksonCertificationArtifactCodec;
import io.github.flowerjvm.factory.infrastructure.incidentapplication.tool.DockerIncidentApplicationProductionTool;
import io.github.flowerjvm.factory.infrastructure.persistence.*;
import io.github.flowerjvm.factory.infrastructure.verification.*;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Read-only, bounded operator query/export. No Spring, migrations, Actions, product/worker execution or Docker calls. */
public final class IncidentApplicationReadback {
    private static final ContentHash FIXTURE_HASH = new ContentHash("6a2ae8440eb6883de2abdd91256219344ee49b70cc1275668e8a9a0f7053ecf8");
    private static final ArtifactReference FIXTURE_REF = new ArtifactReference("factory-verification/pr4/fixture-set/" + FIXTURE_HASH.sha256());
    private static final int MAX_OUTPUT = 65_536;

    public static void main(String[] args) {
        String stage = "ARGUMENTS";
        try {
            Options options = Options.parse(args);
            if (options.exportDirectory().isPresent()) {
                stage = "EXPORT_DESTINATION";
                ExportPlan.validateDestination(options.exportDirectory().orElseThrow());
            }
            stage = "PASSWORD_FILE";
            String password = readPassword(options.passwordFile());
            stage = "DATABASE_READ_ONLY";
            var source = new ReadOnlyDataSource(options.url(), options.user(), password);
            try (Connection ignored = source.getConnection()) { }
            stage = "FULL_READBACK";
            Readback readback = readback(options, source);
            stage = "OUTPUT_BOUND";
            byte[] output = mapper().writeValueAsBytes(readback.report());
            require(output.length <= MAX_OUTPUT);
            if (readback.export().isPresent()) {
                stage = "EXPORT_WRITE";
                readback.export().orElseThrow().write(options.exportDirectory().orElseThrow());
            }
            System.out.println(new String(output, StandardCharsets.UTF_8));
        } catch (Exception | LinkageError rejected) {
            // SQL, paths and parser messages can contain credentials or private artifact bytes.
            System.err.println("INCIDENT_APPLICATION_READBACK_FAILED:" + stage);
            System.exit(2);
        }
    }

    private static Readback readback(Options options, ReadOnlyDataSource source) throws Exception {
        Instant startedAt = Instant.now();
        Clock clock = Clock.systemUTC();
        ObjectMapper mapper = mapper();
        TenantId tenant = new TenantId(options.tenant());
        BuildSessionId sessionId = new BuildSessionId(options.session());
        ArtifactStore artifacts = new ReadOnlyArtifacts(new JdbcArtifactStore(source, clock));
        var sessions = new JdbcBuildSessionRepository(source);
        var workOrders = new JdbcWorkOrderRepository(source, mapper);
        var candidates = new JdbcCandidateVersionRepository(source);
        var verifications = new JdbcVerificationRunRepository(source);
        var verificationIntents = new JdbcVerificationDispatchIntentRepository(source);
        var certificationIntents = new JdbcCertificationDispatchIntentRepository(source);
        var codec = new JacksonCertificationArtifactCodec(mapper);
        var certifications = new JdbcCertificationRepository(source, codec);
        var runs = new JdbcRunStore(source, mapper);
        var workerArtifacts = new WorkerProtocolArtifacts(artifacts, new JacksonWorkerProtocolArtifactDecoder(mapper));
        var evidence = new ArtifactVerificationEvidenceValidator(artifacts, candidates, mapper,
                FIXTURE_REF, FIXTURE_HASH, Pr4MavenToolchainInstaller.EXPECTED_REFERENCE, Pr4MavenToolchainInstaller.EXPECTED_HASH);
        var fullComponents = new CertifiedComponentResolver(certifications,
                new ActionRuntimeCertificationEvidenceOwner(certificationIntents, runs),
                sessions, workOrders, candidates, verifications, verificationIntents, artifacts, workerArtifacts,
                evidence, new ActionRuntimeVerificationEvidenceOwner(verificationIntents, runs, candidates,
                        new JdbcVerificationActionDuplicateOwnerLookup(source)), codec, clock);
        // validate() reads only the persisted immutable artifact graph. These deliberately unusable
        // equipment paths are never used; accidental future subprocess use fails instead of finding Docker.
        Path unused = Path.of("incident-readback-equipment-disabled").toAbsolutePath().normalize();
        var tool = new DockerIncidentApplicationProductionTool(artifacts, fullComponents, unused, unused, unused,
                "incident-readback-process-execution-disabled", Duration.ofSeconds(5));
        var ledger = new JdbcIncidentApplicationLedger(source, mapper, runs, fullComponents, tool, clock);
        var decisions = new JdbcDecisionRepository(source);
        var points = new JdbcDecisionPointRepository(source, mapper);
        IncidentApplicationProduct product = ledger.find(tenant, sessionId).orElseThrow();
        BuildSession session = sessions.find(tenant, sessionId).orElseThrow();
        require(product.order().tenantId().equals(tenant) && product.order().buildSessionId().equals(sessionId)
                && session.productLineId().equals(IncidentApplicationOrder.PRODUCT_LINE_ID)
                && session.projectId().equals(product.order().projectId())
                && session.deadlineAt().equals(product.order().deadlineAt()));
        // A persisted window without its canonical SUCCEEDED Action is not renewal authority.
        // This shared ledger call deliberately fails closed in the commit-to-owner crash gap.
        Optional<RenewalProof> renewal = readRenewal(ledger, product, points, runs, artifacts);
        Instant effectiveDeadline = ledger.executionDeadline(product);
        require(effectiveDeadline.equals(renewal.map(proof -> proof.renewal().deadlineAt()).orElse(product.order().deadlineAt())));
        var component = fullComponents.resolve(tenant, product.order().component());
        require(component.reference().equals(product.order().component()));
        boolean fullProduct = product.product() != null && product.verification() != null && product.verification().passed();
        if (fullProduct) tool.validate(product.workOrder(), product.product(), product.verification());
        var report = new TreeMap<String,Object>();
        report.put("schemaVersion", "factory.incident-application.readback.v1");
        report.put("status", "STATUS_READBACK_PASSED");
        report.put("tenantId", tenant.value()); report.put("buildSessionId", sessionId.value());
        report.put("projectId", product.order().projectId().value()); report.put("variant", product.order().variant().name());
        report.put("productStatus", product.status().name()); report.put("productVersion", product.version());
        report.put("sessionStatus", session.status().name()); report.put("sessionPhase", session.currentPhase().name());
        report.put("sessionVersion", session.version()); report.put("deadlineAt", product.order().deadlineAt().toString());
        report.put("originalDeadlineAt", product.order().deadlineAt().toString());
        report.put("effectiveDeadlineAt", effectiveDeadline.toString());
        renewal.ifPresent(proof -> report.put("reviewRenewal", renewalReport(proof)));
        report.put("componentCertificationId", component.certification().certificationId().value());
        report.put("componentCertificationStatus", component.certification().status().name());
        report.put("componentCertificationVersion", component.certification().version());
        report.put("componentCandidateHash", product.order().component().candidateHash().sha256());
        report.put("componentCertification", lock(product.order().component().certificationManifest()));
        report.put("wholeProductGraphVerified", fullProduct);
        report.put("freshWholeProductExecution", "NOT_RUN_READ_ONLY_GRAPH_REVALIDATION");
        report.put("productionAlgorithm", product.workOrder().algorithmId());
        report.put("workOrder", lock(product.workOrder().workOrder()));
        report.put("requirements", lock(product.workOrder().requirements()));
        report.put("blueprint", lock(product.workOrder().blueprint()));
        report.put("moduleCatalog", lock(product.workOrder().moduleCatalog()));
        report.put("buildPolicy", lock(product.workOrder().policy()));
        if (product.status() == IncidentApplicationProduct.Status.REVIEW
                || product.status() == IncidentApplicationProduct.Status.RELEASED) {
            ledger.requirePriorStages(tenant, sessionId, IncidentApplicationIntent.Stage.RELEASE);
            var completedStages = new ArrayList<Map<String,Object>>();
            for (var stage : List.of(IncidentApplicationIntent.Stage.BUILD, IncidentApplicationIntent.Stage.VERIFY)) {
                var intent = ledger.stageIntent(tenant, sessionId, stage).orElseThrow();
                completedStages.add(Map.of("stage", stage.name(), "operationId", intent.operationId(),
                        "actionRunId", intent.actionRunId(), "status", intent.status().name()));
            }
            report.put("productionOwnersVerified", true);
            report.put("completedProductionStages", completedStages);
        }
        if (product.stableCode() != null) report.put("stableCode", product.stableCode());
        if (product.product() != null) {
            report.put("candidate", lock(product.product().candidate())); report.put("billOfMaterials", lock(product.product().billOfMaterials()));
            report.put("bundle", lock(product.product().bundle())); report.put("buildEvidence", lock(product.product().buildEvidence()));
        }
        if (product.verification() != null) {
            report.put("verificationPassed", product.verification().passed());
            report.put("verificationCode", product.verification().stableCode());
            report.put("wholeProductVerification", lock(product.verification().report()));
            report.put("fixtureSuite", lock(product.verification().fixtureSuite()));
        }
        DecisionPoint observedPoint = null;
        if (product.decisionPointId() != null) {
            var point = points.find(tenant, product.decisionPointId()).orElseThrow();
            observedPoint = point;
            require(fullProduct);
            checkReviewSubject(product, session, point, artifacts, ledger);
            report.put("review", Map.of("decisionPointId", point.decisionPointId().value(), "pointStatus", point.status().name(),
                    "pointVersion", point.version(), "subjectVersion", point.subjectVersion(), "subjectHash", point.subjectHash().sha256(),
                    "subject", lock(product.releaseSubject()), "dueAt", point.dueAt().toString(),
                    "approvalRequired", point.status() == DecisionPointStatus.OPEN,
                    "currentlyWithinDeadline", clock.instant().isBefore(point.dueAt())));
        }
        Optional<ExportPlan> export = Optional.empty();
        if (product.status() == IncidentApplicationProduct.Status.RELEASED) {
            // Shared full released-product authority joins canonical certificate/release documents,
            // the exact human Decision, current component, COMPLETED intent, SUCCEEDED Action and session.
            product = requireReleased(ledger, sessions, runs, artifacts, points, decisions, fullComponents, tool, clock, tenant, sessionId);
            report.put("status", "RELEASED_FULL_READBACK_PASSED");
            report.put("productCertification", lock(product.productCertification()));
            report.put("releaseManifest", lock(product.releaseManifest()));
            report.put("releaseActionRunId", product.releaseActionRunId());
            report.put("releaseOperationId", product.activeOperationId());
            if (options.exportDirectory().isPresent()) export = Optional.of(prepareExport(product, artifacts, renewal));
        } else require(options.exportDirectory().isEmpty());
        // Bounded observation, not an atomic snapshot or permanent certification lease.
        require(ledger.find(tenant, sessionId).orElseThrow().equals(product));
        require(sessions.find(tenant, sessionId).orElseThrow().equals(session));
        if (observedPoint != null) require(points.find(tenant, observedPoint.decisionPointId()).orElseThrow().equals(observedPoint));
        require(readRenewal(ledger, product, points, runs, artifacts).equals(renewal));
        require(ledger.executionDeadline(product).equals(effectiveDeadline));
        require(certifications.find(tenant, component.certification().certificationId()).orElseThrow().equals(component.certification()));
        require(component.certification().status() == CertificationStatus.CERTIFIED
                && component.certification().expiresAt().map(expiry -> clock.instant().isBefore(expiry)).orElse(true));
        report.put("databaseReadOnlyVerified", true); report.put("verifiedReadOnlyConnections", source.connections.get());
        report.put("startedAt", startedAt.toString()); report.put("completedAt", Instant.now().toString());
        report.put("scope", "factory-shipment-readback-not-deployment");
        if (export.isPresent()) {
            ExportPlan plan = export.orElseThrow();
            plan.add("provenance/readback.json", mapper.writeValueAsBytes(report), "derived:bounded-current-readback");
            byte[] index = plan.seal(mapper);
            report.put("handoff", Map.of("status", "HANDOFF_EXPORT_PASSED", "indexFile", ExportPlan.INDEX,
                    "indexSha256", IncidentApplicationArtifacts.hash(index), "fileCount", plan.files.size()));
        }
        return new Readback(report, export);
    }

    // Shared production authority, not a parallel ad-hoc release shortcut.
    private static IncidentApplicationProduct requireReleased(IncidentApplicationLedger ledger, BuildSessionRepository sessions,
            JdbcRunStore runs, ArtifactStore artifacts, JdbcDecisionPointRepository points, JdbcDecisionRepository decisions,
            CertifiedAgentComponentReadGate components, IncidentApplicationProductionTool tool, Clock clock,
            TenantId tenant, BuildSessionId session) {
        return new IncidentApplicationReleasedReadGate(ledger, sessions, points, decisions, artifacts, runs,
                components, tool).resolve(tenant, session);
    }

    static void checkReviewSubject(IncidentApplicationProduct product, BuildSession session, DecisionPoint point,
            ArtifactStore artifacts, IncidentApplicationLedger ledger) {
        checkSubjectContract(product, point);
        require(point.decisionPointId().equals(product.decisionPointId())
                && session.deadlineAt().equals(product.order().deadlineAt())
                && point.dueAt().equals(ledger.executionDeadline(product)));
        Artifact subject = IncidentApplicationDecisionAuthority.subject(product, point.subjectVersion());
        require(IncidentApplicationArtifacts.lock(subject).equals(product.releaseSubject()));
        require(Arrays.equals(subject.content(), IncidentApplicationArtifacts.require(artifacts, product.order().tenantId(), product.releaseSubject()).content()));
        if (product.status() == IncidentApplicationProduct.Status.REVIEW) {
            require(point.subjectVersion() == ledger.inspectedVersion(product, product.version())
                    && session.status() == BuildSessionStatus.WAITING_RELEASE_REVIEW
                    && session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW);
        }
    }

    private static void checkSubjectContract(IncidentApplicationProduct product, DecisionPoint point) {
        require(point.tenantId().equals(product.order().tenantId()) && point.buildSessionId().equals(product.order().buildSessionId())
                && point.type().equals(IncidentApplicationDecisionAuthority.DECISION_TYPE)
                && point.subjectType().equals(IncidentApplicationDecisionAuthority.SUBJECT_TYPE)
                && point.subjectId().equals(product.order().buildSessionId().value())
                && point.optionsSchemaId().equals(IncidentApplicationDecisionAuthority.OPTIONS_SCHEMA_ID)
                && point.requiredPermissions().equals(Set.of(IncidentApplicationDecisionAuthority.REQUIRED_PERMISSION))
                && point.minimumApprovers() == 1 && point.policySnapshotRef().equals(product.workOrder().policy().reference())
                && point.subjectHash().equals(product.releaseSubject().hash())
                && point.questionArtifactRef().equals(product.releaseSubject().reference()));
    }

    static Optional<RenewalProof> readRenewal(IncidentApplicationLedger ledger, IncidentApplicationProduct product,
            DecisionPointRepository points, RunStore runs, ArtifactStore artifacts) {
        var renewal = ledger.reviewRenewal(product.order().tenantId(), product.order().buildSessionId());
        if (renewal.isEmpty()) return Optional.empty();
        var value = renewal.orElseThrow();
        var owner = runs.find(value.actionRunId()).orElseThrow();
        // Recheck the independently observed Action too; never serialize an unchecked or different owner.
        IncidentApplicationReviewRenewalAction.requireOwner(owner, product, value);
        require(owner.status() == ActionRunStatus.SUCCEEDED && IncidentApplicationReviewRenewalAction.result(value).equals(owner.result())
                && value.decisionPointId().equals(product.decisionPointId()) && product.version() >= value.reviewedProductVersion());
        var previous = points.find(value.tenantId(), value.previousDecisionPointId()).orElseThrow();
        checkSubjectContract(product, previous);
        require(previous.decisionPointId().equals(value.previousDecisionPointId())
                && previous.status() == DecisionPointStatus.OPEN && previous.version() == 0
                && previous.subjectVersion() == value.inspectedVersion() && previous.dueAt().equals(value.originalDeadlineAt()));
        Artifact evidence = IncidentApplicationArtifacts.require(artifacts, value.tenantId(), IncidentApplicationArtifacts.lock(value.evidence()));
        require(evidence.mediaType().equals("application/json") && Arrays.equals(evidence.content(), value.evidence().content()));
        return Optional.of(new RenewalProof(value, previous, owner));
    }

    static Map<String,Object> renewalReport(RenewalProof proof) {
        var value = proof.renewal();
        var report = new TreeMap<String,Object>();
        report.put("evidence", lock(IncidentApplicationArtifacts.lock(value.evidence())));
        report.put("previousDecisionPointId", value.previousDecisionPointId().value());
        report.put("decisionPointId", value.decisionPointId().value());
        report.put("inspectedVersion", value.inspectedVersion()); report.put("reviewedProductVersion", value.reviewedProductVersion());
        report.put("originalDeadlineAt", value.originalDeadlineAt().toString()); report.put("openedAt", value.openedAt().toString());
        report.put("effectiveDeadlineAt", value.deadlineAt().toString()); report.put("subject", lock(value.subject()));
        report.put("actionRunId", proof.owner().runId()); report.put("actionStatus", proof.owner().status().name());
        report.put("canonicalOwnerVerified", true); report.put("scope", "same-inspected-product-review-window-not-approval");
        return Collections.unmodifiableMap(report);
    }

    static Map<String,Object> renewalActionProjection(RenewalProof proof) {
        var owner = proof.owner();
        require(owner.status() == ActionRunStatus.SUCCEEDED && IncidentApplicationReviewRenewalAction.result(proof.renewal()).equals(owner.result()));
        var projection = new TreeMap<String,Object>();
        projection.put("schemaVersion", "factory.incident-application.review-renewal-action-readback.v1");
        projection.put("scope", "validated-public-action-projection-not-authority-snapshot");
        projection.put("actionRunId", owner.runId()); projection.put("actionId", owner.actionId());
        projection.put("tenantId", proof.renewal().tenantId().value()); projection.put("buildSessionId", proof.renewal().buildSessionId().value());
        projection.put("status", owner.status().name()); projection.put("version", owner.version());
        projection.put("requestChannel", owner.requestChannel().name()); projection.put("proposerType", owner.proposerType().name());
        projection.put("resultCode", owner.result().code()); projection.put("createdAt", owner.createdAt().toString());
        projection.put("updatedAt", owner.updatedAt().toString()); projection.put("canonicalOwnerVerified", true);
        projection.put("evidence", lock(IncidentApplicationArtifacts.lock(proof.renewal().evidence())));
        projection.put("decisionPointId", proof.renewal().decisionPointId().value()); projection.put("subject", lock(proof.renewal().subject()));
        return Collections.unmodifiableMap(projection);
    }

    private static Map<String,Object> previousPointProjection(RenewalProof proof) {
        var point = proof.previousPoint(); var projection = new TreeMap<String,Object>();
        projection.put("schemaVersion", "factory.incident-application.previous-review-point-readback.v1");
        projection.put("scope", "preserved-expired-review-point-not-current-approval");
        projection.put("decisionPointId", point.decisionPointId().value()); projection.put("tenantId", point.tenantId().value());
        projection.put("buildSessionId", point.buildSessionId().value()); projection.put("type", point.type());
        projection.put("status", point.status().name()); projection.put("version", point.version());
        projection.put("subjectType", point.subjectType()); projection.put("subjectId", point.subjectId());
        projection.put("subjectVersion", point.subjectVersion()); projection.put("subjectHash", point.subjectHash().sha256());
        projection.put("questionArtifactRef", point.questionArtifactRef().value()); projection.put("optionsSchemaId", point.optionsSchemaId());
        projection.put("requiredPermissions", List.copyOf(new TreeSet<>(point.requiredPermissions()))); projection.put("minimumApprovers", point.minimumApprovers());
        projection.put("policySnapshotRef", point.policySnapshotRef().value());
        projection.put("openedAt", point.openedAt().toString()); projection.put("dueAt", point.dueAt().toString());
        return projection;
    }

    static void addRenewalExport(ExportPlan plan, ArtifactStore artifacts, Optional<RenewalProof> renewal) {
        if (renewal.isEmpty()) return;
        var proof = renewal.orElseThrow(); var value = proof.renewal();
        Artifact evidence = IncidentApplicationArtifacts.require(artifacts, value.tenantId(), IncidentApplicationArtifacts.lock(value.evidence()));
        require(evidence.mediaType().equals("application/json") && Arrays.equals(evidence.content(), value.evidence().content()));
        plan.add("provenance/review-renewal.json", evidence.content(), evidence.reference().value());
        plan.add("provenance/previous-review-point.json", IncidentApplicationArtifacts.canonical(previousPointProjection(proof)),
                "derived:verified-decision-point:" + value.previousDecisionPointId().value());
        plan.add("provenance/review-renewal-action.json", IncidentApplicationArtifacts.canonical(renewalActionProjection(proof)),
                "derived:verified-action-public-projection:" + value.actionRunId());
    }

    record RenewalProof(IncidentApplicationReviewRenewal renewal, DecisionPoint previousPoint, ActionRun owner) { }

    private static ExportPlan prepareExport(IncidentApplicationProduct product, ArtifactStore artifacts, Optional<RenewalProof> renewal) {
        require(product.status() == IncidentApplicationProduct.Status.RELEASED);
        var plan = new ExportPlan(); TenantId tenant = product.order().tenantId();
        Map<String,CertificationArtifactLock> allowed = new TreeMap<>();
        allowed.put("application.zip", product.product().bundle());
        allowed.put("provenance/requirements.json", product.workOrder().requirements());
        allowed.put("provenance/blueprint.json", product.workOrder().blueprint());
        allowed.put("provenance/module-catalog.json", product.workOrder().moduleCatalog());
        allowed.put("provenance/build-policy.json", product.workOrder().policy());
        allowed.put("provenance/work-order.json", product.workOrder().workOrder());
        allowed.put("provenance/candidate.json", product.product().candidate());
        allowed.put("provenance/BOM.json", product.product().billOfMaterials());
        allowed.put("provenance/build-evidence.json", product.product().buildEvidence());
        allowed.put("provenance/whole-product-verification.json", product.verification().report());
        allowed.put("provenance/fixture-suite.json", product.verification().fixtureSuite());
        allowed.put("provenance/release-subject.json", product.releaseSubject());
        allowed.put("provenance/product-certification.json", product.productCertification());
        allowed.put("provenance/release-manifest.json", product.releaseManifest());
        for (var entry : allowed.entrySet()) {
            Artifact artifact = IncidentApplicationArtifacts.require(artifacts, tenant, entry.getValue());
            require(artifact.mediaType().equals(entry.getKey().equals("application.zip") ? "application/zip" : "application/json"));
            plan.add(entry.getKey(), artifact.content(), artifact.reference().value());
        }
        addRenewalExport(plan, artifacts, renewal);
        String readme = """
                # 출고된 장애 조사 앱

                제품형: %s
                생산 주문: %s
                번들 SHA-256: %s

                application.zip은 이미 독립 검사·정확한 사람 승인·완성품 인증·출고를 통과한
                로컬 데모 애플리케이션입니다. 이 폴더 복사는 새 인증이나 배포가 아닙니다.

                실행하려면 새 product 디렉터리에 application.zip을 풀고, 그 안의 README.md와
                run-local.ps1을 사용하세요. Docker 로컬 실행 환경은 소비자가 준비합니다.
                기존 디렉터리를 덮어쓰거나 인증 원본을 수정하지 마세요.

                ```powershell
                Expand-Archive -LiteralPath .\\application.zip -DestinationPath .\\product
                Set-Location .\\product
                .\\run-local.ps1
                ```

                BASIC은 이력을 저장하지 않습니다. HISTORY는 제품 실행용 데이터 경로를 별도로
                보관하며 최대 100건을 저장합니다. 정확한 포트·데이터 경로·중지 사용법은
                번들 안의 README.md를 따르세요. 규칙 기반 조사 제품이며 LLM 대화나 자동 수정은 없습니다.

                handoff-index.json은 모든 payload의 hash/size/origin을 기록하며 마지막에 작성됩니다.
                누락·변경·추가 파일이나 실패한 내보내기 결과를 완료된 인계로 소비하지 마세요.
                provenance는 고정된 명시 allowlist이며 전체 Factory/Worker/credential 저장소를
                복사하지 않습니다. 현재 component 상태를 재확인한 한 시점의 인계이지 영구 인증
                보증이나 자체 포함된 오프라인 인증 재실행 환경은 아닙니다.
                Factory 책임은 출고 인계까지입니다. 서비스 배포·상시 운영은 포함하지 않습니다.
                """.formatted(product.order().variant(), product.order().buildSessionId().value(), product.product().bundle().hash().sha256());
        plan.add("README.md", readme.getBytes(StandardCharsets.UTF_8), "derived:released-application-handoff");
        return plan;
    }

    private record Readback(Map<String,Object> report, Optional<ExportPlan> export) { }
    private static Map<String,Object> lock(CertificationArtifactLock lock) { return IncidentApplicationArtifacts.lockMap(lock); }

    static final class ExportPlan {
        static final String INDEX = "handoff-index.json";
        private static final Set<String> PAYLOADS = Set.of("application.zip", "README.md", "provenance/requirements.json",
                "provenance/blueprint.json", "provenance/module-catalog.json", "provenance/build-policy.json",
                "provenance/work-order.json", "provenance/candidate.json", "provenance/BOM.json", "provenance/build-evidence.json",
                "provenance/whole-product-verification.json", "provenance/fixture-suite.json", "provenance/release-subject.json",
                "provenance/product-certification.json", "provenance/release-manifest.json", "provenance/readback.json",
                "provenance/review-renewal.json", "provenance/previous-review-point.json", "provenance/review-renewal-action.json");
        private static final long MAX_TOTAL = 32L * 1024 * 1024;
        final TreeMap<String,ExportFile> files = new TreeMap<>();
        private final Set<String> folded = new HashSet<>();
        private long total;
        private byte[] index;
        void add(String path, byte[] bytes, String origin) {
            relative(path);
            require(PAYLOADS.contains(path) && index == null && bytes != null && bytes.length <= (path.equals("application.zip") ? 24 * 1024 * 1024 : 256 * 1024)
                    && files.size() < 32 && !path.equals(INDEX) && origin != null && !origin.isBlank());
            if (!path.equals("application.zip")) require(new SecretScanner().scan(path, bytes).isEmpty());
            require(!files.containsKey(path) && folded.add(path.toLowerCase(Locale.ROOT)));
            for (String existing : files.keySet()) require(!existing.startsWith(path + "/") && !path.startsWith(existing + "/"));
            total = Math.addExact(total, bytes.length); require(total <= MAX_TOTAL);
            files.put(path, new ExportFile(bytes.clone(), origin, IncidentApplicationArtifacts.hash(bytes)));
        }
        byte[] seal(ObjectMapper mapper) throws Exception {
            require(index == null && !files.isEmpty());
            var entries = new ArrayList<Map<String,Object>>();
            files.forEach((path,file) -> entries.add(Map.of("path",path,"sha256",file.hash(),"sizeBytes",file.bytes().length,"origin",file.origin())));
            index = mapper.writeValueAsBytes(Map.of("schemaVersion","factory.incident-application.handoff-index.v1",
                    "scope","released-application-snapshot-not-deployment","files",entries,"fileCount",files.size(),"totalBytes",total));
            require(index.length <= 65_536); return index.clone();
        }
        static void relative(String path) {
            require(path != null && path.length() <= 200 && !path.startsWith("/") && !path.endsWith("/") && !path.contains("\\") && !path.contains(":"));
            for (String segment : path.split("/",-1)) require(segment.matches("[A-Za-z0-9][A-Za-z0-9._-]*") && !segment.endsWith(".")
                    && !segment.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"));
        }
        static void ancestors(Path path) throws Exception {
            for (Path current = path; current != null; current = current.getParent()) {
                var attr = Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                require(attr.isDirectory() && !attr.isSymbolicLink() && !attr.isOther()
                        && current.toRealPath().equals(current.toAbsolutePath().normalize()));
            }
        }
        static void validateDestination(Path path) throws Exception {
            require(path != null && path.isAbsolute() && path.equals(path.normalize()) && path.getParent() != null
                    && path.getParent().getParent() != null && !Files.exists(path,LinkOption.NOFOLLOW_LINKS));
            for (Path part : path) require(!Set.of(".codex",".git",".ssh").contains(part.toString().toLowerCase(Locale.ROOT)));
            ancestors(path.getParent());
        }
        void write(Path destination) throws Exception {
            require(index != null); validateDestination(destination); Files.createDirectory(destination);
            Object rootKey = Files.readAttributes(destination,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
            for (var entry : files.entrySet()) {
                Path target = destination.resolve(entry.getKey());
                require(target.normalize().startsWith(destination));
                if (!target.getParent().equals(destination) && !Files.exists(target.getParent(),LinkOption.NOFOLLOW_LINKS)) {
                    require(target.getParent().getParent().equals(destination)); ancestors(destination); Files.createDirectory(target.getParent());
                }
                ancestors(target.getParent()); writeNew(target,entry.getValue().bytes());
            }
            verify(destination,false,rootKey);
            Path marker = destination.resolve(INDEX); Object markerKey = null;
            try {
                try (var channel = FileChannel.open(marker,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    markerKey = Files.readAttributes(marker,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
                    var buffer = ByteBuffer.wrap(index); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
                }
                checkFile(marker,index);
                verify(destination,true,rootKey);
            } catch (Exception failed) {
                // Preserve partial payloads. Remove only the completion marker when exact ownership is still provable.
                if (markerKey != null) {
                    try {
                        ancestors(destination);
                        var attrs = Files.readAttributes(marker,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                        require(Objects.equals(rootKey,Files.readAttributes(destination,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey())
                                && attrs.isRegularFile() && !attrs.isSymbolicLink() && !attrs.isOther() && markerKey.equals(attrs.fileKey()));
                        Files.delete(marker);
                    } catch (Exception cleanupFailed) { failed.addSuppressed(cleanupFailed); }
                }
                throw failed;
            }
        }
        private void verify(Path destination, boolean withIndex, Object rootKey) throws Exception {
            ancestors(destination);
            require(Objects.equals(rootKey,Files.readAttributes(destination,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()));
            var expected = new TreeSet<>(files.keySet()); if (withIndex) expected.add(INDEX);
            var actual = new TreeSet<String>();
            try (var paths = Files.walk(destination,2)) {
                for (Path path : paths.toList()) {
                    if (path.equals(destination)) continue;
                    var attr = Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    require(!attr.isSymbolicLink() && !attr.isOther());
                    if (attr.isDirectory()) require(path.equals(destination.resolve("provenance")));
                    else { require(attr.isRegularFile()); actual.add(destination.relativize(path).toString().replace('\\','/')); }
                }
            }
            require(actual.equals(expected));
            for (var entry : files.entrySet()) checkFile(destination.resolve(entry.getKey()),entry.getValue().bytes());
            if (withIndex) checkFile(destination.resolve(INDEX),index);
        }
        private static void writeNew(Path path, byte[] bytes) throws Exception {
            try (var channel = FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            checkFile(path,bytes);
        }
        private static void checkFile(Path path, byte[] expected) throws Exception {
            var before = Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(before.isRegularFile() && !before.isSymbolicLink() && !before.isOther() && before.size() == expected.length);
            try (var input = Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                require(Arrays.equals(input.readNBytes(expected.length+1),expected));
            }
            var after = Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            // Windows' BasicFileAttributes may not expose a file key. This is a bounded private-parent
            // copy/check, not a cross-process filesystem lease; preserve the platform's available identity.
            require(Objects.equals(before.fileKey(),after.fileKey()) && before.size() == after.size()
                    && before.creationTime().equals(after.creationTime()) && before.lastModifiedTime().equals(after.lastModifiedTime()));
        }
    }
    private record ExportFile(byte[] bytes,String origin,String hash) { }

    static ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS); }
    static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("INCIDENT_READBACK_REJECTED"); }
    static String readPassword(Path path) throws Exception {
        require(path.isAbsolute() && path.equals(path.normalize()));
        ExportPlan.ancestors(path.getParent());
        var before = Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(before.isRegularFile() && !before.isSymbolicLink() && !before.isOther() && before.size()>0 && before.size()<=4096);
        byte[] bytes;
        try (var channel = Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)); var input = Channels.newInputStream(channel)) {
            bytes = input.readNBytes(4097);
        }
        try {
            var after = Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(after.isRegularFile() && !after.isSymbolicLink() && !after.isOther() && bytes.length==before.size()
                    && before.size()==after.size() && Objects.equals(before.fileKey(),after.fileKey()) && before.lastModifiedTime().equals(after.lastModifiedTime()));
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.endsWith("\r\n")) value=value.substring(0,value.length()-2); else if(value.endsWith("\n")) value=value.substring(0,value.length()-1);
            require(!value.isEmpty() && !value.contains("\n") && !value.contains("\r") && !value.contains("\0") && value.charAt(0)!='\uFEFF');
            return value;
        } finally { Arrays.fill(bytes,(byte)0); }
    }
    record Options(String url,String user,Path passwordFile,String tenant,String session,Optional<Path> exportDirectory) {
        static Options parse(String[] args) {
            var required=Set.of("--db-url","--db-user","--password-file","--tenant","--session");
            require(args.length==10 || args.length==12); var values=new HashMap<String,String>();
            for(int i=0;i<args.length;i+=2) {
                String key=args[i],value=args[i+1];
                require((required.contains(key)||key.equals("--export-dir")) && value!=null && !value.isBlank() && value.length()<=2048
                        && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl) && values.putIfAbsent(key,value)==null);
            }
            require(values.keySet().containsAll(required)); String url=values.get("--db-url");
            require(url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/[A-Za-z0-9_][A-Za-z0-9_-]{0,62}"));
            require(values.get("--db-user").matches("[A-Za-z_][A-Za-z0-9_-]{0,62}"));
            for(String key:List.of("--tenant","--session")) require(values.get(key).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"));
            return new Options(url,values.get("--db-user"),Path.of(values.get("--password-file")),values.get("--tenant"),values.get("--session"),
                    Optional.ofNullable(values.get("--export-dir")).map(Path::of));
        }
    }
    private record ReadOnlyArtifacts(JdbcArtifactStore delegate) implements ArtifactStore {
        public ArtifactReference store(Artifact ignored) { throw new UnsupportedOperationException("READBACK_WRITE_FORBIDDEN"); }
        public Optional<Artifact> find(TenantId tenant,ArtifactReference ref) { return delegate.find(tenant,ref); }
    }
    static final class ReadOnlyDataSource implements DataSource {
        private final String url;
        private final Properties properties=new Properties();
        private final long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
        final AtomicInteger connections=new AtomicInteger();
        ReadOnlyDataSource(String url,String user,String password) {
            this.url=url; properties.setProperty("user",user); properties.setProperty("password",password);
            properties.setProperty("readOnly","true"); properties.setProperty("ApplicationName","factory-incident-application-readback");
            properties.setProperty("connectTimeout","5"); properties.setProperty("socketTimeout","15");
            properties.setProperty("options","-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=1000");
        }
        public Connection getConnection() throws SQLException {
            if(System.nanoTime()>=deadline || connections.incrementAndGet()>4096) throw new SQLException("READBACK_BOUND");
            Connection connection=DriverManager.getConnection(url,properties);
            try {
                connection.setReadOnly(true);
                try(var statement=connection.prepareStatement("SELECT current_setting('default_transaction_read_only'), current_setting('transaction_read_only')")) {
                    statement.setQueryTimeout(10);
                    try(var result=statement.executeQuery()) {
                        if(!connection.isReadOnly() || !result.next() || !"on".equals(result.getString(1)) || !"on".equals(result.getString(2)) || result.next())
                            throw new SQLException("READBACK_READ_ONLY_PROOF_FAILED");
                    }
                }
                return connection;
            } catch(SQLException|RuntimeException failed) { try{connection.close();}catch(SQLException close){failed.addSuppressed(close);}throw failed; }
        }
        public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("ALTERNATE_IDENTITY_FORBIDDEN");}
        public PrintWriter getLogWriter(){return null;}
        public void setLogWriter(PrintWriter ignored){throw new UnsupportedOperationException("DRIVER_LOGGING_FORBIDDEN");}
        public int getLoginTimeout(){return 5;}
        public void setLoginTimeout(int ignored){throw new UnsupportedOperationException("FIXED_TIMEOUT");}
        public Logger getParentLogger()throws SQLFeatureNotSupportedException{throw new SQLFeatureNotSupportedException();}
        public<T>T unwrap(Class<T> ignored)throws SQLException{throw new SQLException("UNWRAP_FORBIDDEN");}
        public boolean isWrapperFor(Class<?> ignored){return false;}
    }
}
