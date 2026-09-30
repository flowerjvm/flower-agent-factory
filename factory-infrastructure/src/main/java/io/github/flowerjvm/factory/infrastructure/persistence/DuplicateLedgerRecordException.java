package io.github.flowerjvm.factory.infrastructure.persistence;

/** Raised when an insert-only or create-once ledger identity already exists. */
public final class DuplicateLedgerRecordException extends FactoryPersistenceException {
    public DuplicateLedgerRecordException(String operation, Throwable cause) {
        super(operation + " failed because the durable identity already exists", cause);
    }
}
