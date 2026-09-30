package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** One bounded, immutable application order; contains no user data or execution credentials. */
public record IncidentApplicationOrder(BuildSessionId buildSessionId, TenantId tenantId,
        ProjectId projectId, String requestKey, Variant variant,
        CertifiedAgentComponentRef component, Instant deadlineAt) {
    public static final ProductLineId PRODUCT_LINE_ID = ProductLineId.INCIDENT_APPLICATION;
    public enum Variant { BASIC, HISTORY }
    public IncidentApplicationOrder {
        Objects.requireNonNull(buildSessionId); Objects.requireNonNull(tenantId); Objects.requireNonNull(projectId);
        text(buildSessionId.value(), 128); text(tenantId.value(), 128); text(projectId.value(), 128);
        requestKey = text(requestKey, 255); Objects.requireNonNull(variant); Objects.requireNonNull(component);
        Objects.requireNonNull(deadlineAt);
        if (!deadlineAt.equals(deadlineAt.truncatedTo(ChronoUnit.MILLIS))) throw invalid();
        if (!component.componentRole().equals("embedded-agent-pack")) throw invalid();
    }
    public static String text(String text, int limit) {
        if (text == null || text.isBlank() || text.length() > limit || !text.equals(text.trim())
                || text.chars().anyMatch(Character::isISOControl)) throw invalid();
        return text;
    }
    public static IllegalArgumentException invalid() { return new IllegalArgumentException("INCIDENT_APPLICATION_INVALID"); }
}
