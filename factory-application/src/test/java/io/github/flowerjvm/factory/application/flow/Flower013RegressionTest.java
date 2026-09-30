package io.github.flowerjvm.factory.application.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.event.InMemoryEventBus;
import io.github.flowerjvm.flower.core.flow.Flow;
import io.github.flowerjvm.flower.core.flow.FlowSnapshot;
import io.github.flowerjvm.flower.core.flow.FlowState;
import io.github.flowerjvm.flower.core.flow.LifecycleObserver;
import io.github.flowerjvm.flower.core.listener.FlowerListener;
import io.github.flowerjvm.flower.core.step.Step;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;
import io.github.flowerjvm.flower.core.time.ManualClock;
import io.github.flowerjvm.flower.core.trace.StepTransition;
import io.github.flowerjvm.flower.core.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Pins the Flower 0.1.3 lifecycle fixes that Factory PR3 relies on. */
class Flower013RegressionTest {

    @Test
    void exitObserverCancellationIsRecordedAsExternalCancelledTransition() {
        List<StepTransition> transitions = new ArrayList<>();
        Flow flow = Flow.builder("factory-flower-regression", "observer-cancel")
                .step("only", step(StepResult.done(), new AtomicInteger()))
                .build();
        flow.attach(new ManualClock(), InMemoryEventBus.create(), new LifecycleObserver() {
            @Override
            public void onStepEntered(String stepId) {
            }

            @Override
            public void onStepExited(String stepId) {
                flow.cancel();
            }

            @Override
            public void onStepTransitioned(StepTransition transition) {
                transitions.add(transition);
            }
        });

        flow.tick();

        assertEquals(FlowState.CANCELLED, flow.state());
        assertNull(flow.failureCause());
        assertEquals(1, transitions.size());
        assertEquals(StepTransition.Origin.EXTERNAL, transitions.getFirst().origin());
        assertEquals(StepTransition.Outcome.CANCELLED, transitions.getFirst().outcome());
    }

    @Test
    void nestedWorkerTickIsRejectedWithoutDuplicatingStepExecution() {
        Worker worker = Worker.builder("factory-regression-worker").build();
        AtomicInteger stepTicks = new AtomicInteger();
        AtomicReference<Throwable> nestedFailure = new AtomicReference<>();
        FlowerListener listener = new FlowerListener() {
            @Override
            public void onStepExited(FlowSnapshot flow, String stepId) {
                try {
                    worker.tickOnce();
                } catch (Throwable failure) {
                    nestedFailure.compareAndSet(null, failure);
                }
            }
        };
        Engine engine = Engine.builder()
                .clock(new ManualClock())
                .eventBus(InMemoryEventBus.create())
                .worker(worker)
                .listener(listener)
                .build();
        engine.attach();
        Flow flow = Flow.builder("factory-flower-regression", "nested-tick")
                .step("only", step(StepResult.done(), stepTicks))
                .build();

        worker.submit(flow);
        worker.tickOnce();

        assertEquals(FlowState.FINISHED, flow.state());
        assertEquals(1, stepTicks.get());
        Throwable failure = assertInstanceOf(IllegalStateException.class, nestedFailure.get());
        assertTrue(failure.getMessage().contains("does not allow a nested tickOnce() call"));
    }

    private static Step step(StepResult result, AtomicInteger ticks) {
        return new Step() {
            @Override
            protected StepResult onTick(StepContext context) {
                ticks.incrementAndGet();
                return result;
            }
        };
    }
}
