import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.certification.*;
import io.github.flowerjvm.factory.application.referenceassembly.*;
import io.github.flowerjvm.factory.application.verification.ActionRuntimeVerificationEvidenceOwner;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.VerificationResultManifest;
import io.github.flowerjvm.factory.infrastructure.certification.JacksonCertificationArtifactCodec;
import io.github.flowerjvm.factory.infrastructure.persistence.*;
import io.github.flowerjvm.factory.infrastructure.referenceassembly.JacksonReferenceAssemblyArtifactCodec;
import io.github.flowerjvm.factory.infrastructure.verification.*;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
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

/** Java 21 source-file probe. No Spring, migrations, Action submission, Worker, subprocess or fixture. */
public final class FactoryProductionReadback {
    // Matches FactoryActionRuntimeConfiguration's code-owned PR4 technical fixture lock.
    private static final ContentHash FIXTURE_HASH = new ContentHash(
            "6a2ae8440eb6883de2abdd91256219344ee49b70cc1275668e8a9a0f7053ecf8");
    private static final ArtifactReference FIXTURE_REF = new ArtifactReference(
            "factory-verification/pr4/fixture-set/" + FIXTURE_HASH.sha256());
    private static final int MAX_REVERSE = 100;
    private static final int MAX_OUTPUT_BYTES = 65_536;

    public static void main(String[] arguments) {
        String stage = "ARGUMENTS";
        try {
            var options = Options.parse(arguments);
            if (options.exportDirectory().isPresent()) {
                stage = "EXPORT_DESTINATION";
                ExportPlan.validateDestination(options.exportDirectory().orElseThrow());
            }
            stage = "PASSWORD_FILE";
            String password = readPassword(options.passwordFile());
            stage = "DATABASE_READ_ONLY";
            var dataSource = new ReadOnlyDataSource(options.url(), options.user(), password);
            // Force the server-side read-only proof even before constructing a repository.
            try (Connection ignored = dataSource.getConnection()) { }
            stage = "FULL_READBACK";
            Readback readback = readback(options, dataSource);
            stage = "OUTPUT_BOUND";
            byte[] output = mapper().writeValueAsBytes(readback.report());
            require(output.length <= MAX_OUTPUT_BYTES);
            if (readback.export().isPresent()) {
                stage = "EXPORT_WRITE";
                readback.export().orElseThrow().write(options.exportDirectory().orElseThrow());
            }
            System.out.println(new String(output, StandardCharsets.UTF_8));
        } catch (Exception | LinkageError rejected) {
            // SQL/parser/driver exceptions can contain credential, URL or immutable evidence bytes.
            // Deliberately never print exception messages, causes, stack traces or option values.
            String safeStage = rejected instanceof ProbeStageFailure staged ? staged.stage.name() : stage;
            System.err.println("PRODUCTION_READBACK_FAILED:" + safeStage);
            System.exit(2);
        }
    }

