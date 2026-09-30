import { AtomicStateStore } from "../src/atomic-state-store.mjs";

const [stateRoot, tenantKey, operationId] = process.argv.slice(2);
const store = new AtomicStateStore(stateRoot);
await store.initialize();
await store.withLock(tenantKey, operationId, async () => {
  // Deliberately bypasses finally to model an OS process crash immediately after lock publish.
  process.exit(86);
});
process.exit(87);
