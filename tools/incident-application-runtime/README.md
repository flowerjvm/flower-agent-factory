# Incident Application runtime module catalog

This is the `incident-application` product line's standard source-module catalog.
The production builder combines these inputs with the exact sources resolved from a
currently eligible certified investigation component, generates immutable configuration,
and compiles a runnable product.

## Assembly contract

Java 21 compilation uses UTF-8, `-proc:none --release 21`, and pinned Jackson libraries:
databind/core `2.21.4` and annotations `2.21`.

- Common modules: `JsonSupport.java`, `HistoryStore.java`, `IncidentApplication.java`, `WebPage.java`.
- Exactly one `basic/SelectedHistoryStore.java` or `history/SelectedHistoryStore.java`.
- Unchanged component sources: `IncidentInvestigator.java` and `InvestigationAcceptanceApi.java`.
- Builder-generated `RuntimeConfiguration` containing the fixed product variant and title.

BASIC and HISTORY contain different compiled storage implementations. BASIC contains
no file-store implementation and cannot enable history through a live feature flag.
A mismatched variant/storage configuration prevents startup.

The investigation core is rule-based. It is an input component, not a new LLM or an
autonomous repair agent. These catalog modules are source inputs, not a certified app bundle.

## Product behavior

| API | Result |
| --- | --- |
| `GET /` | Korean JSON input, result/report display, download and selected history UI |
| `GET /api/config` | Fixed variant, history setting and title |
| `POST /api/investigations` | Normalized-input ID and unchanged component investigation result |
| `GET /api/history` | HISTORY result summaries; BASIC returns `HISTORY_DISABLED` |
| `GET /api/history/{id}` | Stored result envelope in HISTORY |
| `GET /api/history/{id}/report` | Stored Markdown attachment in HISTORY |

Input limits include 65,536 bytes and depth 16. Duplicate keys, trailing JSON,
invalid UTF-8 and domain-invalid input are rejected. HISTORY stores at most 100
records, deduplicates normalized input, and preserves results across normal process
restart. Exclusive ownership, unknown/corrupt files, symlinks and interrupted-write
residue are checked; uncertain storage is not silently repaired.

The consumer launcher keeps the application inside a network-none container and
relays only fixed product routes through an exact loopback listener. There is no
authentication, TLS, multi-tenant remote service, or continuous-operation contract.

## Historical module test

The exact investigation source snapshot is not included in this public repository.
Supply its directory and the matching Maven cache explicitly:

```powershell
.\tools\incident-application-runtime\Test-IncidentApplicationRuntime.ps1 `
  -ComponentSourceDirectory $exactHistoricalComponentDirectory `
  -MavenRepository $explicitMavenRepository
```

PowerShell 7.2+, Docker Linux engine, the existing pinned image, and exact dependency
hashes are required. Default paths retain the historical handoff layout and the current
user's Maven repository. This test preserves the original source/JAR hash checks;
path customization does not relax them. It is separate from Factory-owned whole-product
verification and does not issue a certification.

The optional Java native suite also accepts
`-Dfactory.incident.native.componentSourceDirectory=<exact-source-directory>` and
`-Dfactory.incident.native.powershell=<PowerShell-7-executable>`; its default shell is `pwsh`.
See [setup](../../docs/local-setup.md), [product lines](../../docs/product-lines.md),
and [validation](../../docs/validation.md).
