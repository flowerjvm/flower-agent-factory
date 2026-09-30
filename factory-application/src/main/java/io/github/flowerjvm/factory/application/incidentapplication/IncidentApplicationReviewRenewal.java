package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.*;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationActions.require;
import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationArtifacts.*;

/** Append-only, one-shot release window. It never rewrites the production order or inspected bytes. */
public record IncidentApplicationReviewRenewal(TenantId tenantId, BuildSessionId buildSessionId,
        DecisionPointId previousDecisionPointId, DecisionPointId decisionPointId,
        CertificationArtifactLock subject, long inspectedVersion, long reviewedProductVersion,
        Instant originalDeadlineAt, Instant openedAt, Instant deadlineAt,
        String actionRunId, String attemptTokenHash, String requestKey) {
    public IncidentApplicationReviewRenewal {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(buildSessionId);
        Objects.requireNonNull(previousDecisionPointId); Objects.requireNonNull(decisionPointId); Objects.requireNonNull(subject);
        Objects.requireNonNull(originalDeadlineAt); Objects.requireNonNull(openedAt); Objects.requireNonNull(deadlineAt);
        IncidentApplicationOrder.text(actionRunId,64); IncidentApplicationOrder.text(requestKey,255);
        require(attemptTokenHash != null && attemptTokenHash.matches("[0-9a-f]{64}")
                && !previousDecisionPointId.equals(decisionPointId) && inspectedVersion >= 0
                && reviewedProductVersion == inspectedVersion + 1 && !openedAt.isBefore(originalDeadlineAt)
                && deadlineAt.isAfter(openedAt) && !deadlineAt.isAfter(openedAt.plus(Duration.ofHours(48)))
                && openedAt.equals(openedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                && deadlineAt.equals(deadlineAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)));
    }
    public Artifact evidence() {
        var fields=new LinkedHashMap<String,Object>();
        fields.put("buildSessionId",buildSessionId.value()); fields.put("previousDecisionPointId",previousDecisionPointId.value());
        fields.put("decisionPointId",decisionPointId.value()); fields.put("releaseSubject",lockMap(subject));
        fields.put("inspectedVersion",inspectedVersion); fields.put("reviewedProductVersion",reviewedProductVersion);
        fields.put("originalDeadlineAt",originalDeadlineAt.toString()); fields.put("openedAt",openedAt.toString());
        fields.put("deadlineAt",deadlineAt.toString()); fields.put("actionRunId",actionRunId);
        fields.put("attemptTokenHash",attemptTokenHash); fields.put("requestKey",requestKey);
        return document(tenantId,"review-renewal",fields);
    }
}
