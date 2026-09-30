package io.github.flowerjvm.factory.application.decision;

import static io.github.flowerjvm.factory.application.decision.DecisionRecordTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.flower.action.runtime.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DecisionRecordInputTest {
    @ParameterizedTest
    @ValueSource(strings = {"principal", "tenantId", "projectId", "permissions", "authoritySnapshotRef", "createdAt", "decisionId", "selectedOption"})
    void authorityOrUnknownPayloadFieldsCannotBeSelfDeclared(String field) {
        var f = new DecisionRecordTestFixture(); var point = f.add("input", false); var authority = f.authority(point);
        var fields = new HashMap<>(input(point).toMap()); fields.put(field, "self-declared");
        var proposal = ActionProposal.builder(DecisionRecordAction.ACTION_ID).requestChannel(ActionRequestChannel.CLI)
                .proposerType(ActionProposerType.USER).requesterId(authority.principal()).input(fields).idempotencyKey("request").build();
        var result = f.runtime().handle(proposal, context(authority, point));
        assertEquals(ActionExecutionStatus.VALIDATION_FAILED, result.status()); assertTrue(result.output().isEmpty()); assertEquals(0, f.commits.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"negative", "maximum", "fractional", "string", "missing", "null-reason", "blank-reason", "large-reason", "unknown-outcome", "uppercase-hash", "high-surrogate", "low-surrogate", "control"})
    void malformedOrAmbiguousInputsFailClosed(String mutation) {
        var fields = new HashMap<>(new DecisionRecordInput(new DecisionPointId("point"), 0, HASH, DecisionOutcome.APPROVE, Optional.empty()).toMap());
        switch (mutation) {
            case "negative" -> fields.put("expectedDecisionPointVersion", -1);
            case "maximum" -> fields.put("expectedDecisionPointVersion", Long.MAX_VALUE);
            case "fractional" -> fields.put("expectedDecisionPointVersion", 0.0);
            case "string" -> fields.put("expectedDecisionPointVersion", "0");
            case "missing" -> fields.remove("reason");
            case "null-reason" -> fields.put("reason", null);
            case "blank-reason" -> fields.put("reason", " ");
            case "large-reason" -> fields.put("reason", "x".repeat(4097));
            case "unknown-outcome" -> fields.put("outcome", "approve");
            case "uppercase-hash" -> fields.put("subjectHash", "A".repeat(64));
            case "high-surrogate" -> fields.put("decisionPointId", "point-\uD800");
            case "low-surrogate" -> fields.put("reason", "reason-\uDC00");
            default -> fields.put("reason", "hidden\u0000value");
        }
        assertThrows(IllegalArgumentException.class, () -> DecisionRecordInput.from(fields));
    }

    @Test
    void canonicalPayloadRoundTripsWithoutContainingAnyOperatorAuthority() {
        var input = new DecisionRecordInput(new DecisionPointId("point-\uD83C\uDF38"), 0, HASH, DecisionOutcome.REQUEST_CHANGES,
                Optional.of("Add deterministic evidence for the reported case"));
        assertEquals(input, DecisionRecordInput.from(input.toMap())); assertEquals(5, input.toMap().size());
        assertThrows(UnsupportedOperationException.class, () -> input.toMap().put("tenantId", "forged"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "project", "principal", "permission", "authority"})
    void hostAuthorityAlsoRejectsMalformedUnicodeBeforeIdentityHashing(String field) {
        var f = new DecisionRecordTestFixture(); var point = f.add("unicode", false); var authority = f.authority(point);
        assertThrows(IllegalArgumentException.class, () -> new DecisionRecordAuthority(
                field.equals("tenant") ? new io.github.flowerjvm.factory.contracts.ids.TenantId("tenant-\uD800") : authority.tenantId(),
                field.equals("project") ? new io.github.flowerjvm.factory.contracts.ids.ProjectId("project-\uD800") : authority.projectId(),
                field.equals("principal") ? "operator-\uD800" : authority.principal(),
                field.equals("permission") ? Set.of("permission-\uD800") : authority.permissions(),
                field.equals("authority") ? ref("authority-\uD800") : authority.authoritySnapshotRef()));
    }
}
