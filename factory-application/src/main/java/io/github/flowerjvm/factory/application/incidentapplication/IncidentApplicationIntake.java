package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.application.certification.*;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.contracts.certification.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Real current producer-ledger selection, followed by full evidence resolution on the control lane. */
public final class IncidentApplicationIntake {
    private final CertificationRepository certifications;
    private final CertifiedAgentComponentReadGate fullComponents;
    private final IncidentApplicationProductionTool tool;
    private final IncidentApplicationLedger ledger;
    private final Clock clock;
    public IncidentApplicationIntake(CertificationRepository certifications, CertifiedAgentComponentReadGate fullComponents,
            IncidentApplicationProductionTool tool, IncidentApplicationLedger ledger, Clock clock) {
        this.certifications = Objects.requireNonNull(certifications); this.fullComponents = Objects.requireNonNull(fullComponents);
        this.tool = Objects.requireNonNull(tool); this.ledger = Objects.requireNonNull(ledger); this.clock = Objects.requireNonNull(clock);
    }
    public IncidentApplicationOrder requireSelection(TenantId tenant, String requestKey, Map<String,Object> input, boolean executing) {
        IncidentApplicationActions.shape(IncidentApplicationActions.INTAKE, input);
        var certification = certifications.find(tenant, new CertificationId(text(input.get("certificationId")))).orElseThrow(IncidentApplicationOrder::invalid);
        var lock = certification.inputLock(); Instant now = clock.instant();
        IncidentApplicationActions.require(certification.status() == CertificationStatus.CERTIFIED && certification.revokedAt().isEmpty()
                && lock.tenantId().equals(tenant) && lock.candidateHash().sha256().equals(input.get("candidateHash"))
                && certification.certificationManifest().orElseThrow().reference().value().equals(input.get("certificationManifestRef"))
                && certification.certificationManifest().orElseThrow().hash().sha256().equals(input.get("certificationManifestHash"))
                && lock.productContractBundle().equals(MaintenanceInvestigationProductContract.lock())
                && lock.apiSignatureIndex().equals(MaintenanceInvestigationProductContract.apiSignatureIndexLock())
                && lock.gateProfile().equals(MaintenanceInvestigationProductContract.GATE_PROFILE)
                && certification.expiresAt().map(now::isBefore).orElse(true) && !now.isBefore(certification.updatedAt()));
        var component = new CertifiedAgentComponentRef(CertifiedAgentComponentRef.SCHEMA_VERSION, "embedded-agent-pack", lock.productLineId(), lock.artifactType(),
                certification.certificationId(), certification.certificationManifest().orElseThrow(), lock.candidateId(), lock.candidateHash(), lock.sourceManifest(),
                certification.inputLockArtifact(), lock.verificationRunId(), lock.verificationResultManifest(), lock.compatibilityDescriptor(),
                certification.certificationEvidence().orElseThrow(), lock.certificationProfile());
        var order = new IncidentApplicationOrder(new BuildSessionId(text(input.get("buildSessionId"))), tenant, new ProjectId(text(input.get("projectId"))), requestKey,
                IncidentApplicationOrder.Variant.valueOf(text(input.get("variant"))), component, Instant.parse(text(input.get("deadlineAt"))));
        var existing = ledger.find(tenant, order.buildSessionId());
        if (existing.isPresent()) IncidentApplicationActions.require(existing.orElseThrow().order().equals(order));
        else if (executing) IncidentApplicationActions.require(now.isBefore(order.deadlineAt()) && !order.deadlineAt().isAfter(now.plus(Duration.ofHours(48))));
        return order;
    }
    public IncidentApplicationProduct accept(TenantId tenant, String requestKey, Map<String,Object> input, ActionRun owner) {
        var order = requireSelection(tenant, requestKey, input, true);
        var resolved = fullComponents.resolve(tenant, order.component());
        IncidentApplicationActions.require(resolved.reference().equals(order.component()));
        var workOrder = tool.plan(order);
        IncidentApplicationActions.require(workOrder.order().equals(order));
        requireSelection(tenant, requestKey, input, true);
        return ledger.accept(workOrder, owner, clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }
    public static Map<String,Object> input(IncidentApplicationOrder order) {
        return Map.of("buildSessionId", order.buildSessionId().value(), "projectId", order.projectId().value(), "variant", order.variant().name(),
                "certificationId", order.component().certificationId().value(), "candidateHash", order.component().candidateHash().sha256(),
                "certificationManifestRef", order.component().certificationManifest().reference().value(),
                "certificationManifestHash", order.component().certificationManifest().hash().sha256(), "deadlineAt", order.deadlineAt().toString());
    }
    private static String text(Object value) { return IncidentApplicationActions.text(value); }
}
