import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, test } from "node:test";
import { AtomicStateStore } from "../src/atomic-state-store.mjs";
import {
  CodexSdkBackend,
  CodexOperationService,
  publicSnapshot,
  terminalEnvelope
} from "../src/codex-operation.mjs";
import { ProtocolError } from "../src/protocol.mjs";
import { tenantPartition } from "../src/protocol.mjs";

const TENANT_KEY = tenantPartition("tenant-a");

const roots = [];
afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

test("duplicate submit binds one deterministic operation and conflicting input is rejected", async () => {
  const fixture = await createFixture();
  const first = await fixture.service.submit(fixture.request, {});
  const duplicate = await fixture.service.submit(fixture.request, {});
  assert.equal(first.createdAt, duplicate.createdAt);
  await assert.rejects(
    fixture.service.submit({ ...fixture.request, purpose: "different immutable purpose" }, {}),
    (error) => error instanceof ProtocolError && error.code === "WORKER_OPERATION_CONFLICT"
  );
});

test("fake backend produces only bounded relative outputs and an allowlisted transcript", async () => {
  const backend = {
    async run({ request, onEvent }) {
      onEvent({
        type: "item.completed",
        item: {
          type: "command_execution",
          command: "echo sk-proj-this-secret-must-never-appear",
          aggregated_output: "private output",
          status: "completed",
          exit_code: 0
        }
      });
      onEvent({ type: "item.completed", item: { type: "agent_message", text: "raw provider response" } });
      await mkdir(path.join(request.workspaceRoot, "candidate"), { recursive: true });
      await writeFile(path.join(request.workspaceRoot, "candidate", "result.txt"), "safe candidate\n");
    }
  };
  const fixture = await createFixture({ backend });
  await fixture.service.submit(fixture.request, {});
  const terminal = await fixture.service.execute(TENANT_KEY, fixture.request.operationId, runtime());

  assert.equal(terminal.status, "SUCCEEDED");
  assert.deepEqual(terminal.result.outputs.map((output) => output.relativePath), ["candidate/result.txt"]);
  const transcript = await readFile(
    path.join(fixture.store.operationDirectory(TENANT_KEY, fixture.request.operationId), "transcript.json"),
    "utf8"
  );
  assert.doesNotMatch(transcript, /sk-proj|private output|raw provider response|echo/u);
  assert.match(transcript, /command_execution/u);

  const envelope = terminalEnvelope(terminal, Buffer.from("attempt-token"));
  assert.equal(envelope.operationId, fixture.request.operationId);
  assert.equal(envelope.outputs[0].relativePath, "candidate/result.txt");
  assert.equal(Object.hasOwn(envelope, "url"), false);
  assert.equal(Object.hasOwn(envelope, "tenantId"), false);
  assert.match(envelope.attemptProof, /^[0-9a-f]{64}$/u);
  const snapshot = publicSnapshot(terminal, Buffer.from("attempt-token"));
  assert.equal(snapshot.effectAcceptedAt, terminal.createdAt);
  assert.equal(snapshot.effectTerminalAt, terminal.updatedAt);
  assert.match(snapshot.snapshotProof, /^[0-9a-f]{64}$/u);
});

test("write outside the allowlist fails closed without returning output bytes", async () => {
  const backend = {
    async run({ request }) {
      await writeFile(path.join(request.workspaceRoot, "outside.txt"), "not allowed");
    }
  };
  const fixture = await createFixture({ backend });
  await fixture.service.submit(fixture.request, {});
  const terminal = await fixture.service.execute(TENANT_KEY, fixture.request.operationId, runtime());
  assert.equal(terminal.status, "FAILED");
  assert.equal(terminal.stableCode, "WORKER_WRITE_SCOPE_VIOLATION");
  assert.equal(terminal.result, null);
});

test("status distinguishes not-found and does not reveal cross-tenant operation existence", async () => {
  const fixture = await createFixture();
  assert.equal(
    (await fixture.service.status({ tenantId: "tenant-a", workOrderId: "work-1", workerRunId: "run-1", operationId: "missing" })).observation,
    "NOT_FOUND"
  );
  await fixture.service.submit(fixture.request, {});
  assert.equal(
    (await fixture.service.status({
      tenantId: "tenant-b",
      workOrderId: "work-1",
      workerRunId: "run-1",
      operationId: "operation-1"
    })).observation,
    "NOT_FOUND"
  );
});

