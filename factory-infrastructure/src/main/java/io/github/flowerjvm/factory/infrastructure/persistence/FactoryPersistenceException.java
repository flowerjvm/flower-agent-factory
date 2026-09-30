package io.github.flowerjvm.factory.infrastructure.persistence;

/** Stable infrastructure failure wrapper; callers must not branch on vendor exception messages. */
public class FactoryPersistenceException extends RuntimeException {
    public FactoryPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
