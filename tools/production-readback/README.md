# Production full readback probe

`FactoryProductionReadback.java` is an independent Java 21 source-file probe. It does not bootstrap
Spring, run migrations, start Flower/pumps, submit Actions, stage artifacts, run Workers/models,
spawn processes, or construct test fixtures. It calls the existing production full component and
released-product read gates, never the internal tick projection. No application JAR change is needed.

Execution is an explicit operator action. Writing this source does not prove a live readback succeeded.
Do not use real-production acceptance/release Actions or fixture test methods as substitute queries.

## Inputs and example shape

Exactly these options are required, each followed by a separate value:

- `--db-url`: queryless local PostgreSQL URL, for example `jdbc:postgresql://127.0.0.1:5432/database_name`.
  Only loopback hosts and an explicit port/database are accepted; URL properties/userinfo are rejected.
- `--db-user`: explicit PostgreSQL role. Prefer an existing SELECT-only role; this tool creates no roles.
- `--password-file`: absolute normalized path to an existing non-link regular UTF-8 password file,
  1–4096 bytes, with no BOM. One final LF or CRLF is optional; other password whitespace is preserved.
  The operator owns file ACLs. Do not point it at Codex `auth.json`, `.env`, or another credential store.
- `--tenant`: trusted tenant ID, not untrusted request authority.
- `--certification-id`: exact canonical Certification ID.
- Optional `--assembly-id`: exact released Reference Assembly ID; it must consume the selected component.
- Optional `--export-dir`: absolute normalized path to a **new** directory under an existing, operator-owned
  non-link parent. Requires `--assembly-id`; omitted means the original metadata-only/no-file-write mode.

Example **shape only**; replace placeholders with already approved explicit local values. Never put a
password value in command arguments, paste it into the console, or enable JDBC credential/SQL tracing.

```powershell
& $java21 --class-path $readbackClasspath --source 21 `
  tools/production-readback/FactoryProductionReadback.java `
  --db-url $explicitLocalJdbcUrl --db-user $explicitDatabaseUser `
  --password-file $protectedPasswordFile --tenant $trustedTenant `
  --certification-id $exactCertificationId --assembly-id $exactReleasedAssemblyId
```

Omit both `--assembly-id` tokens for Pack-only checks. Reverse provenance is still checked: every
released result for the selected same-tenant certification must pass the full released read gate.
At most 100 reverse results are accepted; overflow fails rather than claiming a truncated list complete.
No source, verification logs, Action reason/message, password, URL or private transcript is printed.

## Explicit released demo handoff export

After the exact RA release and full released gate succeed, add `--export-dir $newHandoffDirectory` to
the selected-assembly invocation above. This is an explicitly requested **local copy of an already
approved released snapshot**, not another production/approval/certification/release action, a database
write, deployment, or execution of the generated code. No Spring/Action/Worker/model is started.
Default readback behavior and its stdout schema are preserved when the option is absent.

This bounded exporter supports the existing `factory-maintenance-investigation-v1` demo product only:

```text
product-source/              exact original pom.xml and src/{main,test}/java/**/*.java bytes
provenance/                  selected source/certification/assembly/release and public contract manifests
demonstration/input.json     NORMAL input extracted from the persisted, hash-locked code-owned case suite
demonstration/actual-output.json
                            NORMAL invocation 0 extracted from persisted actual candidate output
demonstration/report.md      that actual output's reportMarkdown, unchanged
demonstration/acceptance-actual.json
demonstration/acceptance-summary.json
README.md                   actual verification completion date, meaning, provenance and limitations
handoff-index.json           every other file's relative path, byte size, SHA-256 and origin; written last
```

The full production component/released gates run **before** export planning. All exported artifact bytes
are re-read and rehashed; the source tree is recomputed. The Factory acceptance validator joins source,
summary, actual output and pinned contract. The sample additionally requires exactly one `NORMAL` case,
one actual invocation `0`, and exact matching output. The golden expected value is used only as an
assertion; it is never copied and labeled as actual output. No new verification execution occurs.
The README uses the canonical VerificationRun completion time, not the export date, for actual execution.

The allowlist intentionally omits Worker plans/inputs/context, raw transcripts, credentials, verification
logs, certification input-lock bytes, dependency cache and arbitrary referenced artifacts. Original
manifest **references** to omitted artifacts remain intact; the tool does not recursively follow them.
This is not a self-contained offline certification replay. It is not a runnable Reference Assembly app,
universal Agent Runtime ABI, deployment bundle, or standalone maintenance service. It illustrates the
reusable Factory foundation's production → inspection → certification → composition → shipment path.

Export controls: no existing destination, overwrite, relative/traversal/ADS/device-name source path,
case-colliding files, symlink/junction/non-regular ancestor, `.codex`/`.git`/`.ssh` destination hierarchy,
arbitrary source file type or known secret material. Limits are 2,048 payload files, 8 MiB per file,
64 MiB total payload, 240-character relative paths and 1 MiB index. The operator must keep the destination
parent private and stable: path checks do not replace OS ACLs or establish a cross-process filesystem lease.
No consumer should build or execute exported POM/source/tests just because the directory was opened.

All payloads and the final directory membership are checked again after writing. The index is written
last, re-read and checked. Only then can stdout include `handoff.status=HANDOFF_EXPORT_PASSED` and its
`indexSha256`; the index intentionally does not hash itself. A failure prints only the bounded error stage,
never partial success. Partial payloads may remain for operator inspection; the writer removes its own
completion index on a detected post-index failure when ownership can still be proved. Do not consume a
failed invocation, a missing/invalid index, a mismatched hash or any extra/missing file as a completed
handoff. Existing/partial destinations are never automatically deleted, repaired, resumed or overwritten;
choose a fresh explicit destination after investigating the failure.

