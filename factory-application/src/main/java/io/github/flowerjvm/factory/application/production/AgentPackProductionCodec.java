package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;

/** Strict canonical JSON boundary for stored production inputs, not a new Worker protocol. */
public interface AgentPackProductionCodec {
    byte[] writePlan(AgentPackProductionPlan plan);
    AgentPackProductionPlan readPlan(byte[] bytes);
    byte[] writeWorkerInput(CodingWorkerInputManifest input);
    byte[] normalizeBlueprint(byte[] bytes);
    java.util.List<String> blueprintSourceFiles(byte[] bytes);
}
