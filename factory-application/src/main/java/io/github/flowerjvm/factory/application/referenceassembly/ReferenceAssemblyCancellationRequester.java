package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseAction;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionExecutor;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.flow.FactoryCancellationRequestDisposition;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineCancellationRequester;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.util.Map;
import java.util.Objects;

/**
 * Cancels an Action-owned Reference Assembly release without claiming that shipment was undone.
 *
 * <p>The BuildSession is already durably CANCELLING when {@link #request(BuildSession)} runs.
 * A cancelled Action with a non-terminal V14 intent remains pending until the bounded release
 * runner reconciles that intent to a terminal non-completed state.
 */
public final class ReferenceAssemblyCancellationRequester
        implements FactoryProductLineCancellationRequester {
    public static final String REASON_CODE = "FACTORY_CANCELLATION_REQUESTED";

    private final ReferenceAssemblyRepository assemblies;
    private final ReferenceAssemblyReleaseDispatchIntentRepository intents;
    private final RunStore actionRuns;
    private final CompletableActionRuntime actionRuntime;

    public ReferenceAssemblyCancellationRequester(
            ReferenceAssemblyRepository assemblies,
            ReferenceAssemblyReleaseDispatchIntentRepository intents,
            RunStore actionRuns,
            CompletableActionRuntime actionRuntime) {
        this.assemblies = Objects.requireNonNull(assemblies, "assemblies");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.actionRuns = Objects.requireNonNull(actionRuns, "actionRuns");
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
    }

    @Override
    public ProductLineId productLineId() {
        return ProductLineId.REFERENCE_ASSEMBLY;
    }

    @Override
    public boolean canRequest(BuildSession session) {
        Objects.requireNonNull(session, "session");
        if (!ProductLineId.REFERENCE_ASSEMBLY.equals(session.productLineId())) {
            return false;
        }
        ReferenceAssembly assembly = assemblies
                .findByBuildSession(session.tenantId(), session.buildSessionId())
                .orElse(null);
        if (assembly == null) {
            return true;
        }
        if (assembly.status() == ReferenceAssemblyStatus.RELEASED) {
            return false;
        }
        ReferenceAssemblyReleaseDispatchIntent intent = intents
                .findLatest(assembly.tenantId(), assembly.referenceAssemblyId())
                .orElse(null);
        String actionRunId = assembly.releaseActionRunId().orElse(null);
        if (actionRunId == null) {
            return intent == null;
        }
        if (intent == null || !actionRunId.equals(intent.actionRunId())) {
            return false;
        }
        FactoryCancellationRequestDisposition observed =
                classify(assembly, intent, actionRuns.find(actionRunId).orElse(null));
        return observed != FactoryCancellationRequestDisposition.CONFLICT;
    }

    @Override
    public FactoryCancellationRequestDisposition request(BuildSession cancellingSession) {
        Objects.requireNonNull(cancellingSession, "cancellingSession");
        if (!ProductLineId.REFERENCE_ASSEMBLY.equals(cancellingSession.productLineId())
                || cancellingSession.status() != BuildSessionStatus.CANCELLING
                || cancellingSession.cancellationRequestedAt().isEmpty()) {
            return FactoryCancellationRequestDisposition.CONFLICT;
        }

        ReferenceAssembly assembly = assemblies
                .findByBuildSession(
                        cancellingSession.tenantId(), cancellingSession.buildSessionId())
                .orElse(null);
        if (assembly == null) {
            return FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT;
        }
        if (assembly.status() == ReferenceAssemblyStatus.RELEASED) {
            return FactoryCancellationRequestDisposition.CONFLICT;
        }

        ReferenceAssemblyReleaseDispatchIntent intent = intents
                .findLatest(assembly.tenantId(), assembly.referenceAssemblyId())
                .orElse(null);
        String actionRunId = assembly.releaseActionRunId().orElse(null);
        if (actionRunId == null) {
            return intent == null
                    ? FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT
                    : FactoryCancellationRequestDisposition.CONFLICT;
        }
        if (intent == null || !actionRunId.equals(intent.actionRunId())) {
            return FactoryCancellationRequestDisposition.CONFLICT;
        }

        ActionRun actionRun = actionRuns.find(actionRunId).orElse(null);
        FactoryCancellationRequestDisposition observed =
                classify(assembly, intent, actionRun);
        if (observed != null) {
            return observed;
        }

        try {
            actionRuntime.cancel(actionRunId, REASON_CODE);
        } catch (RuntimeException cancellationUncertain) {
            // The runtime may have committed CANCELLED before its caller lost the outcome.
            // Canonical Action/domain/intent state below decides whether reconciliation remains.
        }
        return observeCanonical(cancellingSession, assembly.referenceAssemblyId());
    }

    private FactoryCancellationRequestDisposition observeCanonical(
            BuildSession session, ReferenceAssemblyId referenceAssemblyId) {
        ReferenceAssembly assembly = assemblies
                .find(session.tenantId(), referenceAssemblyId)
                .orElse(null);
        if (assembly == null || !assembly.buildSessionId().equals(session.buildSessionId())) {
            return FactoryCancellationRequestDisposition.CONFLICT;
        }
        ReferenceAssemblyReleaseDispatchIntent intent = intents
                .findLatest(session.tenantId(), referenceAssemblyId)
                .orElse(null);
        ActionRun actionRun = assembly.releaseActionRunId()
                .flatMap(actionRuns::find)
                .orElse(null);
        FactoryCancellationRequestDisposition observed =
                classify(assembly, intent, actionRun);
        return observed == null
                ? FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING
                : observed;
    }

    /** Returns {@code null} only while an exactly owned non-terminal Action can be cancelled. */
    private static FactoryCancellationRequestDisposition classify(
            ReferenceAssembly assembly,
            ReferenceAssemblyReleaseDispatchIntent intent,
            ActionRun actionRun) {
        if (assembly == null
                || assembly.status() == ReferenceAssemblyStatus.RELEASED
                || intent == null
                || actionRun == null
                || !ReferenceAssemblyReleaseDispatchRunner.hasImmutableOwnerBinding(
                        intent, actionRun, assembly)) {
            return FactoryCancellationRequestDisposition.CONFLICT;
        }
        if (actionRun.status() == ActionRunStatus.SUCCEEDED) {
            return FactoryCancellationRequestDisposition.CONFLICT;
        }
        if (actionRun.status().isTerminal()) {
            if (!hasTerminalExternalBinding(intent, actionRun)) {
                return FactoryCancellationRequestDisposition.CONFLICT;
            }
            if (intent.status().isTerminal()) {
                return FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT;
            }
            return actionRun.status() == ActionRunStatus.CANCELLED
                    ? FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING
                    : FactoryCancellationRequestDisposition.CONFLICT;
        }
        if (actionRun.status() == ActionRunStatus.RUNNING
                && intent.status()
                        == ReferenceAssemblyReleaseDispatchIntentStatus.ORPHANED_BEFORE_WAITING) {
            return FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT;
        }
        if (intent.status().isTerminal()) {
            return FactoryCancellationRequestDisposition.CONFLICT;
        }
        if (actionRun.status() == ActionRunStatus.RUNNING) {
            // The deferred executor may have atomically committed the intent while the runtime
            // has not yet parked the Action. Immutable attempt ownership is the available fence.
            return null;
        }
        if (actionRun.status() == ActionRunStatus.WAITING_EXTERNAL) {
            return ReferenceAssemblyReleaseDispatchRunner.hasWaitingOwnerBinding(intent, actionRun)
                    ? null
                    : FactoryCancellationRequestDisposition.CONFLICT;
        }
        return FactoryCancellationRequestDisposition.CONFLICT;
    }

    private static boolean hasTerminalExternalBinding(
            ReferenceAssemblyReleaseDispatchIntent intent, ActionRun actionRun) {
        boolean exactParkedOwner = intent.operationId().equals(actionRun.externalOperationId())
                && ReferenceAssemblyReleaseDeadlines.actionDueAt(intent.deadlineAt()).equals(actionRun.dueAt())
                && actionRun.externalOperationMetadata().equals(Map.of(
                        "dispatchMode",
                        ReferenceAssemblyReleaseActionExecutor.DISPATCH_MODE,
                        ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                        intent.referenceAssemblyId().value()));
        boolean cancelledDuringDispatchCommitWindow = actionRun.externalOperationId().isBlank()
                && actionRun.dueAt() == null
                && actionRun.externalOperationMetadata().isEmpty();
        return exactParkedOwner || cancelledDuringDispatchCommitWindow;
    }
}