The current-certification check still occurs after all database artifact reads. The report is a bounded
read-interval observation, not an atomic DB/filesystem snapshot or permanently valid certification.
Subsequent consumers must revalidate current full production gates. No new product authority is granted.

## Standalone synthetic regression tests

`FactoryProductionReadbackTest.java` tests options, opt-in behavior, exact copy/index integrity, duplicate
destination/no-overwrite, traversal/device/ADS/case rejection, protected/linked parents, quotas, known
secret rejection, and the strict persisted-case/actual sample join. It uses only private temporary
directories and synthetic data: it does not access a database, call a full production gate, or prove live
shipment. Root/operator owns compilation and execution; use the matching classpath described below:

```powershell
& $javac21 --release 21 --class-path $readbackClasspath -d $newTemporaryTestClasses `
  tools/production-readback/FactoryProductionReadback.java `
  tools/production-readback/FactoryProductionReadbackTest.java
& $java21 --class-path "$newTemporaryTestClasses;$readbackClasspath" `
  -Dfactory.readback.test.requireLinks=true FactoryProductionReadbackTest
```

The link test reports an explicit skip if OS privileges do not permit link creation; the property above
makes that condition a failure instead. These standalone tests are not automatically counted in Maven's
reactor totals. A real full-gated export and its hashes must be recorded separately before claiming a
production handoff artifact exists.

The current standalone suite does not inject I/O failures during completion-index creation or race a
post-index file mutation. Those failure paths have source review, not a fault-injection execution claim.
Actual readback/export and a rejected repeat export to the already completed directory are separately
recorded in `docs/32-production-validation-goal.md`; they do not remove that test limitation.

## Read-only enforcement and limits

Every JDBC connection is opened with PostgreSQL startup options
`default_transaction_read_only=on`, `statement_timeout=10000`, and `lock_timeout=1000`; JDBC read-only is
also enabled. Before handing any connection to a repository, the probe checks both
`current_setting('default_transaction_read_only')` and `current_setting('transaction_read_only')` are
`on`, and `Connection.isReadOnly()` is true. Mismatch closes the connection and fails. The only SQL
written in the probe is this fixed SELECT. Existing adapters are called only through their read methods;
`ArtifactStore.store` explicitly throws. No DDL/DML/migration/role/seed/approval API is invoked.

Connection acquisition is limited to 4,096 connections and a two-minute read interval, with five-second
connect and fifteen-second socket timeouts. These limits do not constitute a durable lease or a claim
of an atomic database snapshot. The selected certification is checked again at the end for unchanged
version/content/current status and expiry. Every downstream consumer must still perform its own current
full read gate. Operators should also enforce an outer process timeout for abnormal JVM/driver failures.

Success is one bounded JSON object (maximum 64 KiB), `factory.production-readback.v1` /
`FULL_READBACK_PASSED`, containing only selected public IDs/status/version/ref+hash locks, source file
count and verified byte/tree-hash totals, checked reverse IDs and release locks. Failure exits 2 and emits
only `PRODUCTION_READBACK_FAILED:<stage>`; it does not print exception details. A release missing its exact
COMPLETED intent/SUCCEEDED Action/COMPLETE session, an expired/revoked component, or any integrity mismatch
must fail. The probe grants no certification, human approval, release or deployment authority.

For an explicitly selected assembly, the resolver's typed opaque rejection maps only to the fixed stage
`SELECTED_RELEASE`. An inspected-but-not-released assembly therefore exits 2 with
`PRODUCTION_READBACK_FAILED:SELECTED_RELEASE`, never a partial success report. Run a separate invocation
without `--assembly-id` for the Pack/full-component and current released reverse-list result.

## Classpath without Spring bootstrap

Use the already built matching reactor outputs; do not compile or replace the live host JAR just to
run this source. Ordinary JVM classpaths do **not** resolve Spring Boot nested `BOOT-INF/lib` JARs.
Use existing plain artifacts or classes/resources directories plus plain dependency JAR paths. On
Windows the classpath separator is `;`.

The direct dependency set to supply is:

- `factory-contracts`, `factory-application`, `factory-infrastructure` matching the verified build
  (`target/classes` also works and retains required infrastructure resources).
- `flower-action-runtime-core:0.3.3`, `flower-action-runtime-persistence-jdbc:0.3.3`.
- `flower-core:0.1.3` from the verified application's runtime dependency closure.
- The verified Jackson `jackson-annotations`, `jackson-core`, `jackson-databind`, and
  `jackson-datatype-jsr310` JARs; the Java-time module is required by persisted domain records.
- The verified PostgreSQL JDBC driver JAR.

No Spring Boot, Flyway, H2, Testcontainers, Maven, Codex SDK or test-source classpath is needed by this
probe. Root/operator compilation and dependency-closure verification remain required before execution;
do not resolve classpath errors by bootstrapping the production Spring application.

The fixed PR4 fixture-set lock mirrors `FactoryActionRuntimeConfiguration`, while the toolchain lock
uses the existing `Pr4MavenToolchainInstaller.EXPECTED_*` constants without calling installer methods.
The full validator retains Maintenance acceptance requirements and source/evidence integrity checks.
`ReferenceAssemblyProductLineCatalog` is used only to compute its code-owned admission policies; no
catalog provisioning method is called. Review the probe alongside host wiring if those contracts change.