test("cancel before provider start is terminal and does not invoke the backend", async () => {
  let effects = 0;
  const fixture = await createFixture({ backend: { async run() { effects += 1; } } });
  await fixture.service.submit(fixture.request, {});
  const cancelled = await fixture.service.cancel({
    tenantId: fixture.request.tenantId,
    workOrderId: fixture.request.workOrderId,
    workerRunId: fixture.request.workerRunId,
    operationId: fixture.request.operationId,
    reasonCode: "FACTORY_CANCELLED"
  });
  assert.equal(cancelled.state.status, "CANCELLED");
  assert.equal(effects, 0);
  assert.equal(publicSnapshot(cancelled.state, Buffer.from("attempt-token")).status, "CANCELLED");
});

test("running operation cooperatively observes durable cancel without killing a pid", async () => {
  let observedAbort = false;
  const backend = {
    async run({ signal }) {
      let cancellationDeadline;
      try {
        await new Promise((resolve, reject) => {
          // A real provider child keeps the process alive; this promise-only fake needs
          // its own bounded handle while the production cancellation monitor is unref'd.
          cancellationDeadline = setTimeout(() => reject(new Error("fake backend cancellation timed out")), 5_000);
          const onAbort = () => {
            observedAbort = true;
            reject(new Error("cancelled"));
          };
          if (signal.aborted) onAbort();
          else signal.addEventListener("abort", onAbort, { once: true });
        });
      } finally {
        clearTimeout(cancellationDeadline);
      }
    }
  };
  const fixture = await createFixture({ backend });
  await fixture.service.submit(fixture.request, {});
  const execution = fixture.service.execute(TENANT_KEY, fixture.request.operationId, runtime());
  await waitForStatus(fixture.store, fixture.request.operationId, "RUNNING");

  const cancellation = await fixture.service.cancel({
    tenantId: fixture.request.tenantId,
    workOrderId: fixture.request.workOrderId,
    workerRunId: fixture.request.workerRunId,
    operationId: fixture.request.operationId,
    reasonCode: "FACTORY_CANCELLED"
  });
  const terminal = await execution;

  assert.equal(cancellation.state.status, "CANCEL_REQUESTED");
  assert.equal(observedAbort, true, "the backend must observe abort, not only a cancellation deadline");
  assert.equal(terminal.status, "CANCELLED");
  assert.equal(terminal.executionOwnerId, null);
});

test("status observation helper reads only while holding the exact operation lock", async () => {
  let locked = false;
  let reads = 0;
  const state = { status: "RUNNING" };
  const store = {
    async withLock(tenantKey, operationId, action) {
      assert.equal(tenantKey, TENANT_KEY);
      assert.equal(operationId, "operation-observation");
      locked = true;
      try {
        return await action();
      } finally {
        locked = false;
      }
    },
    async read(tenantKey, operationId) {
      assert.equal(locked, true);
      assert.equal(tenantKey, TENANT_KEY);
      assert.equal(operationId, "operation-observation");
      reads += 1;
      return state;
    }
  };

  assert.equal(await waitForStatus(store, "operation-observation", "RUNNING"), state);
  assert.equal(reads, 1);
  assert.equal(locked, false);
});

test("status observation helper retries operation lock contention without an unlocked read", async () => {
  let claims = 0;
  let reads = 0;
  const store = {
    async withLock(tenantKey, operationId, action) {
      claims += 1;
      if (claims === 1) {
        throw new ProtocolError("WORKER_OPERATION_BUSY", "synthetic concurrent state publication", true);
      }
      return action();
    },
    async read() {
      reads += 1;
      return { status: "RUNNING" };
    }
  };

  assert.equal((await waitForStatus(store, "operation-observation", "RUNNING")).status, "RUNNING");
  assert.equal(claims, 2);
  assert.equal(reads, 1);
});

