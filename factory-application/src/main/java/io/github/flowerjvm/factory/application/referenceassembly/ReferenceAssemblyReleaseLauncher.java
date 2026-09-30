package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import java.time.Instant;

/** Governed release proposal port used by the Reference Assembly Flow. */
@FunctionalInterface
public interface ReferenceAssemblyReleaseLauncher {
    ActionExecutionResult ensureProposed(
            BuildSession buildSession,
            ReferenceAssembly referenceAssembly,
            io.github.flowerjvm.flower.core.context.ExecutionContext flowIdentity,
            Instant proposedAt);
}
