package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.work.WorkerCancellationRequestDisposition;
import io.github.flowerjvm.factory.application.work.WorkerCancellationRequester;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.flow.FlowId;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Persists cancellation truth before sending the non-blocking Flower control command. */
public final class FactoryFlowCancellationService {
    public static final String CANCELLATION_EFFECT_CONFLICT =
            "BUILD_SESSION_CANCELLATION_EFFECT_CONFLICT";

    private final BuildSessionRepository sessions;
    private final Engine engine;
    private final FactoryFlowRegistry flows;
    private final Optional<WorkerCancellationRequester> workerCancellation;
    private final Map<ProductLineId, FactoryProductLineCancellationRequester>
            productLineCancellations;
    private final Clock clock;

    public FactoryFlowCancellationService(
            BuildSessionRepository sessions,
            Engine engine,
            FactoryProductLineRegistry productLines,
            WorkerCancellationRequester workerCancellation,
            Clock clock) {
        this(
                sessions,
                engine,
                new FactoryFlowRegistry(productLines, List.of()),
                workerCancellation,
                clock,
                List.of());
    }

    public FactoryFlowCancellationService(
            BuildSessionRepository sessions,
            Engine engine,
            FactoryProductLineRegistry productLines,
            WorkerCancellationRequester workerCancellation,
            Clock clock,
            Collection<? extends FactoryProductLineCancellationRequester>
                    productLineCancellations) {
        this(
                sessions,
                engine,
                new FactoryFlowRegistry(productLines, List.of()),
                workerCancellation,
                clock,
                productLineCancellations);
    }

    public FactoryFlowCancellationService(
            BuildSessionRepository sessions,
            Engine engine,
            FactoryFlowRegistry flows,
            WorkerCancellationRequester workerCancellation,
            Clock clock) {
        this(sessions, engine, flows, workerCancellation, clock, List.of());
    }

    public FactoryFlowCancellationService(
            BuildSessionRepository sessions,
            Engine engine,
            FactoryFlowRegistry flows,
            WorkerCancellationRequester workerCancellation,
            Clock clock,
            Collection<? extends FactoryProductLineCancellationRequester>
                    productLineCancellations) {
        this(sessions,engine,flows,Optional.of(Objects.requireNonNull(workerCancellation,"workerCancellation")),clock,productLineCancellations);
    }

    /** Module-only hosts may cancel installed line effects, but have no authority over Worker effects. */
    public FactoryFlowCancellationService(
            BuildSessionRepository sessions,Engine engine,FactoryFlowRegistry flows,Clock clock,
            Collection<? extends FactoryProductLineCancellationRequester> productLineCancellations) {
        this(sessions,engine,flows,Optional.empty(),clock,productLineCancellations);
    }

