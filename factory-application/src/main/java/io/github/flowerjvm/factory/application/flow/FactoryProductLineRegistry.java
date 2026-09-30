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
import java.util.Set;

/** Fail-closed routing registry for installed specialized Factory production lines. */
public final class FactoryProductLineRegistry {
    private final Map<ProductLineId, FactoryProductLine> productLines;
    private final FlowFactoryRegistry flowFactories;

    public FactoryProductLineRegistry(Collection<? extends FactoryProductLine> productLines) {
        Objects.requireNonNull(productLines, "productLines");
        if (productLines.isEmpty()) {
            throw new IllegalArgumentException("at least one Factory product line must be registered");
        }
        var byId = new LinkedHashMap<ProductLineId, FactoryProductLine>();
        var byFlowType = new LinkedHashMap<String, ProductLineId>();
        var flowerRegistry = FlowFactoryRegistry.builder();
        for (FactoryProductLine productLine : productLines) {
            Objects.requireNonNull(productLine, "productLine");
            ProductLineId productLineId = Objects.requireNonNull(
                    productLine.productLineId(), "productLine.productLineId");
            String flowType = requireText(productLine.flowType(), "productLine.flowType");
            if (byId.putIfAbsent(productLineId, productLine) != null) {
                throw new IllegalArgumentException("duplicate Factory product line id: " + productLineId.value());
            }
            ProductLineId existingFlowOwner = byFlowType.putIfAbsent(flowType, productLineId);
            if (existingFlowOwner != null) {
                throw new IllegalArgumentException("duplicate Factory product line flow type: " + flowType);
            }
            flowerRegistry.register(flowType, productLine);
        }
        this.productLines = Map.copyOf(byId);
        this.flowFactories = flowerRegistry.build();
    }

    public FactoryProductLine require(ProductLineId productLineId) {
        Objects.requireNonNull(productLineId, "productLineId");
        FactoryProductLine productLine = productLines.get(productLineId);
        if (productLine == null) {
            throw new IllegalArgumentException("unregistered Factory product line: " + productLineId.value());
        }
        return productLine;
    }

    public FlowId flowId(BuildSession session) {
        Objects.requireNonNull(session, "session");
        FactoryProductLine productLine = require(session.productLineId());
        return FlowId.of(productLine.flowType(), session.buildSessionId().value());
    }

    public boolean requiresProductLineCancellationRequester(ProductLineId productLineId) {
        return require(productLineId).requiresProductLineCancellationRequester();
    }

    public Flow create(BuildSession session, String flowRunId, String traceId) {
        Objects.requireNonNull(session, "session");
        return Objects.requireNonNull(
                require(session.productLineId()).create(session, flowRunId, traceId),
                "product line Flow");
    }

    public FlowFactoryRegistry flowFactoryRegistry() {
        return flowFactories;
    }

    public Set<ProductLineId> productLineIds() {
        return productLines.keySet();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
