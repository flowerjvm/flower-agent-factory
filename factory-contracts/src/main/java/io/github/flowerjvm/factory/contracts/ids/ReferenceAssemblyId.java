package io.github.flowerjvm.factory.contracts.ids;

/** Exact identity of one durable Reference Assembly production ledger. */
public record ReferenceAssemblyId(String value) {
    public ReferenceAssemblyId {
        if (value == null
                || value.isBlank()
                || value.length() > 128
                || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)
                || value.matches("(?i).*(?:^|[:/@._-])(latest|head)(?:$|[:/@._-]).*")) {
            throw new IllegalArgumentException(
                    "referenceAssemblyId must be a bounded, exact non-floating identity");
        }
    }
}
