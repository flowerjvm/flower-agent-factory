package io.github.flowerjvm.factory.application.flow;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.flower.core.flow.Flow;
import io.github.flowerjvm.flower.core.flow.FlowId;
import io.github.flowerjvm.flower.core.recovery.FlowFactoryRegistry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Composes primary ProductLine flows with their separately recoverable lifecycle continuations. */
public final class FactoryFlowRegistry {
    private final FactoryProductLineRegistry productLines;
    private final Map<String, FactoryContinuationFlow> continuations;
    private final FlowFactoryRegistry recoveryFactories;

    public FactoryFlowRegistry(
            FactoryProductLineRegistry productLines,
            Collection<? extends FactoryContinuationFlow> continuations) {
        this.productLines = Objects.requireNonNull(productLines, "productLines");
        Objects.requireNonNull(continuations, "continuations");

        var byType = new LinkedHashMap<String, FactoryContinuationFlow>();
        var builder = FlowFactoryRegistry.builder();
        FlowFactoryRegistry primary = productLines.flowFactoryRegistry();
        for (String flowType : primary.flowTypes()) {
            builder.register(flowType, primary::create);
        }
        for (FactoryContinuationFlow continuation : continuations) {
            Objects.requireNonNull(continuation, "continuation");
            String flowType = requireText(continuation.flowType(), "continuation.flowType");
            if (primary.contains(flowType) || byType.putIfAbsent(flowType, continuation) != null) {
                throw new IllegalArgumentException("duplicate Factory Flow type: " + flowType);
            }
            builder.register(flowType, continuation);
        }
        this.continuations = Map.copyOf(byType);
        this.recoveryFactories = builder.build();
    }

    /** Resolves the one active control Flow before any cancellation-side durable mutation. */
    public FlowId flowId(BuildSession session) {
        Objects.requireNonNull(session, "session");
        FactoryProductLine productLine = productLines.require(session.productLineId());
        FactoryContinuationFlow owner = continuationOwner(session, true);
        if (owner != null) {
            return FlowId.of(owner.flowType(), session.buildSessionId().value());
        }
        if (productLine.requiresContinuation(session)) {
            throw new IllegalStateException(
                    "BuildSession is in a continuation phase without an installed control owner");
        }
        return productLines.flowId(session);
    }

    /** Creates the continuation selected by durable session state; zero or ambiguous owners fail closed. */
    public Flow createContinuation(BuildSession session, String flowRunId, String traceId) {
        Objects.requireNonNull(session, "session");
        productLines.require(session.productLineId());
        FactoryContinuationFlow owner = continuationOwner(session, false);
        if (owner == null) {
            throw new IllegalArgumentException("BuildSession has no installed continuation Flow owner");
        }
        return Objects.requireNonNull(
                owner.create(session, flowRunId, traceId), "continuation Flow");
    }

    public FlowFactoryRegistry flowFactoryRegistry() {
        return recoveryFactories;
    }

    public boolean requiresProductLineCancellationRequester(ProductLineId productLineId) {
        return productLines.requiresProductLineCancellationRequester(productLineId);
    }

    private FactoryContinuationFlow continuationOwner(BuildSession session, boolean controlRouting) {
        FactoryContinuationFlow found = null;
        for (FactoryContinuationFlow continuation : continuations.values()) {
            boolean matches = controlRouting
                    ? continuation.controls(session)
                    : continuation.owns(session);
            if (matches) {
                if (found != null) {
                    throw new IllegalStateException(
                            "multiple continuation Flows own BuildSession "
                                    + session.buildSessionId().value());
                }
                found = continuation;
            }
        }
        return found;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