test("status observation helper never retries or hides corrupt state under the lock", async () => {
  const failure = new ProtocolError("WORKER_STATE_CORRUPT", "synthetic invalid state");
  let claims = 0;
  let reads = 0;
  const store = {
    async withLock(tenantKey, operationId, action) {
      claims += 1;
      return action();
    },
    async read() {
      reads += 1;
      throw failure;
    }
  };

  await assert.rejects(waitForStatus(store, "operation-observation", "RUNNING"), (error) => error === failure);
  assert.equal(claims, 1);
  assert.equal(reads, 1);
});

test("materialized instruction and manifest hashes are checked before any backend effect", async () => {
  let effects = 0;
  const fixture = await createFixture({ backend: { async run() { effects += 1; } } });
  await writeFile(
    path.join(
      fixture.store.operationDirectory(TENANT_KEY, fixture.request.operationId),
      "inputs",
      "instruction"
    ),
    "tampered"
  );
  await assert.rejects(
    fixture.service.submit(fixture.request, {}),
    (error) => error instanceof ProtocolError && error.code === "WORKER_INPUT_HASH_MISMATCH"
  );
  assert.equal(effects, 0);
});

test("authority inputs are embedded by the trusted runner and never added as a writable sdk directory", async () => {
  const fixture = await createFixture();
  const operation = fixture.store.operationDirectory(TENANT_KEY, fixture.request.operationId);
  const locked = path.join(operation, "inputs", "locked");
  const codexHome = path.join(fixture.root, "codex-home");
  await mkdir(locked);
  await mkdir(codexHome);
  await writeFile(path.join(locked, "skill"), "bounded skill authority\n");
  let threadOptions;
  let codexOptions;
  let prompt;
  const backend = new CodexSdkBackend({
    authenticationVerifier: async () => ({ method: "chatgpt" }),
    isolationVerifier: async () => ({
      profileName: "factory-worker",
      evidenceSha256: "a".repeat(64)
    }),
    codexFactory: (options) => {
      codexOptions = options;
      return ({
      startThread(options) {
        threadOptions = options;
        return {
          async runStreamed(value) {
            prompt = value;
            return { events: (async function* () {})() };
          }
        };
      }
    });
    }
  });

  await backend.run({
    request: fixture.request,
    operationDirectory: operation,
    signal: new AbortController().signal,
    onEvent() {},
    trustedEnvironment: {
      FACTORY_CODEX_HOME: codexHome,
      OPENAI_API_KEY: "must-not-be-forwarded",
      CODEX_API_KEY: "must-not-be-forwarded",
      CODEX_ACCESS_TOKEN: "must-not-be-forwarded"
    }
  });

  assert.equal(Object.hasOwn(threadOptions, "additionalDirectories"), false);
  assert.equal(Object.hasOwn(threadOptions, "sandboxMode"), false);
  assert.equal(Object.hasOwn(threadOptions, "networkAccessEnabled"), false);
  assert.deepEqual(codexOptions.config, {
    default_permissions: "factory-worker", forced_login_method: "chatgpt",
    [`projects.${JSON.stringify(process.platform === "win32"
      ? path.resolve(fixture.request.workspaceRoot).toLowerCase()
      : path.resolve(fixture.request.workspaceRoot))}.trust_level`]: "untrusted"
  });
  assert.equal(Object.hasOwn(codexOptions, "apiKey"), false);
  for (const name of ["OPENAI_API_KEY", "CODEX_API_KEY", "CODEX_ACCESS_TOKEN"]) {
    assert.equal(Object.hasOwn(codexOptions.env, name), false);
  }
  assert.match(prompt, /not writable paths/u);
  assert.match(prompt, /bounded skill authority/u);
  assert.doesNotMatch(prompt, new RegExp(escapeRegex(path.join(operation, "inputs")), "u"));
});

