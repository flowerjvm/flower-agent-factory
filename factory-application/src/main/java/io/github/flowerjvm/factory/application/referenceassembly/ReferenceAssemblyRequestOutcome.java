package io.github.flowerjvm.factory.application.referenceassembly;

import java.util.Objects;

/** Canonical ledger observed after a create, exact retry, or lifecycle-progressed retry. */
public record ReferenceAssemblyRequestOutcome(
        ReferenceAssemblyRequestDisposition disposition,
        ReferenceAssembly referenceAssembly) {

    public ReferenceAssemblyRequestOutcome {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(referenceAssembly, "referenceAssembly");
        if (disposition == ReferenceAssemblyRequestDisposition.CREATED
                && (referenceAssembly.status() != ReferenceAssemblyStatus.REQUESTED
                        || referenceAssembly.version() != 0)) {
            throw new IllegalArgumentException(
                    "created Reference Assembly outcome must contain a version-zero REQUESTED row");
        }
    }
}
