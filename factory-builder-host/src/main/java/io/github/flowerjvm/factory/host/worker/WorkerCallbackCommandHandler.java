package io.github.flowerjvm.factory.host.worker;

import io.github.flowerjvm.factory.application.work.WorkerCallbackCommand;

/** Thin host seam that keeps HTTP details out of application callback processing. */
@FunctionalInterface
public interface WorkerCallbackCommandHandler {
    void handle(WorkerCallbackCommand command);
}