test("unverified or API authentication stops before SDK creation while preserving the isolation gate", async () => {
  const fixture = await createFixture();
  const codexHome = path.join(fixture.root, "codex-home");
  await mkdir(codexHome);
  for (const authentication of [undefined, { method: "api" }]) {
    const events = [];
    const backend = new CodexSdkBackend({
      isolationVerifier: async () => { events.push("isolation"); },
      authenticationVerifier: async () => { events.push("authentication"); return authentication; },
      codexFactory() { events.push("sdk"); throw new Error("must not create SDK"); }
    });
    await assert.rejects(backend.run({
      request: fixture.request,
      operationDirectory: fixture.store.operationDirectory(TENANT_KEY, fixture.request.operationId),
      signal: new AbortController().signal,
      onEvent() {},
      trustedEnvironment: { FACTORY_CODEX_HOME: codexHome }
    }), (error) => error instanceof ProtocolError && error.code === "WORKER_CHATGPT_LOGIN_REQUIRED");
    assert.deepEqual(events, ["isolation", "authentication"]);
  }
});

test("preseeded tenant root redirect is rejected before state can escape the configured root", async (context) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "factory-codex-state-link-"));
  roots.push(root);
  const stateRoot = path.join(root, "state");
  const outside = path.join(root, "outside");
  await mkdir(stateRoot);
  await mkdir(outside);
  try {
    await symlink(outside, path.join(stateRoot, "tenants"), process.platform === "win32" ? "junction" : "dir");
  } catch {
    context.skip("filesystem redirects are unavailable");
    return;
  }
  const store = new AtomicStateStore(stateRoot);
  await assert.rejects(
    store.initialize(),
    (error) => error instanceof ProtocolError && error.code === "WORKER_STATE_PATH_INVALID"
  );
  assert.deepEqual(await readDirectoryNames(outside), []);
});

test("restarted operation cannot reuse another operation private workspace", async () => {
  const fixture = await createFixture();
  await fixture.service.submit(fixture.request, {});
  const restarted = new CodexOperationService({
    store: new AtomicStateStore(fixture.store.stateRoot),
    workspaceBase: fixture.store.tenantsRoot,
    backend: { async run() {} }
  });
  await restarted.initialize();
  const second = {
    ...fixture.request,
    workOrderId: "work-2",
    workerRunId: "run-2",
    operationId: "operation-2"
  };

  await assert.rejects(
    restarted.submit(second, {}),
    (error) => error instanceof ProtocolError && error.code === "WORKER_WORKSPACE_OUTSIDE_OPERATION"
  );
});

test("concurrent cross-tenant operations cannot share one operation private workspace", async () => {
  const fixture = await createFixture();
  const competing = {
    ...fixture.request,
    tenantId: "tenant-b",
    workOrderId: "work-2",
    workerRunId: "run-2",
    operationId: "operation-2"
  };

  const [owner, rejected] = await Promise.allSettled([
    fixture.service.submit(fixture.request, {}),
    fixture.service.submit(competing, {})
  ]);

  assert.equal(owner.status, "fulfilled");
  assert.equal(rejected.status, "rejected");
  assert.equal(rejected.reason.code, "WORKER_WORKSPACE_OUTSIDE_OPERATION");
});

test("same operation and run ids are isolated in canonical tenant partitions", async () => {
  const fixture = await createFixture();
  await fixture.service.submit(fixture.request, {});
  const tenantB = "tenant-b";
  const tenantBKey = tenantPartition(tenantB);
  const tenantBRequest = {
    ...fixture.request,
    tenantId: tenantB,
    workspaceRoot: path.join(
      fixture.store.operationDirectory(tenantBKey, fixture.request.operationId),
      "workspace"
    )
  };
  await materializeTestInputs(fixture.store, tenantBRequest);

  const tenantBState = await fixture.service.submit(tenantBRequest, {});
  const tenantAState = await fixture.store.read(TENANT_KEY, fixture.request.operationId);

  assert.equal(tenantAState.identity.tenantId, "tenant-a");
  assert.equal(tenantBState.identity.tenantId, tenantB);
  assert.notEqual(
    fixture.store.operationDirectory(TENANT_KEY, fixture.request.operationId),
    fixture.store.operationDirectory(tenantBKey, fixture.request.operationId)
  );
});

