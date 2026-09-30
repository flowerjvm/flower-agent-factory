# Incident Application independent readback and handoff

`IncidentApplicationReadback.java` independently queries the existing production
ledgers and validates the exact product, component, inspection, decision, renewal
when present, and release graph. It does not start Spring/Flower, apply migrations,
submit Actions, invoke a Worker/model, run Docker, or change production data.

## Query contract

Required options take separate values:

- `--db-url`: queryless loopback PostgreSQL URL with explicit port/database.
- `--db-user`: an explicit existing database role; SELECT-only access is preferred.
- `--password-file`: protected absolute regular UTF-8 file, 1–4096 bytes, no BOM/link.
- `--tenant`: trusted exact tenant ID.
- `--session`: the exact Incident Application BuildSession ID.

```powershell
& $java21 --class-path $readbackClasspath --source 21 `
  tools/incident-application-production/IncidentApplicationReadback.java `
  --db-url $explicitLocalJdbcUrl --db-user $explicitDatabaseUser `
  --password-file $protectedPasswordFile --tenant $trustedTenant --session $exactSessionId
```

The operator provides the matching ordinary three-module reactor classpath and its
published dependencies. A Spring Boot executable JAR's nested libraries cannot be
used as an ordinary classpath without the appropriate loading mechanism.

At REVIEW, the helper reports the exact review subject and present state. An elapsed
deadline is an observation; it does not grant consent or extend the window. A verified
one-shot renewal preserves the original deadline and inspected subject while supplying
a separate effective release-review window. Readback only observes that proof.

At RELEASED, the full gate requires the separate whole-product certificate, exact human
Decision, current component eligibility, canonical release Action/intent ownership,
and completed production session. A RELEASED row alone is insufficient.

`wholeProductGraphVerified` means immutable graph revalidation, not a fresh HTTP test
execution. The report is a bounded read-interval observation; downstream consumers
still own current-state validation.

## Export

Add `--export-dir $newAbsoluteHandoffDirectory` to export an already released, fully
validated product. The destination must be new under a stable operator-owned parent.
Existing outputs, automatic partial resume and protected/link paths are rejected.

Export includes the exact application ZIP, BOM, product configuration, product and
component provenance, inspection/certification/decision/release evidence, optional
renewal evidence, and an index with each payload's size and hash. It omits raw Worker
transcripts, account settings and private credentials.

Export is local handoff of an existing release, not production, approval, deployment,
or runtime startup. Private production data and historical exports are not bundled
in this source repository.

## Synthetic readback checks

`IncidentApplicationReadbackTest.java` uses its synthetic JDBC driver/test fixtures.
Its source is included; no live production database is needed. The matching Java 21
classpath is required. When mandatory local symlink cases are desired, use the test's
`factory.incident.readback.test.requireLinks` setting and a prepared Windows environment.

Readback test success does not establish a live release or new handoff export.
See [production process](../../docs/production-process.md),
[validation](../../docs/validation.md), and [setup](../../docs/local-setup.md).