    private static Readback readback(Options options, ReadOnlyDataSource dataSource) throws Exception {
        Instant startedAt = Instant.now();
        Clock clock = Clock.systemUTC();
        ObjectMapper mapper = mapper();
        TenantId tenant = new TenantId(options.tenant());
        CertificationId certificationId = new CertificationId(options.certification());
        var artifacts = new ReadOnlyArtifacts(new JdbcArtifactStore(dataSource, clock));
        var sessions = new JdbcBuildSessionRepository(dataSource);
        var workOrders = new JdbcWorkOrderRepository(dataSource, mapper);
        var candidates = new JdbcCandidateVersionRepository(dataSource);
        var verifications = new JdbcVerificationRunRepository(dataSource);
        var verificationIntents = new JdbcVerificationDispatchIntentRepository(dataSource);
        var certificationIntents = new JdbcCertificationDispatchIntentRepository(dataSource);
        var certificationCodec = new JacksonCertificationArtifactCodec(mapper);
        var certifications = new JdbcCertificationRepository(dataSource, certificationCodec);
        var actionRuns = new JdbcRunStore(dataSource, mapper);
        var workerArtifacts = new WorkerProtocolArtifacts(artifacts, new JacksonWorkerProtocolArtifactDecoder(mapper));
        var fullEvidence = new ArtifactVerificationEvidenceValidator(artifacts, candidates, mapper,
                FIXTURE_REF, FIXTURE_HASH, Pr4MavenToolchainInstaller.EXPECTED_REFERENCE,
                Pr4MavenToolchainInstaller.EXPECTED_HASH);
        var fullComponents = new CertifiedComponentResolver(certifications,
                new ActionRuntimeCertificationEvidenceOwner(certificationIntents, actionRuns),
                sessions, workOrders, candidates, verifications, verificationIntents, artifacts, workerArtifacts,
                fullEvidence, new ActionRuntimeVerificationEvidenceOwner(verificationIntents, actionRuns, candidates,
                        new JdbcVerificationActionDuplicateOwnerLookup(dataSource)), certificationCodec, clock);
        Certification original = certifications.find(tenant, certificationId).orElseThrow();
        CertifiedAgentComponentRef reference = reference(original);
        ResolvedCertifiedAgentComponent resolved = fullComponents.resolve(tenant, reference);
        require(resolved.certification().equals(original));

        var assemblyCodec = new JacksonReferenceAssemblyArtifactCodec(mapper);
        // Constructor computes code-owned policy locks only. Never call provisionTenant/stageRequirement.
        var catalog = new ReferenceAssemblyProductLineCatalog(artifacts, assemblyCodec);
        var assemblies = new JdbcReferenceAssemblyRepository(dataSource);
        var releases = new JdbcReferenceAssemblyReleaseDispatchIntentRepository(dataSource);
        var fullReleases = new ReleasedReferenceAssemblyResolver(assemblies, sessions, releases,
                actionRuns, artifacts, assemblyCodec, fullComponents, catalog.admissionPolicies());

        var reverseRows = assemblies.findReleasedByComponentCertification(tenant, certificationId, MAX_REVERSE + 1);
        require(reverseRows.size() <= MAX_REVERSE); // Never call a truncated reverse list complete.
        var checkedReleases = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : reverseRows) {
            var release = fullReleases.resolve(tenant, row.referenceAssemblyId());
            require(release.component().reference().equals(reference));
            checkedReleases.put(row.referenceAssemblyId().value(), releaseMetadata(release));
        }
        Optional<Map<String, Object>> selected = Optional.empty();
        Optional<ResolvedReleasedReferenceAssembly> selectedRelease = Optional.empty();
        if (options.assembly().isPresent()) {
            var id = new ReferenceAssemblyId(options.assembly().orElseThrow());
            ResolvedReleasedReferenceAssembly release;
            try {
                release = fullReleases.resolve(tenant, id);
            } catch (ReleasedReferenceAssemblyResolutionException rejected) {
                // Typed gate rejection only; never inspect or relay the underlying private reason.
                throw new ProbeStageFailure(ProbeStage.SELECTED_RELEASE);
            }
            require(release.component().reference().equals(reference));
            require(checkedReleases.containsKey(id.value()));
            selected = Optional.of(releaseMetadata(release));
            selectedRelease = Optional.of(release);
        }
        Map<String, Object> source = sourceSummary(artifacts, tenant, resolved, mapper);
        Optional<ExportPlan> export = Optional.empty();
        if (options.exportDirectory().isPresent()) {
            export = Optional.of(prepareExport(artifacts, tenant, selectedRelease.orElseThrow(), mapper));
        }
        // Full gates prove a read interval, not a durable lease. Fail if the selected certification
        // changes while this bounded traversal runs; downstream consumers must still revalidate.
        Certification observedAfter = certifications.find(tenant, certificationId).orElseThrow();
        require(observedAfter.equals(original) && observedAfter.status() == CertificationStatus.CERTIFIED
                && observedAfter.expiresAt().map(expiry -> clock.instant().isBefore(expiry)).orElse(true));
        var report = new TreeMap<String, Object>();
        report.put("schemaVersion", "factory.production-readback.v1");
        report.put("status", "FULL_READBACK_PASSED");
        report.put("startedAt", startedAt.toString());
        report.put("completedAt", Instant.now().toString());
        report.put("tenantId", tenant.value());
        report.put("databaseReadOnlyVerified", true);
        report.put("verifiedReadOnlyConnections", dataSource.connections.get());
        report.put("certificationId", certificationId.value());
        report.put("certificationVersion", original.version());
        report.put("certificationStatus", original.status().name());
        report.put("candidateId", reference.candidateId().value());
        report.put("sourceHash", reference.candidateHash().sha256());
        report.put("sourceManifest", lock(reference.sourceManifest()));
        report.put("certificationManifest", lock(reference.certificationManifest()));
        report.put("certificationEvidence", lock(reference.certificationEvidence()));
        report.put("inputLockManifest", lock(reference.inputLockManifest()));
        report.put("compatibilityDescriptor", lock(reference.compatibilityDescriptor()));
        report.put("verificationRunId", reference.verificationRunId().value());
        report.put("verificationResultManifest", lock(reference.verificationResultManifest()));
        report.put("gateProfile", original.inputLock().gateProfile());
        report.put("sourceIntegrity", source);
        report.put("reverseResultIds", List.copyOf(checkedReleases.keySet()));
        report.put("reverseResults", List.copyOf(checkedReleases.values()));
        selected.ifPresent(value -> report.put("selectedAssembly", value));
        if (export.isPresent()) {
            ExportPlan plan = export.orElseThrow();
            plan.add("provenance/full-readback.json", mapper.writeValueAsBytes(report), "derived:current-full-readback");
            byte[] index = plan.index(mapper);
            plan.seal(index);
            report.put("handoff", Map.of("status", "HANDOFF_EXPORT_PASSED", "indexFile", "handoff-index.json",
                    "indexSha256", new OrdinalSourceTreeHasher().sha256(index).sha256(),
                    "fileCount", plan.files.size(), "scope", "released-snapshot-not-new-certified-product"));
        }
        return new Readback(report, export);
    }

    private record Readback(Map<String, Object> report, Optional<ExportPlan> export) { }

    private static CertifiedAgentComponentRef reference(Certification certification) {
        require(certification.status() == CertificationStatus.CERTIFIED);
        var input = certification.inputLock();
        return new CertifiedAgentComponentRef(CertifiedAgentComponentRef.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.COMPONENT_ROLE, input.productLineId(), input.artifactType(),
                certification.certificationId(), certification.certificationManifest().orElseThrow(),
                input.candidateId(), input.candidateHash(), input.sourceManifest(), certification.inputLockArtifact(),
                input.verificationRunId(), input.verificationResultManifest(), input.compatibilityDescriptor(),
                certification.certificationEvidence().orElseThrow(), input.certificationProfile());
    }

    private static Map<String, Object> sourceSummary(ArtifactStore artifacts, TenantId tenant,
            ResolvedCertifiedAgentComponent resolved, ObjectMapper mapper) throws Exception {
        var manifestArtifact = artifacts.find(tenant, resolved.reference().sourceManifest().reference()).orElseThrow();
        var hasher = new OrdinalSourceTreeHasher();
        require(manifestArtifact.content().length <= 4 * 1024 * 1024
                && manifestArtifact.contentHash().equals(resolved.reference().sourceManifest().hash())
                && hasher.sha256(manifestArtifact.content()).equals(resolved.reference().sourceManifest().hash()));
        var manifest = new CandidateSourceManifestReader(mapper).read(manifestArtifact.content());
        require(manifest.candidateId().equals(resolved.candidate().candidateId())
                && manifest.buildSessionId().equals(resolved.candidate().buildSessionId())
                && manifest.candidateHash().equals(resolved.reference().candidateHash()));
        var entries = new ArrayList<CandidateSourceEntry>();
        long byteCount = 0;
        for (var entry : manifest.files()) {
            var artifact = artifacts.find(tenant, entry.artifactRef()).orElseThrow();
            byte[] content = artifact.content();
            ContentHash actualHash = hasher.sha256(content);
            require(content.length == entry.sizeBytes() && actualHash.equals(entry.contentHash())
                    && actualHash.equals(artifact.contentHash()));
            byteCount = Math.addExact(byteCount, content.length);
            entries.add(new CandidateSourceEntry(entry.path(), entry.artifactRef(), actualHash, content.length));
        }
        ContentHash tree = hasher.hashEntries(entries);
        require(byteCount == manifest.totalBytes() && entries.size() == manifest.fileCount()
                && tree.equals(resolved.reference().candidateHash()));
        return Map.of("fileCount", entries.size(), "declaredBytes", manifest.totalBytes(),
                "observedBytes", byteCount, "allFileHashesMatch", true, "sourceTreeHash", tree.sha256());
    }

    private static Map<String, Object> releaseMetadata(ResolvedReleasedReferenceAssembly resolved) {
        var assembly = resolved.referenceAssembly();
        return Map.of("referenceAssemblyId", assembly.referenceAssemblyId().value(),
                "buildSessionId", assembly.buildSessionId().value(), "version", assembly.version(),
                "status", assembly.status().name(), "fullReadGatePassed", true,
                "releaseManifest", lock(resolved.releaseManifestArtifact()),
                "assemblyManifest", lock(assembly.assemblyManifest().orElseThrow()),
                "inspectionReport", lock(assembly.inspectionReport().orElseThrow()),
                "componentCertificationId", assembly.componentCertificationId().value(),
                "componentSourceHash", assembly.componentCandidateHash().sha256());
    }

    private static Map<String, String> lock(CertificationArtifactLock lock) {
        return Map.of("reference", lock.reference().value(), "sha256", lock.hash().sha256());
    }

    /** Whitelisted released snapshot only. Never traverses arbitrary artifact references or Worker inputs. */
    private static ExportPlan prepareExport(ArtifactStore artifacts, TenantId tenant,
            ResolvedReleasedReferenceAssembly released, ObjectMapper mapper) throws Exception {
        var component = released.component();
        var reference = component.reference();
        require(component.verificationRun().gateProfile().equals(MaintenanceInvestigationProductContract.GATE_PROFILE));
        var plan = new ExportPlan();
        byte[] sourceBytes = exportArtifact(plan, artifacts, tenant, "provenance/source-manifest.json", reference.sourceManifest());
        var source = new CandidateSourceManifestReader(mapper).read(sourceBytes);
        require(source.candidateHash().equals(reference.candidateHash())
                && source.candidateId().equals(reference.candidateId())
                && source.buildSessionId().equals(component.candidate().buildSessionId()));
        var entries = new ArrayList<CandidateSourceEntry>();
        long total = 0;
        for (var entry : source.files()) {
            // The demo is Java source, not a workspace export or an arbitrary artifact download.
            require(ExportPlan.allowedSource(entry.path()));
            byte[] bytes = exportArtifact(plan, artifacts, tenant, "product-source/" + entry.path(),
                    new CertificationArtifactLock(entry.artifactRef(), entry.contentHash()));
            require(bytes.length == entry.sizeBytes());
            total = Math.addExact(total, bytes.length);
            entries.add(new CandidateSourceEntry(entry.path(), entry.artifactRef(), entry.contentHash(), bytes.length));
        }
        require(total == source.totalBytes() && entries.size() == source.fileCount()
                && new OrdinalSourceTreeHasher().hashEntries(entries).equals(reference.candidateHash()));
        exportArtifact(plan, artifacts, tenant, "provenance/certification-manifest.json", reference.certificationManifest());
        exportArtifact(plan, artifacts, tenant, "provenance/certification-evidence.json", reference.certificationEvidence());
        exportArtifact(plan, artifacts, tenant, "provenance/compatibility-descriptor.json", reference.compatibilityDescriptor());
        exportArtifact(plan, artifacts, tenant, "provenance/release-manifest.json", released.releaseManifestArtifact());
        var release = released.releaseManifest();
        exportArtifact(plan, artifacts, tenant, "provenance/assembly-manifest.json", release.assemblyManifest());
        exportArtifact(plan, artifacts, tenant, "provenance/inspection-report.json", release.inspectionReport());
        exportArtifact(plan, artifacts, tenant, "provenance/assembly-requirement.json", release.requirement());
        exportArtifact(plan, artifacts, tenant, "provenance/consumer-contract.json", release.consumerContract());
        exportArtifact(plan, artifacts, tenant, "provenance/host-fixture.json", release.hostFixture());
        exportArtifact(plan, artifacts, tenant, "provenance/assembly-policy-snapshot.json", release.policySnapshot());
        exportArtifact(plan, artifacts, tenant, "provenance/product-contract.json", MaintenanceInvestigationProductContract.lock());
        exportArtifact(plan, artifacts, tenant, "provenance/product-requirements.txt", MaintenanceInvestigationProductContract.requirementsLock());
        exportArtifact(plan, artifacts, tenant, "provenance/api-signature-index.json", MaintenanceInvestigationProductContract.apiSignatureIndexLock());
        exportArtifact(plan, artifacts, tenant, "provenance/requirement-test-matrix.json", MaintenanceInvestigationProductContract.requirementTestMatrixLock());
        byte[] cases = exportArtifact(plan, artifacts, tenant, "provenance/acceptance-case-suite.json",
                MaintenanceInvestigationProductContract.caseSuiteLock());
        byte[] resultBytes = exportArtifact(plan, artifacts, tenant, "provenance/verification-result-manifest.json",
                reference.verificationResultManifest());
        var result = mapper.readValue(resultBytes, VerificationResultManifest.class);
        require(result.verificationRunId().equals(reference.verificationRunId())
                && result.candidateHash().equals(reference.candidateHash())
                && result.gateProfile().equals(MaintenanceInvestigationProductContract.GATE_PROFILE));
        var commands = result.commands().stream().filter(command -> command.commandId().equals(
                VerificationCommand.MAINTENANCE_ACCEPTANCE.commandId())).toList();
        require(commands.size() == 1 && !result.evidenceArtifacts().isEmpty());
        var command = commands.getFirst();
        byte[] summary = exportArtifact(plan, artifacts, tenant, "demonstration/acceptance-summary.json",
                new CertificationArtifactLock(command.machineEvidenceRef(), command.machineEvidenceHash()));
        JsonNode summaryNode = mapper.readTree(summary);
        var actualLock = new CertificationArtifactLock(result.evidenceArtifacts().getLast(),
                new ContentHash(summaryNode.path("actualHash").asText()));
        byte[] actual = exportArtifact(plan, artifacts, tenant, "demonstration/acceptance-actual.json", actualLock);
        require(MaintenanceAcceptanceGate.evidenceMatches(summary, actual, reference.candidateHash(), mapper));
        addDemonstration(plan, cases, actual, mapper);
        Instant executionDate = component.verificationRun().completedAt().orElseThrow();
        String readme = """
                # 출고 데모 인계물 / Released demonstration handoff

                이 폴더는 실제 출고된 Reference Assembly와 그 인증 Java 조사 코어의 읽기 전용 복사본입니다.
                새 인증 제품, 실행 가능한 RA 앱, Agent Runtime, 배포 또는 운영 환경이 아닙니다.
                Factory 공통 기반 위의 Agent Pack 생산 → 독립 검사 → 인증 → 다른 ProductLine 조립 → 출고 사례입니다.

                ## 먼저 볼 파일

                - demonstration/input.json: 저장된 code-owned NORMAL 검사 사례의 정제된 입력.
                - demonstration/actual-output.json: 저장된 실제 후보 실행 결과에서 추출한 출력(expected 값 복사 아님).
                - demonstration/report.md: 그 실제 출력의 reportMarkdown 원문.
                - provenance/release-manifest.json: 출고 제품과 인증 부품을 잇는 exact manifest graph.
                - product-source/: 인증된 원본 파일. 자동 실행하거나 다시 작성하지 않았습니다.

                조사 코어는 시간 순서로 증거를 정규화하고 timeout/error-rate finding 및 근거 참조 보고서를 만듭니다.
                API는 public static Map<String,Object> InvestigationAcceptanceApi.investigate(Map<String,Object>)인
                제품 검사 전용 bridge입니다. LLM 호출, 외부 도구, 범용 Agent 실행 프로토콜을 제공하지 않습니다.

                ## 증거와 시간

                실제 검사는 %s에 완료되었습니다(UTC, 원장 완료 시각). 이 export는 새 실행이나 새 검사가 아닙니다.
                새 현재성 검증 구간은 provenance/full-readback.json의 startedAt/completedAt을 보십시오.
                inspection/report와 source의 원본 bytes/hash는 보존했습니다. input/output/report는 명시된
                원본 사례에서 추출한 표현이며 handoff-index.json에 derived provenance가 있습니다.
                handoff-index.json은 나머지 모든 파일의 SHA-256과 크기를 기록하며 마지막에 기록됩니다.
                index가 없거나 파일/hash/개수가 다르면 완료된 인계물로 사용하지 마십시오.

                ## 한계와 안전

                이것은 관찰 구간의 증거이지 영구 유효 인증이나 DB 전체 원자 snapshot이 아닙니다.
                후속 소비자는 현재 인증/출고 full gate를 다시 확인해야 합니다. 디렉터리 ACL은 운영자가 소유합니다.
                Worker 입력/계정/대화/로그/credential은 포함하지 않습니다. manifest의 참조는 다운로드 지시가 아닙니다.
                certification input-lock, dependency/cache, verification log 등은 의도적으로 내보내지 않으므로
                이 폴더만으로 전체 인증 검사를 offline 재현했다고 주장하지 않습니다.
                원본 소스와 POM/test는 열람 대상이며 이 exporter는 빌드하거나 실행하지 않습니다.
                재실행이 필요하면 별도로 승인한 고정·격리 sandbox 검사 절차를 사용하십시오.
                Factory 책임은 생산·검사·승인·출고 인계까지이며 deploy/rollout/운영은 별도 책임입니다.
                """.formatted(executionDate);
        plan.add("README.md", readme.getBytes(StandardCharsets.UTF_8), "derived:operator-handoff-description");
        return plan;
    }

    private static byte[] exportArtifact(ExportPlan plan, ArtifactStore artifacts, TenantId tenant,
            String path, CertificationArtifactLock expected) throws Exception {
        Artifact artifact = artifacts.find(tenant, expected.reference()).orElseThrow();
        byte[] bytes = artifact.content();
        require(artifact.tenantId().equals(tenant) && artifact.reference().equals(expected.reference())
                && artifact.contentHash().equals(expected.hash())
                && new OrdinalSourceTreeHasher().sha256(bytes).equals(expected.hash()));
        plan.add(path, bytes, "artifact:" + expected.reference().value());
        return bytes;
    }

    /** Joins the persisted suite and actual by exact case and invocation, never relabels expected as actual. */
    static void addDemonstration(ExportPlan plan, byte[] suiteBytes, byte[] actualBytes, ObjectMapper mapper) throws Exception {
        require(Arrays.equals(suiteBytes, MaintenanceInvestigationProductContract.caseSuiteBytes()));
        JsonNode suite = mapper.readTree(suiteBytes);
        JsonNode actual = mapper.readTree(actualBytes);
        var suiteCases = new ArrayList<JsonNode>();
        for (JsonNode value : suite.path("cases")) if ("NORMAL".equals(value.path("caseId").asText())) suiteCases.add(value);
        var observations = new ArrayList<JsonNode>();
        for (JsonNode value : actual.path("cases")) if ("NORMAL".equals(value.path("caseId").asText())) observations.add(value);
        require(suiteCases.size() == 1 && observations.size() == 1);
        JsonNode sample = suiteCases.getFirst(); JsonNode observation = observations.getFirst();
        require(sample.path("repeatInvocations").isInt() && sample.path("repeatInvocations").intValue() == 1
                && observation.path("invocation").isInt() && observation.path("invocation").intValue() == 0
                && sample.path("input").isObject() && observation.path("actual").isObject()
                && sample.path("expectedOutput").equals(observation.path("actual"))
                && observation.path("actual").path("reportMarkdown").isTextual());
        plan.add("demonstration/input.json", mapper.writeValueAsBytes(sample.get("input")),
                "derived:provenance/acceptance-case-suite.json#/cases/NORMAL/input");
        plan.add("demonstration/actual-output.json", mapper.writeValueAsBytes(observation.get("actual")),
                "derived:demonstration/acceptance-actual.json#/cases/NORMAL/0/actual");
        plan.add("demonstration/report.md", observation.get("actual").get("reportMarkdown").textValue().getBytes(StandardCharsets.UTF_8),
                "derived:demonstration/acceptance-actual.json#/cases/NORMAL/0/actual/reportMarkdown");
    }

    /** Local snapshot copy only; has no repository, Action, process or model capability. */
    static final class ExportPlan {
        private static final long MAX_TOTAL = 64L * 1024 * 1024;
        private static final String INDEX = "handoff-index.json";
        private final TreeMap<String, ExportFile> files = new TreeMap<>();
        private final Set<String> folded = new HashSet<>();
        private long total;
        private byte[] sealedIndex;

        void add(String path, byte[] bytes, String origin) {
            validateRelative(path);
            require(sealedIndex == null && !path.equals(INDEX) && bytes != null && bytes.length <= 8 * 1024 * 1024
                    && files.size() < 2048 && origin != null && !origin.isBlank());
            require(new SecretScanner().scan(path, bytes).isEmpty());
            require(!files.containsKey(path) && folded.add(path.toLowerCase(Locale.ROOT)));
            for (String existing : files.keySet()) require(!existing.startsWith(path + "/") && !path.startsWith(existing + "/"));
            total = Math.addExact(total, bytes.length); require(total <= MAX_TOTAL);
            files.put(path, new ExportFile(bytes.clone(), origin, new OrdinalSourceTreeHasher().sha256(bytes).sha256()));
        }

        byte[] index(ObjectMapper mapper) throws Exception {
            require(!files.isEmpty());
            var entries = new ArrayList<Map<String, Object>>();
            files.forEach((path, file) -> entries.add(Map.of("path", path, "sha256", file.hash(),
                    "sizeBytes", file.bytes().length, "origin", file.origin())));
            return mapper.writeValueAsBytes(Map.of("schemaVersion", "factory.demo-handoff-index.v1",
                    "scope", "released-snapshot-not-new-certified-product", "files", entries,
                    "fileCount", entries.size(), "totalBytes", total));
        }

        void seal(byte[] index) { require(sealedIndex == null && index.length <= 1024 * 1024); sealedIndex = index.clone(); }

        static boolean allowedSource(String path) {
            return "pom.xml".equals(path) || path.matches("src/(?:main|test)/java/(?:[A-Za-z_$][A-Za-z0-9_$]*/)*[A-Za-z_$][A-Za-z0-9_$]*\\.java");
        }

        static void validateRelative(String path) {
            require(path != null && path.length() <= 240 && !path.startsWith("/") && !path.endsWith("/")
                    && !path.contains("\\") && !path.contains(":"));
            for (String segment : path.split("/", -1)) {
                require(segment.matches("[A-Za-z0-9_$][A-Za-z0-9._$-]*") && !segment.endsWith(".")
                        && !segment.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"));
            }
        }

        static void regularAncestors(Path directory) throws Exception {
            for (Path current = directory; current != null; current = current.getParent()) {
                var attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther());
                require(current.toRealPath().equals(current.toAbsolutePath().normalize()));
            }
        }

        static void validateDestination(Path directory) throws Exception {
            require(directory != null && directory.isAbsolute() && directory.equals(directory.normalize())
                    && directory.getParent() != null && directory.getParent().getParent() != null
                    && !Files.exists(directory, LinkOption.NOFOLLOW_LINKS));
            for (Path segment : directory) require(!Set.of(".codex", ".git", ".ssh").contains(segment.toString().toLowerCase(Locale.ROOT)));
            regularAncestors(directory.getParent());
        }

        void write(Path destination) throws Exception {
            require(sealedIndex != null);
            validateDestination(destination);
            Files.createDirectory(destination);
            Object rootKey = Files.readAttributes(destination, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            boolean indexCreated = false;
            Object indexKey = null;
            try {
                for (var entry : files.entrySet()) {
                    Path output = destination.resolve(entry.getKey());
                    require(output.normalize().startsWith(destination));
                    Path parent = output.getParent();
                    var missing = new ArrayList<Path>();
                    for (Path cursor = parent; !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS); cursor = cursor.getParent()) missing.add(cursor);
                    for (int i = missing.size() - 1; i >= 0; i--) {
                        regularAncestors(missing.get(i).getParent()); Files.createDirectory(missing.get(i));
                    }
                    regularAncestors(parent);
                    Files.write(output, entry.getValue().bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    checkFile(output, entry.getValue().bytes());
                }
                verifyTree(destination, false, rootKey);
                // Index is the completion marker, never created before all payload bytes revalidate.
                try (var channel = Files.newByteChannel(destination.resolve(INDEX),
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                    indexCreated = true;
                    indexKey = Files.readAttributes(destination.resolve(INDEX), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                    var buffer = ByteBuffer.wrap(sealedIndex);
                    while (buffer.hasRemaining()) channel.write(buffer);
                }
                checkFile(destination.resolve(INDEX), sealedIndex);
                verifyTree(destination, true, rootKey);
            } catch (Exception failure) {
                // Preserve partial payloads for operator inspection. Remove only our own completion marker.
                if (indexCreated) {
                    try {
                        regularAncestors(destination);
                        require(Objects.equals(rootKey, Files.readAttributes(destination, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey()));
                        var marker = Files.readAttributes(destination.resolve(INDEX), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        require(indexKey != null && marker.isRegularFile() && !marker.isSymbolicLink() && !marker.isOther()
                                && indexKey.equals(marker.fileKey()));
                        Files.delete(destination.resolve(INDEX));
                    } catch (Exception ignored) { /* Failure remains failure; an outer exception never prints success. */ }
                }
                throw failure;
            }
        }

        private void verifyTree(Path root, boolean hasIndex, Object rootKey) throws Exception {
            regularAncestors(root);
            require(Objects.equals(rootKey, Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey()));
            var observed = new TreeSet<String>();
            var directories = new HashSet<String>(); directories.add("");
            for (String file : files.keySet()) {
                for (int slash = file.indexOf('/'); slash >= 0; slash = file.indexOf('/', slash + 1)) directories.add(file.substring(0, slash));
            }
            try (var tree = Files.walk(root)) {
                var paths = tree.iterator(); int visited = 0;
                while (paths.hasNext()) {
                    Path path = paths.next();
                    require(++visited <= files.size() + directories.size() + (hasIndex ? 1 : 0));
                    var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    require(!attrs.isSymbolicLink() && !attrs.isOther());
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    if (attrs.isDirectory()) { require(directories.contains(relative)); regularAncestors(path); continue; }
                    require(attrs.isRegularFile());
                    require(observed.add(relative));
                    if (hasIndex && relative.equals(INDEX)) checkFile(path, sealedIndex);
                    else { require(files.containsKey(relative)); checkFile(path, files.get(relative).bytes()); }
                }
            }
            var expected = new TreeSet<>(files.keySet()); if (hasIndex) expected.add(INDEX);
            require(observed.equals(expected));
        }

        private static void checkFile(Path path, byte[] expected) throws Exception {
            regularAncestors(path.getParent());
            var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            require(before.isRegularFile() && !before.isSymbolicLink() && !before.isOther() && before.size() == expected.length);
            byte[] bytes;
            try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 var input = Channels.newInputStream(channel)) { bytes = input.readNBytes(expected.length + 1); }
            var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            require(after.isRegularFile() && !after.isSymbolicLink() && !after.isOther()
                    && Objects.equals(before.fileKey(), after.fileKey()) && before.lastModifiedTime().equals(after.lastModifiedTime())
                    && Arrays.equals(bytes, expected));
        }

        private record ExportFile(byte[] bytes, String origin, String hash) { }
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    private static String readPassword(Path path) throws Exception {
        require(path.isAbsolute() && path.normalize().equals(path));
        for (Path current = path; current != null; current = current.getParent()) {
            require(!Files.isSymbolicLink(current));
            require(!Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther());
        }
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(before.isRegularFile() && before.size() > 0 && before.size() <= 4096);
        byte[] bytes;
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel)) { bytes = input.readNBytes(4097); }
        try {
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            require(after.isRegularFile() && !after.isSymbolicLink() && bytes.length == before.size()
                    && after.size() == before.size() && Objects.equals(before.fileKey(), after.fileKey())
                    && before.lastModifiedTime().equals(after.lastModifiedTime()));
            String password = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (password.endsWith("\r\n")) password = password.substring(0, password.length() - 2);
            else if (password.endsWith("\n")) password = password.substring(0, password.length() - 1);
            require(!password.isEmpty() && password.indexOf('\n') < 0 && password.indexOf('\r') < 0
                    && password.indexOf('\0') < 0 && password.charAt(0) != '\uFEFF');
            return password;
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("readback precondition rejected");
    }

    private enum ProbeStage { SELECTED_RELEASE }

    private static final class ProbeStageFailure extends RuntimeException {
        final ProbeStage stage;
        ProbeStageFailure(ProbeStage stage) {
            super(null, null, false, false);
            this.stage = Objects.requireNonNull(stage);
        }
    }

    record Options(String url, String user, Path passwordFile, String tenant,
                   String certification, Optional<String> assembly, Optional<Path> exportDirectory) {
        static Options parse(String[] args) {
            Set<String> required = Set.of("--db-url", "--db-user", "--password-file", "--tenant", "--certification-id");
            var values = new HashMap<String, String>();
            require(args.length == 10 || args.length == 12 || args.length == 14);
            for (int index = 0; index < args.length; index += 2) {
                String key = args[index]; String value = args[index + 1];
                require((required.contains(key) || key.equals("--assembly-id") || key.equals("--export-dir"))
                        && value != null && !value.isBlank() && value.length() <= 2048
                        && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl)
                        && values.putIfAbsent(key, value) == null);
            }
            require(values.keySet().containsAll(required));
            require(!values.containsKey("--export-dir") || values.containsKey("--assembly-id"));
            // Bounded local production probe: no URL properties, embedded userinfo or host routing override.
            String url = values.get("--db-url");
            require(url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/[A-Za-z0-9_][A-Za-z0-9_-]{0,62}"));
            require(values.get("--db-user").matches("[A-Za-z_][A-Za-z0-9_-]{0,62}"));
            for (String key : List.of("--tenant", "--certification-id", "--assembly-id")) {
                if (values.containsKey(key)) require(values.get(key).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"));
            }
            return new Options(url, values.get("--db-user"), Path.of(values.get("--password-file")),
                    values.get("--tenant"), values.get("--certification-id"), Optional.ofNullable(values.get("--assembly-id")),
                    Optional.ofNullable(values.get("--export-dir")).map(Path::of));
        }
    }

    /** Only find is delegated; even accidental future catalog provisioning must fail before JDBC. */
    private record ReadOnlyArtifacts(JdbcArtifactStore delegate) implements ArtifactStore {
        @Override public ArtifactReference store(Artifact ignored) { throw new UnsupportedOperationException("read-only probe"); }
        @Override public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) { return delegate.find(tenant, reference); }
    }

    /** Every new connection starts server-read-only and proves both defaults and current transaction. */
    private static final class ReadOnlyDataSource implements DataSource {
        private final String url;
        private final Properties properties = new Properties();
        private final long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        private final AtomicInteger connections = new AtomicInteger();

        ReadOnlyDataSource(String url, String user, String password) {
            this.url = url;
            properties.setProperty("user", user);
            properties.setProperty("password", password);
            properties.setProperty("readOnly", "true");
            properties.setProperty("ApplicationName", "factory-production-readback");
            properties.setProperty("connectTimeout", "5");
            properties.setProperty("socketTimeout", "15");
            properties.setProperty("options", "-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=1000");
        }

        @Override public Connection getConnection() throws SQLException {
            if (System.nanoTime() >= deadline || connections.incrementAndGet() > 4096) throw new SQLException("probe bound");
            Connection connection = DriverManager.getConnection(url, properties);
            try {
                connection.setReadOnly(true);
                try (var statement = connection.prepareStatement(
                        "SELECT current_setting('default_transaction_read_only'), current_setting('transaction_read_only')")) {
                    statement.setQueryTimeout(10);
                    try (var result = statement.executeQuery()) {
                        if (!connection.isReadOnly() || !result.next() || !"on".equals(result.getString(1))
                                || !"on".equals(result.getString(2)) || result.next()) throw new SQLException("read-only proof failed");
                    }
                }
                return connection;
            } catch (SQLException | RuntimeException failed) {
                try { connection.close(); } catch (SQLException closeFailure) { failed.addSuppressed(closeFailure); }
                throw failed;
            }
        }

        @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("alternate identity forbidden"); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter ignored) { throw new UnsupportedOperationException("driver logging forbidden"); }
        @Override public int getLoginTimeout() { return 5; }
        @Override public void setLoginTimeout(int ignored) { throw new UnsupportedOperationException("fixed timeout"); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> ignored) throws SQLException { throw new SQLException("unwrap forbidden"); }
        @Override public boolean isWrapperFor(Class<?> ignored) { return false; }
    }
}