test("restart reconciles accepted state without execution owner to manual review and never relaunches", async () => {
  let now = new Date("2026-08-20T00:00:00Z");
  const fixture = await createFixture({ clock: () => now });
  await fixture.service.submit(fixture.request, {});
  now = new Date(now.getTime() + 5_000);
  let effects = 0;
  const restarted = new CodexOperationService({
    store: new AtomicStateStore(fixture.store.stateRoot),
    workspaceBase: fixture.store.tenantsRoot,
    launcher: () => {
      effects += 1;
      return { pid: 999_999 };
    },
    clock: () => now
  });
  await restarted.initialize();

  const observation = await restarted.status(fixture.request, {});
  assert.equal(observation.state.status, "MANUAL_REVIEW");
  assert.equal(observation.state.stableCode, "WORKER_ACCEPTANCE_ORPHANED");
  const duplicate = await restarted.submit(fixture.request, {});
  assert.equal(duplicate.status, "MANUAL_REVIEW");
  assert.equal(effects, 0);
});

test("stale status reconciliation rechecks owner and heartbeat under the operation lock", async () => {
  let now = new Date("2026-08-20T00:00:00.000Z");
  const fixture = await createFixture({ clock: () => now });
  await fixture.service.submit(fixture.request, {});
  now = new Date(now.getTime() + 5_000);
  let intercepted = false;
  const base = fixture.store;
  const interleavingStore = {
    stateRoot: base.stateRoot,
    tenantsRoot: base.tenantsRoot,
    initialize: () => base.initialize(),
    operationDirectory: (tenantKey, operationId) => base.operationDirectory(tenantKey, operationId),
    write: (tenantKey, operationId, state) => base.write(tenantKey, operationId, state),
    withLock: (tenantKey, operationId, action) => base.withLock(tenantKey, operationId, action),
    async read(tenantKey, operationId) {
      const observed = await base.read(tenantKey, operationId);
      if (!intercepted && observed != null) {
        intercepted = true;
        await base.withLock(tenantKey, operationId, async () => {
          const claimed = await base.read(tenantKey, operationId);
          claimed.status = "RUNNING";
          claimed.stableCode = "WORKER_RUNNING";
          claimed.executionOwnerId = "owner-after-stale-read";
          claimed.heartbeatAt = now.toISOString();
          claimed.updatedAt = claimed.heartbeatAt;
          await base.write(tenantKey, operationId, claimed);
        });
      }
      return observed;
    }
  };
  const racingService = new CodexOperationService({
    store: interleavingStore,
    workspaceBase: base.tenantsRoot,
    clock: () => now
  });
  await racingService.initialize();

  const observation = await racingService.status(fixture.request, {});
  const persisted = await base.read(TENANT_KEY, fixture.request.operationId);

  assert.equal(observation.state.status, "RUNNING");
  assert.equal(observation.state.executionOwnerId, "owner-after-stale-read");
  assert.equal(persisted.status, "RUNNING");
  assert.equal(persisted.executionOwnerId, "owner-after-stale-read");
});

test("restart recovers a crash-residue lock only after reacquiring its OS-held owner port", async () => {
  const fixture = await createFixture();
  const child = spawn(
    process.execPath,
    [
      fileURLToPath(new URL("crash-lock-child.mjs", import.meta.url)),
      fixture.store.stateRoot,
      TENANT_KEY,
      fixture.request.operationId
    ],
    {
      shell: false,
      windowsHide: true,
      stdio: ["ignore", "ignore", "ignore"],
      env: safeChildEnvironment(process.env)
    }
  );
  const exitCode = await new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("exit", resolve);
  });
  assert.equal(exitCode, 86);

  const lock = path.join(
    fixture.store.operationDirectory(TENANT_KEY, fixture.request.operationId),
    "operation.lock"
  );
  const lockMetadata = await (await import("node:fs/promises")).lstat(lock);
  assert.equal(lockMetadata.isFile(), true);

  const restarted = new AtomicStateStore(fixture.store.stateRoot);
  await restarted.initialize();
  let recoveryActions = 0;
  await restarted.withLock(TENANT_KEY, fixture.request.operationId, async () => {
    recoveryActions += 1;
    assert.equal(await restarted.read(TENANT_KEY, fixture.request.operationId), null);
  });

  assert.equal(recoveryActions, 1);
  assert.equal(await restarted.read(TENANT_KEY, fixture.request.operationId), null);
  assert.equal(await (await import("node:fs/promises")).lstat(lock).catch(() => null), null);
});

