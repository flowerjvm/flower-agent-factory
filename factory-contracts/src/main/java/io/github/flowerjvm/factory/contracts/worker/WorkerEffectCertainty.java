package io.github.flowerjvm.factory.contracts.worker;

/** Whether an adapter failure proves that no external effect was accepted. */
public enum WorkerEffectCertainty {
    NO_EFFECT,
    UNCERTAIN
}
