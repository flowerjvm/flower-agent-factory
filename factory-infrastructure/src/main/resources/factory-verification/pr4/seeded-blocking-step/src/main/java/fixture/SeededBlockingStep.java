package fixture;

import io.github.flowerjvm.flower.Step;
import io.github.flowerjvm.flower.StepContext;
import io.github.flowerjvm.flower.StepResult;

final class SeededBlockingStep extends Step {
    @Override
    protected StepResult onTick(StepContext context) {
        try {
            Thread.sleep(1L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return StepResult.stay();
    }
}
