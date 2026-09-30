# Development guidance

This repository implements a Factory with explicit specialized product lines.
Read `README.md` and `docs/architecture.md` before changing application code.
Use `docs/product-lines.md` and `docs/production-process.md` for existing product contracts.

- Keep product semantics, blueprints, module catalogs, verification, and release contracts in their product line.
- Keep durable domain records and ActionRun as the source of truth.
- Keep model calls, external processes, and other long work outside Flower worker ticks.
- Route controlled effects through registered Actions and preserve authorization, scoped duplicate handling, approval, audit, and fresh execution guards.
- Use version CAS and verified operation ownership for mutable durable records.
- Bind approvals and component consumption to exact artifacts and valid certification evidence.
- Verify candidates independently; worker self-tests and success claims do not establish shipment eligibility.
- Preserve unknown-effect states for explicit reconciliation or review instead of blind replay.
- Factory responsibility ends at release and provenance handoff. Deployment and continuous operation are separate responsibilities.
- Do not commit credentials, personal account settings, customer data, real user transcripts, production databases, or raw local execution evidence.

For Flower Flow/Step/build work, use the installed Flower plugin's `flower-app-guide`.
For Action Runtime controls/persistence work, use its `flower-action-runtime-guide`.
Do not use a legacy local skill of the same name as the implementation reference.
If those guides are unavailable, report the limitation instead of guessing API behavior.

Default verification: Java 21 and Node 20+ on PATH, then `./mvnw -B -ntp verify`
(`mvnw.cmd` on Windows). Node bridge/helper checks and opt-in native checks are described
in `docs/validation.md` and `docs/local-setup.md`.