    private FactoryFlowCancellationService(
            BuildSessionRepository sessions,Engine engine,FactoryFlowRegistry flows,
            Optional<WorkerCancellationRequester> workerCancellation,Clock clock,
            Collection<? extends FactoryProductLineCancellationRequester> productLineCancellations) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.flows = Objects.requireNonNull(flows, "flows");
        this.workerCancellation = Objects.requireNonNull(workerCancellation, "workerCancellation");
        this.productLineCancellations = cancellationRequesters(productLineCancellations);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FlowCancellationDisposition cancel(TenantId tenantId, BuildSessionId sessionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionId, "sessionId");
        BuildSession current = sessions.find(tenantId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("BuildSession not found in tenant scope"));
        // Resolve before any durable mutation or external cancellation request. Unknown lines fail
        // closed without partially changing the BuildSession or its owning Action.
        FlowId flowId = flows.flowId(current);
        if (current.status() == BuildSessionStatus.CANCELLED) {
            return FlowCancellationDisposition.ALREADY_REQUESTED;
        }
        FactoryProductLineCancellationRequester productLineCancellation =
                productLineCancellations.get(current.productLineId());
        if(productLineCancellation==null && workerCancellation.isEmpty()) {
            // Do not stage a Worker cancel outbox that this host cannot publish, and never
            // report NO_ACTIVE_EFFECT merely because the Worker adapter is disabled.
            return FlowCancellationDisposition.CONFLICT;
        }
        if (productLineCancellation == null
                && flows.requiresProductLineCancellationRequester(current.productLineId())) {
            return current.status() == BuildSessionStatus.CANCELLING
                    ? preserveCancellationConflict(
                            tenantId,
                            sessionId,
                            "required ProductLine cancellation requester is not installed")
                    : FlowCancellationDisposition.CONFLICT;
        }
        if (productLineCancellation != null && !productLineCancellation.canRequest(current)) {
            return current.status() == BuildSessionStatus.CANCELLING
                    ? preserveCancellationConflict(
                            tenantId,
                            sessionId,
                            "ProductLine cancellation preflight no longer matches durable owner state")
                    : FlowCancellationDisposition.CONFLICT;
        }
        Instant now = clock.instant();
        if (current.status() != BuildSessionStatus.CANCELLING) {
            BuildSession cancelling = current.requestCancellation(now);
            if (cancelling != current && !sessions.compareAndSet(current, cancelling)) {
                return FlowCancellationDisposition.CONFLICT;
            }
        }
        BuildSession cancelling = sessions.find(tenantId, sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "BuildSession disappeared during cancellation"));
        if (cancelling.status() != BuildSessionStatus.CANCELLING
                || cancelling.cancellationRequestedAt().isEmpty()) {
            return FlowCancellationDisposition.CONFLICT;
        }
        FactoryCancellationRequestDisposition effectDisposition = productLineCancellation == null
                ? workerDisposition(workerCancellation.orElseThrow().request(tenantId, sessionId))
                : Objects.requireNonNull(
                        productLineCancellation.request(cancelling),
                        "ProductLine cancellation disposition");
        if (effectDisposition == FactoryCancellationRequestDisposition.CONFLICT) {
            return preserveCancellationConflict(
                    tenantId,
                    sessionId,
                    "ProductLine cancellation authority changed after the BuildSession cancellation CAS");
        }
        if (effectDisposition == FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT) {
            cancelling = sessions.find(tenantId, sessionId)
                    .orElseThrow(() -> new IllegalStateException("BuildSession disappeared during cancellation"));
            if (cancelling.status() == BuildSessionStatus.CANCELLING) {
                BuildSession cancelled = cancelling.confirmCancellation(
                        "BUILD_SESSION_CANCELLED_BEFORE_EXTERNAL_EFFECT",
                        "BuildSession cancellation had no active ProductLine-owned effect",
                        clock.instant());
                if (!sessions.compareAndSet(cancelling, cancelled)) {
                    return FlowCancellationDisposition.CONFLICT;
                }
            }
        }
        if (!engine.cancel(flowId)
                && !(effectDisposition
                                == FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT
                        && sessions.find(tenantId, sessionId)
                                .filter(value -> value.status() == BuildSessionStatus.CANCELLED)
                                .isPresent())) {
            return FlowCancellationDisposition.CONFLICT;
        }
        // Flower cancellation only stops orchestration. A ProductLine with an active governed
        // effect keeps the durable session CANCELLING until its own ledger records stop proof.
        return FlowCancellationDisposition.CANCELLATION_REQUESTED;
    }

    private FlowCancellationDisposition preserveCancellationConflict(
            TenantId tenantId, BuildSessionId sessionId, String message) {
        for (int attempt = 0; attempt < 3; attempt++) {
            BuildSession canonical = sessions.find(tenantId, sessionId).orElse(null);
            if (canonical == null || canonical.status() != BuildSessionStatus.CANCELLING) {
                return FlowCancellationDisposition.CONFLICT;
            }
            BuildSession review = canonical.manualReviewCancellation(
                    CANCELLATION_EFFECT_CONFLICT, message, clock.instant());
            if (sessions.compareAndSet(canonical, review)) {
                return FlowCancellationDisposition.CONFLICT;
            }
        }
        return FlowCancellationDisposition.CONFLICT;
    }

    private static FactoryCancellationRequestDisposition workerDisposition(
            WorkerCancellationRequestDisposition disposition) {
        return switch (Objects.requireNonNull(disposition, "Worker cancellation disposition")) {
            case CANCEL_OUTBOX_STAGED ->
                    FactoryCancellationRequestDisposition.EFFECT_CANCELLATION_PENDING;
            case NO_ACTIVE_EXTERNAL_EFFECT ->
                    FactoryCancellationRequestDisposition.NO_ACTIVE_EFFECT;
            case CONFLICT -> FactoryCancellationRequestDisposition.CONFLICT;
        };
    }

    private static Map<ProductLineId, FactoryProductLineCancellationRequester>
            cancellationRequesters(
                    Collection<? extends FactoryProductLineCancellationRequester> requesters) {
        Objects.requireNonNull(requesters, "productLineCancellations");
        var byProductLine =
                new LinkedHashMap<ProductLineId, FactoryProductLineCancellationRequester>();
        for (FactoryProductLineCancellationRequester requester : requesters) {
            Objects.requireNonNull(requester, "productLineCancellation");
            var productLineId = Objects.requireNonNull(
                    requester.productLineId(), "productLineCancellation.productLineId");
            if (byProductLine.putIfAbsent(productLineId, requester) != null) {
                throw new IllegalArgumentException(
                        "duplicate ProductLine cancellation requester: " + productLineId.value());
            }
        }
        return Map.copyOf(byProductLine);
    }
}
