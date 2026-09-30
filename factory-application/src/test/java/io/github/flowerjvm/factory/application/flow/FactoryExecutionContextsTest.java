package io.github.flowerjvm.factory.application.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import org.junit.jupiter.api.Test;

class FactoryExecutionContextsTest {
    private static final BuildSessionId SESSION_ID = new BuildSessionId("production-session");

    @Test
    void mapsAllSixIndependentIdentityValuesWithoutProductState() {
        ExecutionContext context = FactoryExecutionContexts.create(
                SESSION_ID, "tenant", "initiator", "flow-run", "trace", "project");

        assertEquals(expectedContext(), context);
        assertEquals("tenant", context.tenantIdOrNull());
        assertEquals("initiator", context.userIdOrNull());
        assertEquals("production-session", context.sessionIdOrNull());
        assertEquals("flow-run", context.runIdOrNull());
        assertEquals("trace", context.traceIdOrNull());
        assertEquals("project", context.correlationIdOrNull());
    }

    @Test
    void historicalAgentFactoryEntryPointPreservesTheSameIdentityContract() {
        assertEquals(expectedContext(), CreateCustomerAgentFlowFactory.executionContext(
                SESSION_ID, "tenant", "initiator", "flow-run", "trace", "project"));
    }

    @Test
    void rejectsMissingBuildSessionIdentity() {
        NullPointerException failure = assertThrows(NullPointerException.class,
                () -> FactoryExecutionContexts.create(
                        null, "tenant", "initiator", "flow-run", "trace", "project"));

        assertEquals("buildSessionId", failure.getMessage());
    }

    @Test
    void rejectsEachNullOrBlankTextIdentityWithoutInventingDefaults() {
        String[] names = {"tenantId", "initiator", "flowRunId", "traceId", "projectId"};
        for (int index = 0; index < names.length; index++) {
            for (String invalid : new String[] {null, "", " \t\n"}) {
                String[] values = {"tenant", "initiator", "flow-run", "trace", "project"};
                values[index] = invalid;

                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> FactoryExecutionContexts.create(
                                SESSION_ID, values[0], values[1], values[2], values[3], values[4]));

                assertEquals(names[index] + " must not be blank", failure.getMessage());
            }
        }
    }

    private static ExecutionContext expectedContext() {
        return ExecutionContext.builder()
                .tenantId("tenant")
                .userId("initiator")
                .sessionId("production-session")
                .runId("flow-run")
                .traceId("trace")
                .correlationId("project")
                .build();
    }
}
