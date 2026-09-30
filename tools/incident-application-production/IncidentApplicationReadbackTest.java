import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.run.*;

/** Synthetic local tests only: no live database, current certification claim or production export. */
public final class IncidentApplicationReadbackTest {
    private static int passed;
    public static void main(String[] args) throws Exception {
        optionsAreExplicitAndReadOnlyByDefault();
        ambiguousRemoteOrCredentialUrlOptionsFail();
        exportPathsAndPayloadsAreAllowlisted();
        exportCopiesBytesAndWritesVerifiedIndexLast();
        existingDestinationAndSealedPlanRemainImmutable();
        protectedBroadAndLinkedDestinationsFail();
        payloadBoundsAndKnownSecretsFailBeforeWrites();
        syntheticPasswordReaderPreservesWhitespaceAndRejectsAmbiguity();
        everyConnectionProvesServerAndTransactionReadOnly();
        failedProofClosesConnectionAndDoesNotDiscloseDetails();
        alternateCredentialsLoggingUnwrapAndConnectionOverflowFail();
        unchangedReviewRetainsOriginalDeadlineAndSubject();
        renewedReviewUsesInspectedVersionWithoutChangingProductLocks();
        oldPointRewrittenOriginalDeadlineAndUnprovenRenewalFail();
        renewalReadbackRequiresCanonicalSuccessfulOwnerBeforeDisclosure();
        optionalRenewalExportPreservesExactEvidenceAndRedactsPrivateOwnerFields();
        absentRenewalLeavesExistingExportPayloadsUnchanged();
        missingRenewalEvidenceAndUnauthorizedRenewalPayloadsFail();
        System.out.println("INCIDENT_APPLICATION_READBACK_SYNTHETIC_TESTS_PASSED:" + passed);
    }
    private static void optionsAreExplicitAndReadOnlyByDefault() throws Exception {
        var options=IncidentApplicationReadback.Options.parse(options());
        check(options.exportDirectory().isEmpty() && options.session().equals("session-demo") && options.tenant().equals("tenant-demo"));
        check(IncidentApplicationReadback.Options.parse(plus(options(),"--export-dir",Path.of("new-export").toAbsolutePath().toString())).exportDirectory().isPresent());
        passed++;
    }
    private static void ambiguousRemoteOrCredentialUrlOptionsFail() throws Exception {
        reject(()->IncidentApplicationReadback.Options.parse(plus(options(),"--tenant","other")));
        reject(()->IncidentApplicationReadback.Options.parse(plus(options(),"--unknown","value")));
        for(String url:List.of("jdbc:postgresql://evil.example:5432/db","jdbc:postgresql://localhost:5432/db?readOnly=false",
                "jdbc:postgresql://user:synthetic@localhost:5432/db","jdbc:postgresql://localhost/db")) {
            var input=options(); input[1]=url; reject(()->IncidentApplicationReadback.Options.parse(input));
        }
        var whitespace=options();whitespace[7]=" tenant-demo";reject(()->IncidentApplicationReadback.Options.parse(whitespace));
        passed++;
    }
    private static void exportPathsAndPayloadsAreAllowlisted() throws Exception {
        for(String path:List.of("../escape","/absolute","C:/absolute","provenance/../../secret","a\\b","provenance/NUL.json",
                "provenance/.","provenance/file:ads","auth.json",".env","worker/transcript.json","handoff-index.json","readme.md"))
            reject(()->new IncidentApplicationReadback.ExportPlan().add(path,bytes("synthetic"),"synthetic:test"));
        var plan=sample();reject(()->plan.add("README.md",bytes("duplicate"),"synthetic:test"));passed++;
    }
    private static void exportCopiesBytesAndWritesVerifiedIndexLast() throws Exception {
        temporary(root->{
            var plan=new IncidentApplicationReadback.ExportPlan();byte[] data=bytes("original Korean 한글\r\n");
            plan.add("README.md",data,"synthetic:test");data[0]='X';
            plan.add("provenance/BOM.json",bytes("{\"synthetic\":true}"),"synthetic:test");
            byte[] index=plan.seal(IncidentApplicationReadback.mapper());Path target=root.resolve("new");plan.write(target);
            check(Files.readString(target.resolve("README.md")).equals("original Korean 한글\r\n"));
            check(Arrays.equals(Files.readAllBytes(target.resolve("handoff-index.json")),index));
            var node=IncidentApplicationReadback.mapper().readTree(index);check(node.path("fileCount").asInt()==2);
            for(var entry:node.path("files")) {
                byte[] actual=Files.readAllBytes(target.resolve(entry.path("path").asText()));
                check(actual.length==entry.path("sizeBytes").asInt());
                check(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(actual)).equals(entry.path("sha256").asText()));
            }
        });passed++;
    }
    private static void existingDestinationAndSealedPlanRemainImmutable() throws Exception {
        temporary(root->{
            Path existing=Files.createDirectory(root.resolve("existing"));Files.writeString(existing.resolve("keep.txt"),"user-owned-synthetic");
            var plan=sample();plan.seal(IncidentApplicationReadback.mapper());reject(()->plan.write(existing));
            check(Files.readString(existing.resolve("keep.txt")).equals("user-owned-synthetic") && !Files.exists(existing.resolve("handoff-index.json")));
            reject(()->plan.add("provenance/BOM.json",bytes("{}"),"synthetic:test"));reject(()->plan.seal(IncidentApplicationReadback.mapper()));
            Path successful=root.resolve("success");plan.write(successful);reject(()->plan.write(successful));
        });passed++;
    }
    private static void protectedBroadAndLinkedDestinationsFail() throws Exception {
        reject(()->IncidentApplicationReadback.ExportPlan.validateDestination(Path.of("relative")));
        reject(()->IncidentApplicationReadback.ExportPlan.validateDestination(Path.of(".").toAbsolutePath().getRoot()));
        temporary(root->{
            Path protectedRoot=Files.createDirectory(root.resolve(".codex"));
            reject(()->IncidentApplicationReadback.ExportPlan.validateDestination(protectedRoot.resolve("export")));
            Path target=Files.createDirectory(root.resolve("target"));Path link=root.resolve("linked");
            try {Files.createSymbolicLink(link,target);}
            catch(UnsupportedOperationException|java.io.IOException unavailable) {
                if(Boolean.getBoolean("factory.incident.readback.test.requireLinks"))throw new AssertionError("LINK_TEST_REQUIRED");
                System.out.println("SKIP INCIDENT_READBACK_LINK_TEST_OS_PERMISSION");return;
            }
            reject(()->IncidentApplicationReadback.ExportPlan.validateDestination(link.resolve("export")));
        });passed++;
    }
    private static void payloadBoundsAndKnownSecretsFailBeforeWrites() throws Exception {
        reject(()->new IncidentApplicationReadback.ExportPlan().add("README.md",new byte[256*1024+1],"synthetic:test"));
        reject(()->new IncidentApplicationReadback.ExportPlan().add("application.zip",new byte[24*1024*1024+1],"synthetic:test"));
        reject(()->new IncidentApplicationReadback.ExportPlan().add("provenance/BOM.json",bytes("-----BEGIN PRIVATE KEY-----\nsynthetic\n-----END PRIVATE KEY-----"),"synthetic:test"));
        reject(()->new IncidentApplicationReadback.ExportPlan().seal(IncidentApplicationReadback.mapper()));passed++;
    }
    private static void syntheticPasswordReaderPreservesWhitespaceAndRejectsAmbiguity() throws Exception {
        temporary(root->{
            Path valid=root.resolve("synthetic-password.txt");Files.writeString(valid," leading-and-trailing \r\n");
            check(IncidentApplicationReadback.readPassword(valid).equals(" leading-and-trailing "));
            Path malformed=root.resolve("invalid.txt");
            for(byte[] data:List.of(bytes(""),bytes("one\ntwo"),bytes("\uFEFFsynthetic"),new byte[4097],new byte[]{(byte)0xc0,(byte)0xaf})) {
                Files.write(malformed,data);reject(()->IncidentApplicationReadback.readPassword(malformed));
            }
        });passed++;
    }
    private static void everyConnectionProvesServerAndTransactionReadOnly() throws Exception {
        var driver=new SyntheticDriver(true);DriverManager.registerDriver(driver);
        try {
            var source=new IncidentApplicationReadback.ReadOnlyDataSource(SyntheticDriver.URL,"synthetic_user","synthetic-value");
            try(var first=source.getConnection()){check(first.isReadOnly());}
            try(var second=source.getConnection()){check(second.isReadOnly());}
            check(driver.connections.get()==2 && driver.proofs.get()==2 && source.connections.get()==2);
            check(driver.startupProof && driver.closed.get()==2);
        } finally {DriverManager.deregisterDriver(driver);}passed++;
    }
    private static void failedProofClosesConnectionAndDoesNotDiscloseDetails() throws Exception {
        var driver=new SyntheticDriver(false);DriverManager.registerDriver(driver);
        try {
            var source=new IncidentApplicationReadback.ReadOnlyDataSource(SyntheticDriver.URL,"synthetic_user","synthetic-value");
            reject(source::getConnection);check(driver.closed.get()==1 && driver.proofs.get()==1);
        }finally{DriverManager.deregisterDriver(driver);}passed++;
    }
    private static void alternateCredentialsLoggingUnwrapAndConnectionOverflowFail() throws Exception {
        var source=new IncidentApplicationReadback.ReadOnlyDataSource(SyntheticDriver.URL,"synthetic_user","synthetic-value");
        reject(()->source.getConnection("other","other-synthetic"));reject(()->source.setLogWriter(null));reject(()->source.unwrap(Connection.class));
        reject(()->source.setLoginTimeout(100));check(!source.isWrapperFor(Connection.class));
        source.connections.set(4096);reject(source::getConnection);passed++;
    }
    private static void unchangedReviewRetainsOriginalDeadlineAndSubject() throws Exception {
        var f=new RenewalFixture();var ledger=f.ledger(Optional.empty());
        IncidentApplicationReadback.checkReviewSubject(f.original,f.session(false),f.previous,f.artifacts,ledger);
        check(ledger.executionDeadline(f.original).equals(RenewalFixture.ORIGINAL_DUE)
                && ledger.inspectedVersion(f.original,4)==4);
        check(IncidentApplicationReadback.readRenewal(ledger,f.original,f.points(),f.runs(f.owner),f.artifacts).isEmpty());
        passed++;
    }
    private static void renewedReviewUsesInspectedVersionWithoutChangingProductLocks() throws Exception {
        var f=new RenewalFixture();var ledger=f.ledger(Optional.of(f.renewal));
        IncidentApplicationReadback.checkReviewSubject(f.renewed,f.session(false),f.current,f.artifacts,ledger);
        var proof=IncidentApplicationReadback.readRenewal(ledger,f.renewed,f.points(),f.runs(f.owner),f.artifacts).orElseThrow();
        var report=IncidentApplicationReadback.renewalReport(proof);
        check(f.renewed.version()==5 && f.current.subjectVersion()==4 && ledger.inspectedVersion(f.renewed,5)==4);
        check(f.original.workOrder().equals(f.renewed.workOrder()) && f.original.product().equals(f.renewed.product())
                && f.original.verification().equals(f.renewed.verification()) && f.original.releaseSubject().equals(f.renewed.releaseSubject()));
        check(Arrays.equals(IncidentApplicationDecisionAuthority.subject(f.original,4).content(),
                IncidentApplicationDecisionAuthority.subject(f.renewed,4).content()));
        check(report.get("originalDeadlineAt").equals(RenewalFixture.ORIGINAL_DUE.toString())
                && report.get("effectiveDeadlineAt").equals(RenewalFixture.RENEWED_DUE.toString())
                && report.get("subject").equals(IncidentApplicationArtifacts.lockMap(f.original.releaseSubject()))
                && report.get("canonicalOwnerVerified").equals(true));
        passed++;
    }
    private static void oldPointRewrittenOriginalDeadlineAndUnprovenRenewalFail() throws Exception {
        var f=new RenewalFixture();var ledger=f.ledger(Optional.of(f.renewal));
        reject(()->IncidentApplicationReadback.checkReviewSubject(f.renewed,f.session(false),f.previous,f.artifacts,ledger));
        reject(()->IncidentApplicationReadback.checkReviewSubject(f.renewed,f.session(true),f.current,f.artifacts,ledger));
        reject(()->IncidentApplicationReadback.checkReviewSubject(f.renewed,f.session(false),f.current,f.artifacts,f.ledger(Optional.empty())));
        reject(()->ledger.inspectedVersion(f.renewed,4));
        var wrongVersion=f.point(f.current.decisionPointId(),5,RenewalFixture.RENEWED_AT,RenewalFixture.RENEWED_DUE);
        reject(()->IncidentApplicationReadback.checkReviewSubject(f.renewed,f.session(false),wrongVersion,f.artifacts,ledger));
        passed++;
    }
    private static void renewalReadbackRequiresCanonicalSuccessfulOwnerBeforeDisclosure() throws Exception {
        var f=new RenewalFixture();var ledger=f.ledger(Optional.of(f.renewal));
        // A failed canonical ledger proof must stop before any auxiliary point or owner lookup.
        var denied=proxy(IncidentApplicationLedger.class,(object,method,args)->{throw new IllegalArgumentException("SYNTHETIC_UNCERTAIN_OWNER");});
        var forbiddenPoints=proxy(DecisionPointRepository.class,(object,method,args)->{throw new AssertionError("EARLY_POINT_DISCLOSURE");});
        var forbiddenRuns=proxy(RunStore.class,(object,method,args)->{throw new AssertionError("EARLY_OWNER_DISCLOSURE");});
        reject(()->IncidentApplicationReadback.readRenewal(denied,f.renewed,forbiddenPoints,forbiddenRuns,f.artifacts));
        reject(()->IncidentApplicationReadback.readRenewal(ledger,f.renewed,f.points(),f.runs(null),f.artifacts));
        for(var status:List.of(ActionRunStatus.RUNNING,ActionRunStatus.FAILED,ActionRunStatus.CANCELLED)) {
            var action=f.action(status,"synthetic-operator-private",status==ActionRunStatus.RUNNING?null:
                    ActionExecutionResult.manualReviewFailure("SYNTHETIC_NON_SUCCESS","Synthetic non-success owner"));
            reject(()->IncidentApplicationReadback.readRenewal(ledger,f.renewed,f.points(),f.runs(action),f.artifacts));
        }
        var wrongResult=f.action(ActionRunStatus.SUCCEEDED,"synthetic-operator-private",ActionExecutionResult.succeeded(Map.of("wrong","result")));
        reject(()->IncidentApplicationReadback.readRenewal(ledger,f.renewed,f.points(),f.runs(wrongResult),f.artifacts));
        var wrongPrincipal=f.action(ActionRunStatus.SUCCEEDED,IncidentApplicationActions.OWNER,IncidentApplicationReviewRenewalAction.result(f.renewal));
        reject(()->IncidentApplicationReadback.readRenewal(ledger,f.renewed,f.points(),f.runs(wrongPrincipal),f.artifacts));
        passed++;
    }
    private static void optionalRenewalExportPreservesExactEvidenceAndRedactsPrivateOwnerFields() throws Exception {
        var f=new RenewalFixture();var proof=IncidentApplicationReadback.readRenewal(f.ledger(Optional.of(f.renewal)),
                f.renewed,f.points(),f.runs(f.owner),f.artifacts);
        temporary(root->{
            var plan=sample();byte[] originalBundle=bytes("synthetic-exact-bundle-bytes");
            plan.add("application.zip",originalBundle,"synthetic:immutable-bundle");
            IncidentApplicationReadback.addRenewalExport(plan,f.artifacts,proof);
            check(plan.files.size()==5);
            byte[] index=plan.seal(IncidentApplicationReadback.mapper());Path target=root.resolve("renewed-export");plan.write(target);
            check(Arrays.equals(Files.readAllBytes(target.resolve("application.zip")),originalBundle));
            check(Arrays.equals(Files.readAllBytes(target.resolve("provenance/review-renewal.json")),f.renewal.evidence().content()));
            var previous=IncidentApplicationReadback.mapper().readTree(target.resolve("provenance/previous-review-point.json").toFile());
            check(previous.path("decisionPointId").asText().equals(f.previous.decisionPointId().value())
                    && previous.path("dueAt").asText().equals(RenewalFixture.ORIGINAL_DUE.toString()) && previous.path("subjectVersion").asInt()==4);
            String actionText=Files.readString(target.resolve("provenance/review-renewal-action.json"));
            var action=IncidentApplicationReadback.mapper().readTree(actionText);
            check(action.path("actionId").asText().equals(IncidentApplicationReviewRenewalAction.ID)
                    && action.path("status").asText().equals("SUCCEEDED") && action.path("canonicalOwnerVerified").asBoolean());
            check(!actionText.contains(RenewalFixture.PRIVATE_ATTEMPT) && !actionText.contains(RenewalFixture.PRIVATE_SNAPSHOT)
                    && !actionText.contains("synthetic-operator-private") && !actionText.contains("contextMetadata")
                    && !actionText.contains("attemptToken") && !actionText.contains("requesterId"));
            for(var entry:IncidentApplicationReadback.mapper().readTree(index).path("files")) {
                byte[] actual=Files.readAllBytes(target.resolve(entry.path("path").asText()));
                check(entry.path("sha256").asText().equals(IncidentApplicationArtifacts.hash(actual)) && actual.length==entry.path("sizeBytes").asInt());
            }
        });passed++;
    }
    private static void absentRenewalLeavesExistingExportPayloadsUnchanged() throws Exception {
        var f=new RenewalFixture();var baseline=sample();var unchanged=sample();
        IncidentApplicationReadback.addRenewalExport(unchanged,f.artifacts,Optional.empty());
        check(Arrays.equals(baseline.seal(IncidentApplicationReadback.mapper()),unchanged.seal(IncidentApplicationReadback.mapper())));
        passed++;
    }
    private static void missingRenewalEvidenceAndUnauthorizedRenewalPayloadsFail() throws Exception {
        var f=new RenewalFixture();var proof=IncidentApplicationReadback.readRenewal(f.ledger(Optional.of(f.renewal)),
                f.renewed,f.points(),f.runs(f.owner),f.artifacts);
        f.stored.remove(f.renewal.evidence().reference());
        reject(()->IncidentApplicationReadback.addRenewalExport(sample(),f.artifacts,proof));
        reject(()->IncidentApplicationReadback.readRenewal(f.ledger(Optional.of(f.renewal)),f.renewed,f.points(),f.runs(f.owner),f.artifacts));
        for(String path:List.of("provenance/renewal-authority-snapshot.json","provenance/renewal-action-raw.json","provenance/renewal-attempt-token.json"))
            reject(()->new IncidentApplicationReadback.ExportPlan().add(path,bytes("{}"),"synthetic:test"));
        passed++;
    }
    /** Contract-only evidence. It does not seed a Factory DB, grant certification or claim product execution. */
    private static final class RenewalFixture {
        static final Instant CREATED=Instant.parse("2026-09-13T00:00:00Z"),ORIGINAL_DUE=Instant.parse("2026-09-14T00:00:00Z"),
                RENEWED_AT=Instant.parse("2026-09-15T00:00:00Z"),RENEWED_DUE=Instant.parse("2026-09-16T00:00:00Z");
        static final String PRIVATE_ATTEMPT="synthetic-private-attempt-not-for-export",PRIVATE_SNAPSHOT="synthetic/authority/private-not-for-export";
        final TenantId tenant=new TenantId("synthetic-renewal-tenant");final BuildSessionId sessionId=new BuildSessionId("synthetic-renewal-session");
        final Map<ArtifactReference,Artifact> stored=new HashMap<>();
        final ArtifactStore artifacts=new ArtifactStore(){
            public ArtifactReference store(Artifact value){throw new AssertionError("READBACK_WRITE_FORBIDDEN");}
            public Optional<Artifact> find(TenantId requested,ArtifactReference ref){check(requested.equals(tenant));return Optional.ofNullable(stored.get(ref));}
        };
        final IncidentApplicationProduct original,renewed;
        final DecisionPoint previous,current;
        final IncidentApplicationReviewRenewal renewal;
        final ActionRun owner;
        final Map<String,Object> input;
        RenewalFixture(){
            var componentLock=artifact("synthetic-component");
            var component=new CertifiedAgentComponentRef(CertifiedAgentComponentRef.SCHEMA_VERSION,"embedded-agent-pack",ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,new CertificationId("synthetic-component-cert"),componentLock,new CandidateId("synthetic-component-candidate"),
                    componentLock.hash(),componentLock,componentLock,new VerificationRunId("synthetic-component-verification"),componentLock,componentLock,componentLock,"synthetic-profile");
            var order=new IncidentApplicationOrder(sessionId,tenant,new ProjectId("synthetic-renewal-project"),"synthetic-renewal-request",
                    IncidentApplicationOrder.Variant.BASIC,component,ORIGINAL_DUE);
            var work=new IncidentApplicationBuildWorkOrder(order,artifact("requirements"),artifact("blueprint"),artifact("catalog"),artifact("policy"),artifact("work-order"),"synthetic-modules");
            var prepared=new IncidentApplicationPreparedProduct(artifact("candidate"),artifact("bom"),artifact("bundle"),artifact("build-evidence"));
            var verification=new IncidentApplicationWholeVerification(true,"SYNTHETIC_PASSED",artifact("verification"),artifact("suite"));
            var originalPoint=new DecisionPointId("synthetic-previous-point");
            var draft=new IncidentApplicationProduct(work,IncidentApplicationProduct.Status.REVIEW,prepared,verification,originalPoint,
                    artifact("placeholder"),null,null,"synthetic-verify-operation",null,null,4,CREATED,CREATED.plusSeconds(60));
            Artifact subject=IncidentApplicationDecisionAuthority.subject(draft,4);stored.put(subject.reference(),subject);
            original=new IncidentApplicationProduct(work,IncidentApplicationProduct.Status.REVIEW,prepared,verification,originalPoint,
                    IncidentApplicationArtifacts.lock(subject),null,null,"synthetic-verify-operation",null,null,4,CREATED,CREATED.plusSeconds(60));
            previous=point(originalPoint,4,CREATED.plusSeconds(60),ORIGINAL_DUE);
            var currentPoint=new DecisionPointId("synthetic-current-point");
            renewed=original.next(IncidentApplicationProduct.Status.REVIEW,prepared,verification,currentPoint,original.releaseSubject(),null,null,
                    original.activeOperationId(),null,null,RENEWED_AT);
            current=point(currentPoint,4,RENEWED_AT,RENEWED_DUE);
            input=IncidentApplicationReviewRenewalAction.input(original,RENEWED_DUE);
            renewal=new IncidentApplicationReviewRenewal(tenant,sessionId,previous.decisionPointId(),currentPoint,original.releaseSubject(),4,5,
                    ORIGINAL_DUE,RENEWED_AT,RENEWED_DUE,"synthetic-renewal-action",IncidentApplicationArtifacts.hash(PRIVATE_ATTEMPT),
                    IncidentApplicationReviewRenewalAction.key(tenant,input));
            stored.put(renewal.evidence().reference(),renewal.evidence());
            owner=action(ActionRunStatus.SUCCEEDED,"synthetic-operator-private",IncidentApplicationReviewRenewalAction.result(renewal));
        }
        CertificationArtifactLock artifact(String kind){var value=IncidentApplicationArtifacts.document(tenant,kind,Map.of("synthetic",kind));stored.put(value.reference(),value);return IncidentApplicationArtifacts.lock(value);}
        DecisionPoint point(DecisionPointId id,long subjectVersion,Instant openedAt,Instant dueAt){
            return new DecisionPoint(id,tenant,sessionId,IncidentApplicationDecisionAuthority.DECISION_TYPE,DecisionPointStatus.OPEN,
                    IncidentApplicationDecisionAuthority.SUBJECT_TYPE,sessionId.value(),subjectVersion,original.releaseSubject().hash(),original.releaseSubject().reference(),
                    IncidentApplicationDecisionAuthority.OPTIONS_SCHEMA_ID,Set.of(IncidentApplicationDecisionAuthority.REQUIRED_PERMISSION),1,original.workOrder().policy().reference(),
                    openedAt,dueAt,Optional.empty(),Optional.empty(),0);
        }
        BuildSession session(boolean rewrittenDeadline){return new BuildSession(sessionId,tenant,original.order().projectId(),IncidentApplicationOrder.PRODUCT_LINE_ID,
                original.order().requestKey(),"synthetic-intake",BuildSessionStatus.WAITING_RELEASE_REVIEW,BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                original.workOrder().requirements().reference(),original.workOrder().requirements().hash(),Optional.empty(),Optional.empty(),Optional.of(original.workOrder().blueprint().reference()),
                Optional.empty(),Optional.empty(),Optional.empty(),0,0,CREATED,rewrittenDeadline?RENEWED_DUE:ORIGINAL_DUE,
                Optional.empty(),Optional.empty(),Optional.empty(),3,CREATED,RENEWED_AT);}
        ActionRun action(ActionRunStatus status,String principal,ActionExecutionResult result){
            return ActionRun.builder().runId(renewal.actionRunId()).version(5).tenantId(tenant.value()).userId(principal).traceId("synthetic-renewal-trace")
                    .contextMetadata(Map.of("actor.permissions",Set.of(IncidentApplicationReviewRenewalAction.ID),"actor.authoritySnapshotRef",PRIVATE_SNAPSHOT,
                            "resource.type",IncidentApplicationActions.RESOURCE,"resource.id",sessionId.value(),"resource.projectId",original.order().projectId().value()))
                    .actionId(IncidentApplicationReviewRenewalAction.ID).proposalId("synthetic-renewal-proposal").requesterId(principal)
                    .requestChannel(ActionRequestChannel.CLI).proposerType(ActionProposerType.USER).input(input).duplicateKey(renewal.requestKey())
                    .status(status).currentStage(status==ActionRunStatus.RUNNING?"EXECUTION":"TERMINAL").attemptToken(PRIVATE_ATTEMPT).result(result)
                    .createdAt(RENEWED_AT.minusMillis(5)).updatedAt(RENEWED_AT.plusMillis(5)).build();
        }
        IncidentApplicationLedger ledger(Optional<IncidentApplicationReviewRenewal> value){return proxy(IncidentApplicationLedger.class,(object,method,args)->{
            if(method.getName().equals("reviewRenewal")){check(args[0].equals(tenant)&&args[1].equals(sessionId));return value;}
            if(method.isDefault())return java.lang.reflect.InvocationHandler.invokeDefault(object,method,args);
            throw new AssertionError("READBACK_UNEXPECTED_LEDGER_METHOD:"+method.getName());
        });}
        DecisionPointRepository points(){return proxy(DecisionPointRepository.class,(object,method,args)->{
            check(method.getName().equals("find")&&args[0].equals(tenant));
            return Optional.of(args[1].equals(previous.decisionPointId())?previous:current);
        });}
        RunStore runs(ActionRun value){return proxy(RunStore.class,(object,method,args)->{
            check(method.getName().equals("find")&&args[0].equals(renewal.actionRunId()));return Optional.ofNullable(value);
        });}
    }
    private static final class SyntheticDriver implements Driver {
        static final String URL="jdbc:incident-readback-synthetic:test";
        final boolean serverReadOnly;final AtomicInteger connections=new AtomicInteger(),proofs=new AtomicInteger(),closed=new AtomicInteger();
        boolean startupProof;
        SyntheticDriver(boolean serverReadOnly){this.serverReadOnly=serverReadOnly;}
        public boolean acceptsURL(String url){return URL.equals(url);}
        public Connection connect(String url,Properties properties)throws SQLException {
            if(!acceptsURL(url))return null;connections.incrementAndGet();
            startupProof="true".equals(properties.getProperty("readOnly")) && properties.getProperty("options","").contains("default_transaction_read_only=on")
                    && properties.getProperty("options","").contains("statement_timeout=10000") && properties.getProperty("options","").contains("lock_timeout=1000");
            AtomicBoolean localReadOnly=new AtomicBoolean(),wasClosed=new AtomicBoolean();
            return proxy(Connection.class,(object,method,args)->switch(method.getName()) {
                case "setReadOnly"->{localReadOnly.set((Boolean)args[0]);yield null;}
                case "isReadOnly"->localReadOnly.get();
                case "close"->{if(wasClosed.compareAndSet(false,true))closed.incrementAndGet();yield null;}
                case "prepareStatement"->{
                    check(args[0].equals("SELECT current_setting('default_transaction_read_only'), current_setting('transaction_read_only')"));
                    yield proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()) {
                        case "setQueryTimeout","close"->null;
                        case "executeQuery"->{
                            proofs.incrementAndGet();AtomicInteger row=new AtomicInteger();
                            yield proxy(ResultSet.class,(r,rm,ra)->switch(rm.getName()) {
                                case "next"->row.getAndIncrement()==0;
                                case "getString"->serverReadOnly?"on":"off";
                                case "close"->null;
                                default->throw new UnsupportedOperationException("SYNTHETIC_UNEXPECTED_RESULT_METHOD");
                            });
                        }
                        default->throw new UnsupportedOperationException("SYNTHETIC_UNEXPECTED_STATEMENT_METHOD");
                    });
                }
                default->throw new UnsupportedOperationException("SYNTHETIC_UNEXPECTED_CONNECTION_METHOD");
            });
        }
        public DriverPropertyInfo[] getPropertyInfo(String url,Properties info){return new DriverPropertyInfo[0];}
        public int getMajorVersion(){return 1;}public int getMinorVersion(){return 0;}public boolean jdbcCompliant(){return false;}
        public Logger getParentLogger(){return Logger.getLogger("incident-readback-synthetic");}
    }
    @SuppressWarnings("unchecked")private static<T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    private static IncidentApplicationReadback.ExportPlan sample(){var plan=new IncidentApplicationReadback.ExportPlan();plan.add("README.md",bytes("Synthetic test only."),"synthetic:test");return plan;}
    private static String[] options(){return new String[]{"--db-url","jdbc:postgresql://127.0.0.1:5432/database","--db-user","synthetic_user","--password-file",Path.of("synthetic-password.txt").toAbsolutePath().toString(),"--tenant","tenant-demo","--session","session-demo"};}
    private static String[] plus(String[] start,String...more){var result=Arrays.copyOf(start,start.length+more.length);System.arraycopy(more,0,result,start.length,more.length);return result;}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean condition){if(!condition)throw new AssertionError("SYNTHETIC_ASSERTION_FAILED");}
    private static void reject(Checked work)throws Exception {boolean rejected=false;try{work.run();}catch(Exception expected){rejected=true;}check(rejected);}
    private static void temporary(Temporary test)throws Exception {
        Path root=Files.createTempDirectory("incident-readback-test-").toAbsolutePath().normalize();
        try{test.run(root);}finally{
            check(root.getFileName().toString().startsWith("incident-readback-test-") && root.getParent()!=null);
            try(var paths=Files.walk(root)){
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){
                    check(path.toAbsolutePath().normalize().startsWith(root));Files.delete(path);
                }
            }
        }
    }
    @FunctionalInterface private interface Checked{void run()throws Exception;}
    @FunctionalInterface private interface Temporary{void run(Path root)throws Exception;}
}
