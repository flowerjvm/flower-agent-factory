# Codex worker process bridge

The Factory uses this bounded Node process bridge to run Codex Coding Worker operations.
Its dependency is pinned to `@openai/codex-sdk` `0.148.0`; Node.js 20+ is required.

```bash
npm ci --ignore-scripts
npm test
```

The default tests use synthetic fixtures and do not start real model production.
The permission-profile integration test is opt-in and reports a skip without its
explicit environment setting. See [validation](../docs/validation.md).

Actual worker production uses separately configured ChatGPT authentication and a
dedicated worker profile, verified workspace/credential isolation, operation locks,
bounded output, and callback/protocol validation. Login data and local profiles are
not part of this repository. See [local setup](../docs/local-setup.md).