async function createFixture({ backend, clock } = {}) {
  const root = await mkdtemp(path.join(os.tmpdir(), "factory-codex-runner-"));
  roots.push(root);
  const stateRoot = path.join(root, "state");
  await mkdir(stateRoot);
  const store = new AtomicStateStore(stateRoot);
  const workspaceBase = path.join(stateRoot, "tenants");
  const service = new CodexOperationService({ store, workspaceBase, backend, clock });
  await service.initialize();
  const workspaceRoot = path.join(store.operationDirectory(TENANT_KEY, "operation-1"), "workspace");
  await mkdir(workspaceRoot, { recursive: true });
  await writeFile(path.join(workspaceRoot, "README.md"), "base\n");
  const request = requestFor(workspaceRoot);
  await materializeTestInputs(store, request);
  return { root, store, service, request };
}

async function materializeTestInputs(store, request) {
  const operation = store.operationDirectory(tenantPartition(request.tenantId), request.operationId);
  const workspace = path.join(operation, "workspace");
  await mkdir(workspace, { recursive: true });
  request.workspaceRoot = workspace;
  const inputs = path.join(operation, "inputs");
  await mkdir(inputs, { recursive: true });
  const instruction = Buffer.from("bounded instruction\n");
  const manifest = Buffer.from('{"schemaVersion":"test"}\n');
  await writeFile(path.join(inputs, "instruction"), instruction);
  await writeFile(path.join(inputs, "input-manifest.json"), manifest);
  await writeFile(path.join(inputs, "policy"), "no network\n");
  request.instructionSha256 = sha256(instruction);
  request.inputManifestSha256 = sha256(manifest);
}

async function readDirectoryNames(directory) {
  const { readdir } = await import("node:fs/promises");
  return readdir(directory);
}

async function waitForStatus(store, operationId, expected) {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    // State readback deliberately rejects files replaced during lstat/open/read/lstat. Observe
    // the concurrent execution using its existing publication lock, not by ignoring corruption.
    let state;
    try {
      state = await store.withLock(TENANT_KEY, operationId, () => store.read(TENANT_KEY, operationId));
    } catch (error) {
      if (!(error instanceof ProtocolError) || error.code !== "WORKER_OPERATION_BUSY") throw error;
    }
    if (state?.status === expected) return state;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error(`operation did not reach ${expected}`);
}

function requestFor(workspaceRoot) {
  return {
    protocolVersion: "flower-codex-worker/1",
    command: "submit",
    schemaVersion: "factory.coding-worker-dispatch.v1",
    tenantId: "tenant-a",
    workOrderId: "work-1",
    workerRunId: "run-1",
    operationId: "operation-1",
    taskType: "generate-candidate",
    purpose: "Generate a bounded candidate",
    workspaceRef: "workspace:one",
    workspaceRoot,
    allowedReadPaths: ["README.md"],
    allowedWritePaths: ["candidate"],
    requiredCapabilities: ["repository-read", "bounded-patch-write"],
    expectedOutputSchemaId: "factory.pack-candidate",
    expectedOutputSchemaVersion: "1",
    instructionArtifactRef: "artifact:instruction",
    instructionSha256: "0".repeat(64),
    inputManifestArtifactRef: "artifact:input-manifest",
    inputManifestSha256: "0".repeat(64),
    policySnapshotRef: "artifact:policy",
    deadlineAt: "2099-01-01T00:00:00Z"
  };
}

function runtime() {
  return {
    attemptToken: Buffer.from("attempt-token"),
    trustedEnvironment: {}
  };
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
}

function safeChildEnvironment(source) {
  const result = {};
  for (const name of ["PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC"]) {
    if (typeof source[name] === "string" && source[name].length <= 32 * 1024) result[name] = source[name];
  }
  return result;
}
