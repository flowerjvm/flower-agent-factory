import { createHash, randomBytes } from "node:crypto";
import { constants } from "node:fs";
import { lstat, mkdir, open, realpath, readdir, rename } from "node:fs/promises";
import path from "node:path";
import { Codex } from "@openai/codex-sdk";
import {
  attemptProof,
  canonicalJson,
  COMPLETION_SCHEMA_VERSION,
  eventIdFor,
  operationDigest,
  PROTOCOL_VERSION,
  ProtocolError,
  statusSnapshotProof,
  tenantPartition
} from "./protocol.mjs";
import { assertNoKnownSecret, TranscriptProjector } from "./redaction.mjs";
import { PERMISSION_PROFILE_NAME, verifyCredentialIsolation } from "./credential-isolation.mjs";
import { verifyChatGptAuthentication } from "./chatgpt-authentication.mjs";

export const CAPABILITIES = Object.freeze([
  "repository-read",
  "bounded-patch-write",
  "file-create",
  "command-build-test",
  "structured-output",
  "progress-events",
  "cooperative-cancel",
  "usage-reporting",
  "sandbox-enforcement"
]);

const TERMINAL = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "MANUAL_REVIEW"]);
const MAX_FILES = 4096;
const MAX_FILE_BYTES = 8 * 1024 * 1024;
const MAX_TOTAL_BYTES = 128 * 1024 * 1024;
const MAX_AUTHORITY_BYTES = 32 * 1024 * 1024;
const EMPTY_SHA256 = createHash("sha256").update(Buffer.alloc(0)).digest("hex");

export class CodexOperationService {
  constructor({ store, workspaceBase, backend, launcher, clock = () => new Date() }) {
    this.store = store;
    this.workspaceBase = path.resolve(workspaceBase);
    this.backend = backend ?? new CodexSdkBackend();
    this.launcher = launcher;
    this.clock = clock;
  }

  async initialize() {
    await this.store.initialize();
    this.workspaceBase = await realpath(this.workspaceBase).catch(() => {
      throw new ProtocolError("WORKER_WORKSPACE_BASE_INVALID", "configured workspace base does not exist");
    });
  }

  async submit(request, secretEnvironment) {
    const tenantKey = tenantPartition(request.tenantId);
    return this.store.withLock(tenantKey, request.operationId, async () => {
      const existing = await this.store.read(tenantKey, request.operationId);
      const fingerprint = requestFingerprint(request);
      if (existing != null) {
        assertIdentity(existing, request);
        if (existing.requestFingerprint !== fingerprint) {
          throw new ProtocolError("WORKER_OPERATION_CONFLICT", "operation is bound to different immutable input");
        }
        return existing;
      }
      if (Date.parse(request.deadlineAt) <= this.clock().getTime()) {
        throw new ProtocolError("WORKER_DEADLINE_ELAPSED", "work order deadline has elapsed");
      }
      const workspaceRoot = await this.validateWorkspace(
        request.workspaceRoot,
        tenantKey,
        request.operationId
      );
      const normalizedRequest = {
        ...request,
        workspaceRoot,
        allowedReadPaths: request.allowedReadPaths.map((entry) => normalizeRelativePath(entry, "allowed read path")),
        allowedWritePaths: request.allowedWritePaths.map((entry) => normalizeRelativePath(entry, "allowed write path"))
      };
      if (normalizedRequest.allowedWritePaths.length === 0) {
        throw new ProtocolError("WORKER_WRITE_SCOPE_EMPTY", "at least one bounded output path is required");
      }
      await verifyInputs(
        this.store.operationDirectory(tenantKey, request.operationId),
        normalizedRequest
      );
      const now = this.clock().toISOString();
      const state = {
        stateVersion: 1,
        protocolVersion: PROTOCOL_VERSION,
        identity: identityOf(request),
        requestFingerprint: fingerprint,
        request: normalizedRequest,
        status: "ACCEPTED",
        stableCode: "WORKER_ACCEPTED",
        createdAt: now,
        effectAcceptedAt: now,
        effectTerminalAt: null,
        updatedAt: now,
        launchAcknowledgedAt: null,
        executionOwnerId: null,
        heartbeatAt: null,
        result: null
      };
      await this.store.write(tenantKey, request.operationId, state);
      if (this.launcher != null) {
        let child;
        try {
          child = this.launcher(tenantKey, request.operationId, secretEnvironment);
        } catch {
          const failed = terminalState(state, "FAILED", "WORKER_START_FAILED", this.clock());
          await this.store.write(tenantKey, request.operationId, failed);
          return failed;
        }
        if (!Number.isSafeInteger(child?.pid) || child.pid <= 0) {
          const failed = terminalState(state, "FAILED", "WORKER_START_FAILED", this.clock());
          await this.store.write(tenantKey, request.operationId, failed);
          return failed;
        }
        state.launchAcknowledgedAt = this.clock().toISOString();
        state.updatedAt = state.launchAcknowledgedAt;
        await this.store.write(tenantKey, request.operationId, state);
      }
      return state;
    });
  }

  async execute(tenantKey, operationId, runtime) {
    const executionOwnerId = randomBytes(16).toString("hex");
    const claimed = await withBusyRetry(this.store, tenantKey, operationId, async () => {
      const current = await this.store.read(tenantKey, operationId);
      if (current == null) {
        throw new ProtocolError("WORKER_OPERATION_NOT_FOUND", "operation does not exist");
      }
      assertTenantPartition(current, tenantKey);
      if (current.status !== "ACCEPTED") {
        return current;
      }
      current.status = "RUNNING";
      current.stableCode = "WORKER_RUNNING";
      current.executionOwnerId = executionOwnerId;
      current.heartbeatAt = this.clock().toISOString();
      current.updatedAt = this.clock().toISOString();
      await this.store.write(tenantKey, operationId, current);
      return current;
    });
    if (claimed.status !== "RUNNING" || claimed.executionOwnerId !== executionOwnerId) {
      return claimed;
    }

    const request = claimed.request;
    const operationDirectory = this.store.operationDirectory(tenantKey, operationId);
    const immutableInputDirectory = path.join(operationDirectory, "inputs");
    const projector = new TranscriptProjector(request.workspaceRoot);
    const controller = new AbortController();
    let timedOut = false;
    const remaining = Math.max(1, Date.parse(request.deadlineAt) - this.clock().getTime());
    const timeout = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, Math.min(remaining, 24 * 60 * 60 * 1000));
    timeout.unref?.();
    const abortOnSignal = () => controller.abort();
    process.once("SIGTERM", abortOnSignal);
    process.once("SIGINT", abortOnSignal);
    const monitor = monitorExecution(
      this.store,
      tenantKey,
      operationId,
      executionOwnerId,
      controller,
      this.clock
    );

    let terminal;
    try {
      await this.validateWorkspace(request.workspaceRoot, tenantKey, operationId);
      const immutableInputsBefore = await snapshotTree(immutableInputDirectory);
      const before = await snapshotTree(request.workspaceRoot);
      await this.backend.run({
        request,
        operationDirectory,
        signal: controller.signal,
        onEvent: (event) => projector.add(event),
        trustedEnvironment: runtime.trustedEnvironment
      });
      const immutableInputsAfter = await snapshotTree(immutableInputDirectory);
      if (!sameSnapshot(immutableInputsBefore, immutableInputsAfter)) {
        throw new ProtocolError("WORKER_INPUT_MUTATION_DETECTED", "worker modified immutable staged input");
      }
      const after = await snapshotTree(request.workspaceRoot);
      const outputs = changedOutputs(before, after, request.allowedWritePaths);
      if (outputs.length === 0) {
        throw new ProtocolError("WORKER_NO_OUTPUT", "worker completed without a bounded candidate output");
      }
      for (const output of outputs) {
        if (output.kind !== "DELETE") {
          const bytes = await readBoundedRegularFile(
            path.join(request.workspaceRoot, ...output.relativePath.split("/")),
            MAX_FILE_BYTES,
            "WORKER_OUTPUT_FILE_INVALID"
          );
          assertNoKnownSecret(bytes);
        }
      }
      const transcript = projector.value();
      const transcriptBytes = Buffer.from(canonicalJson({ schemaVersion: "factory.codex-transcript.v1", events: transcript }));
      assertNoKnownSecret(transcriptBytes);
      const transcriptPath = path.join(operationDirectory, "transcript.json");
      await writePrivateFile(transcriptPath, transcriptBytes);
      const result = {
        outputs,
        transcript: {
          relativePath: "transcript.json",
          sha256: digest(transcriptBytes),
          sizeBytes: transcriptBytes.length
        }
      };
      terminal = terminalState(claimed, "SUCCEEDED", "WORKER_SUCCEEDED", this.clock(), result);
    } catch (error) {
      const current = await this.store.read(tenantKey, operationId);
      const cancelled = current?.status === "CANCEL_REQUESTED" || controller.signal.aborted;
      const status = timedOut ? "TIMED_OUT" : cancelled ? "CANCELLED" : "FAILED";
      const code = timedOut
        ? "WORKER_DEADLINE_EXCEEDED"
        : cancelled
          ? "WORKER_CANCELLED"
          : error instanceof ProtocolError
            ? error.code
            : "WORKER_PROVIDER_FAILED";
      terminal = terminalState(current ?? claimed, status, code, this.clock());
    } finally {
      clearTimeout(timeout);
      process.removeListener("SIGTERM", abortOnSignal);
      process.removeListener("SIGINT", abortOnSignal);
      await monitor.stop();
    }
    terminal.eventId = deterministicEventId(terminal);
    return withBusyRetry(this.store, tenantKey, operationId, async () => {
      const current = await this.store.read(tenantKey, operationId);
      if (current == null || current.executionOwnerId !== executionOwnerId || TERMINAL.has(current.status)) {
        return current ?? terminalState(
          terminal,
          "MANUAL_REVIEW",
          "WORKER_TERMINAL_OWNERSHIP_UNKNOWN",
          this.clock()
        );
      }
      await this.store.write(tenantKey, operationId, terminal);
      return terminal;
    });
  }

  async status(request, runtime) {
    const tenantKey = tenantPartition(request.tenantId);
    let state = await this.store.read(tenantKey, request.operationId);
    if (state == null) {
      return { observation: "NOT_FOUND", state: null };
    }
    assertIdentity(state, request);
    const inactiveFor = this.clock().getTime() - Date.parse(state.updatedAt);
    if (state.status === "ACCEPTED" && state.executionOwnerId == null && inactiveFor >= 5_000) {
      state = await reconcileOrphan(
        this.store,
        tenantKey,
        request,
        this.clock,
        state,
        "WORKER_ACCEPTANCE_ORPHANED"
      );
    } else if (["RUNNING", "CANCEL_REQUESTED"].includes(state.status)) {
      const heartbeatAge = this.clock().getTime() - Date.parse(state.heartbeatAt ?? state.updatedAt);
      if (state.executionOwnerId == null || heartbeatAge >= 5_000) {
        state = await reconcileOrphan(
          this.store,
          tenantKey,
          request,
          this.clock,
          state,
          "WORKER_EXECUTION_ORPHANED"
        );
      }
    }
    return { observation: "FOUND", state };
  }

  async cancel(request) {
    const tenantKey = tenantPartition(request.tenantId);
    const state = await this.store.withLock(tenantKey, request.operationId, async () => {
      const current = await this.store.read(tenantKey, request.operationId);
      if (current == null) {
        return null;
      }
      assertIdentity(current, request);
      if (TERMINAL.has(current.status)) {
        return current;
      }
      current.status = current.status === "ACCEPTED" ? "CANCELLED" : "CANCEL_REQUESTED";
      current.stableCode = current.status === "CANCELLED" ? "WORKER_CANCELLED" : "WORKER_CANCEL_REQUESTED";
      current.updatedAt = this.clock().toISOString();
      if (current.status === "CANCELLED") {
        current.effectTerminalAt = current.updatedAt;
        current.eventId = deterministicEventId(current);
      }
      await this.store.write(tenantKey, request.operationId, current);
      return current;
    });
    if (state == null) {
      return { observation: "NOT_FOUND", state: null };
    }
    return { observation: "FOUND", state };
  }

  async validateWorkspace(candidate, tenantKey, operationId) {
    if (!path.isAbsolute(candidate)) {
      throw new ProtocolError("WORKER_WORKSPACE_INVALID", "workspace root must be absolute");
    }
    const info = await lstat(candidate).catch(() => null);
    if (info == null || !info.isDirectory() || info.isSymbolicLink()) {
      throw new ProtocolError("WORKER_WORKSPACE_INVALID", "workspace root is unavailable");
    }
    const resolved = await realpath(candidate);
    const expected = path.join(this.store.operationDirectory(tenantKey, operationId), "workspace");
    const expectedResolved = await realpath(expected).catch(() => null);
    if (
      expectedResolved == null ||
      normalizeAbsolute(resolved) !== normalizeAbsolute(expectedResolved) ||
      normalizeAbsolute(resolved) !== normalizeAbsolute(path.resolve(candidate)) ||
      !isWithin(this.workspaceBase, resolved)
    ) {
      throw new ProtocolError(
        "WORKER_WORKSPACE_OUTSIDE_OPERATION",
        "workspace root is not the factory-owned operation workspace"
      );
    }
    return resolved;
  }
}

export class CodexSdkBackend {
  constructor({
    codexFactory = (options) => new Codex(options),
    isolationVerifier = verifyCredentialIsolation,
    authenticationVerifier = verifyChatGptAuthentication
  } = {}) {
    this.codexFactory = codexFactory;
    this.isolationVerifier = isolationVerifier;
    this.authenticationVerifier = authenticationVerifier;
  }

  async run({ request, operationDirectory, signal, onEvent, trustedEnvironment }) {
    const configuredCodexHome = trustedEnvironment.FACTORY_CODEX_HOME;
    if (typeof configuredCodexHome !== "string" || !path.isAbsolute(configuredCodexHome)) {
      throw new ProtocolError("WORKER_CODEX_PROFILE_UNAVAILABLE", "dedicated Codex profile is unavailable");
    }
    const temporary = path.join(operationDirectory, "tmp");
    try {
      await mkdir(temporary, { mode: 0o700 });
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;
    }
    await stableDirectory(temporary);
    const codexHome = await realpath(configuredCodexHome).catch(() => {
      throw new ProtocolError("WORKER_CODEX_PROFILE_UNAVAILABLE", "dedicated Codex profile is unavailable");
    });
    const sdkEnvironment = allowlistedSdkEnvironment(trustedEnvironment, codexHome, temporary);
    await this.isolationVerifier({
      codexHome,
      stateRoot: trustedEnvironment.FACTORY_CODEX_STATE_ROOT,
      sourceWorkspaceBase: trustedEnvironment.FACTORY_CODEX_SOURCE_WORKSPACE_BASE,
      workspaceRoot: request.workspaceRoot,
      codexPath: trustedEnvironment.FACTORY_CODEX_PATH,
      environment: trustedEnvironment
    });
    const authentication = await this.authenticationVerifier({
      codexHome,
      codexPath: trustedEnvironment.FACTORY_CODEX_PATH,
      environment: trustedEnvironment,
      signal
    });
    if (authentication?.method !== "chatgpt") {
      throw new ProtocolError("WORKER_CHATGPT_LOGIN_REQUIRED",
        "verified ChatGPT login in the dedicated Codex profile is required");
    }
    const authority = await loadAuthority(operationDirectory);
    const codex = this.codexFactory({
      env: sdkEnvironment,
      config: {
        default_permissions: PERMISSION_PROFILE_NAME,
        forced_login_method: "chatgpt",
        // Generated workspaces are not authority for Codex config, hooks or rules.
        // Pin this exact workspace as untrusted for this invocation, not by editing
        // the shared profile. Quote the TOML key before the pinned SDK flattens it.
        [`projects.${JSON.stringify(normalizeAbsolute(request.workspaceRoot))}.trust_level`]: "untrusted"
      },
      ...(trustedEnvironment.FACTORY_CODEX_PATH
        ? { codexPathOverride: trustedEnvironment.FACTORY_CODEX_PATH }
        : {})
    });
    const thread = codex.startThread({
      workingDirectory: request.workspaceRoot,
      skipGitRepoCheck: true,
      webSearchMode: "disabled",
      approvalPolicy: "never",
      ...(trustedEnvironment.FACTORY_CODEX_MODEL ? { model: trustedEnvironment.FACTORY_CODEX_MODEL } : {})
    });
    const { events } = await thread.runStreamed(buildPrompt(request, authority), { signal });
    for await (const event of events) {
      onEvent(event);
    }
  }
}

export function terminalEnvelope(state, attemptTokenBuffer) {
  if (state.status !== "SUCCEEDED" && state.status !== "FAILED") {
    throw new ProtocolError("WORKER_COMPLETION_UNSUPPORTED", "completion envelope supports success or failure");
  }
  const eventId = state.eventId ?? deterministicEventId(state);
  return {
    schemaVersion: COMPLETION_SCHEMA_VERSION,
    eventId,
    workOrderId: state.identity.workOrderId,
    workerRunId: state.identity.workerRunId,
    operationId: state.identity.operationId,
    status: state.status,
    outputs: state.result?.outputs ?? [],
    transcript: state.result?.transcript ?? null,
    stableCode: state.stableCode,
    attemptProof: attemptProof(
      attemptTokenBuffer,
      eventId,
      state.identity.operationId,
      state.identity.workerRunId
    )
  };
}

export function publicSnapshot(state, attemptTokenBuffer) {
  const effectAcceptedAt = acceptedAt(state);
  const effectTerminalAt = terminalAt(state, effectAcceptedAt);
  const snapshot = {
    tenantId: state.identity.tenantId,
    workOrderId: state.identity.workOrderId,
    workerRunId: state.identity.workerRunId,
    operationId: state.identity.operationId,
    status: externalStatus(state.status),
    stableCode: state.stableCode,
    effectAcceptedAt,
    effectTerminalAt,
    externalSessionRef: `codex-operation:${operationDigest(state.identity.operationId)}`,
    completionEnvelope:
      state.status === "SUCCEEDED" || state.status === "FAILED"
        ? terminalEnvelope(state, attemptTokenBuffer)
        : null
  };
  return { ...snapshot, snapshotProof: statusSnapshotProof(attemptTokenBuffer, snapshot) };
}

function buildPrompt(request, authority) {
  return [
    "Perform the bounded Factory coding work order in the configured workspace.",
    `Task type: ${request.taskType}`,
    `Purpose: ${request.purpose}`,
    "The following authority blocks were read by the trusted runner and are not writable paths:",
    ...authority.map((entry) => `\n--- ${entry.name} sha256=${entry.sha256} ---\n${entry.text}`),
    `You may modify only these workspace-relative paths: ${request.allowedWritePaths.join(", ")}`,
    "Network and web search are disabled. Never request credentials or access a personal profile.",
    "Do not modify immutable input files. Finish after producing the requested candidate files."
  ].join("\n");
}

async function loadAuthority(operationDirectory) {
  const inputs = path.join(operationDirectory, "inputs");
  const candidates = [
    ["instruction", path.join(inputs, "instruction")],
    ["input-manifest.json", path.join(inputs, "input-manifest.json")],
    ["policy", path.join(inputs, "policy")]
  ];
  const locked = path.join(inputs, "locked");
  const lockedEntries = await readdir(locked, { withFileTypes: true });
  lockedEntries.sort((first, second) => first.name.localeCompare(second.name, "en"));
  for (const entry of lockedEntries) {
    if (!entry.isFile() || entry.isSymbolicLink()) {
      throw new ProtocolError("WORKER_INPUT_MATERIALIZATION_INVALID", "locked authority is invalid");
    }
    candidates.push([`locked/${entry.name}`, path.join(locked, entry.name)]);
  }
  let total = 0;
  const result = [];
  const decoder = new TextDecoder("utf-8", { fatal: true });
  for (const [name, file] of candidates) {
    const bytes = await readBoundedRegularFile(
      file,
      MAX_FILE_BYTES,
      "WORKER_INPUT_MATERIALIZATION_INVALID"
    );
    total += bytes.length;
    if (total > MAX_AUTHORITY_BYTES) {
      throw new ProtocolError("WORKER_INPUT_QUOTA_EXCEEDED", "authority input exceeds its quota");
    }
    let text;
    try {
      text = decoder.decode(bytes);
    } catch {
      throw new ProtocolError("WORKER_INPUT_TEXT_INVALID", "authority input must be UTF-8 text");
    }
    result.push({ name, sha256: digest(bytes), text });
  }
  return result;
}

async function verifyInputs(operationDirectory, request) {
  const expected = [
    ["instruction", request.instructionSha256],
    ["input-manifest.json", request.inputManifestSha256],
    ["policy", null]
  ];
  for (const [name, expectedHash] of expected) {
    const candidate = path.join(operationDirectory, "inputs", name);
    const bytes = await readBoundedRegularFile(
      candidate,
      MAX_FILE_BYTES,
      "WORKER_INPUT_MATERIALIZATION_INVALID"
    );
    if (expectedHash != null && digest(bytes) !== expectedHash) {
      throw new ProtocolError("WORKER_INPUT_HASH_MISMATCH", "materialized worker input does not match its lock");
    }
  }
}

async function snapshotTree(root) {
  const files = new Map();
  let totalBytes = 0;
  const visit = async (directory, relativeBase) => {
    const directoryBefore = await stableDirectory(directory);
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((a, b) => a.name.localeCompare(b.name, "en"));
    for (const entry of entries) {
      const relative = relativeBase ? `${relativeBase}/${entry.name}` : entry.name;
      const absolute = path.join(directory, entry.name);
      const metadata = await lstat(absolute);
      if (metadata.isSymbolicLink()) {
        throw new ProtocolError("WORKER_WORKSPACE_LINK_REJECTED", "workspace contains a symbolic link");
      }
      if (metadata.isDirectory()) {
        await visit(absolute, relative);
      } else if (metadata.isFile()) {
        if (metadata.size > MAX_FILE_BYTES || files.size >= MAX_FILES) {
          throw new ProtocolError("WORKER_WORKSPACE_QUOTA_EXCEEDED", "workspace file quota exceeded");
        }
        totalBytes += metadata.size;
        if (totalBytes > MAX_TOTAL_BYTES) {
          throw new ProtocolError("WORKER_WORKSPACE_QUOTA_EXCEEDED", "workspace byte quota exceeded");
        }
        const bytes = await readBoundedRegularFile(
          absolute,
          MAX_FILE_BYTES,
          "WORKER_WORKSPACE_FILE_INVALID"
        );
        files.set(relative, { sha256: digest(bytes), sizeBytes: bytes.length });
      } else {
        throw new ProtocolError("WORKER_WORKSPACE_SPECIAL_FILE_REJECTED", "workspace contains a special file");
      }
    }
    const directoryAfter = await stableDirectory(directory);
    if (!sameFileMetadata(directoryBefore, directoryAfter)) {
      throw new ProtocolError("WORKER_WORKSPACE_RACE_DETECTED", "workspace changed while being scanned");
    }
  };
  await visit(root, "");
  return files;
}

function changedOutputs(before, after, allowedWritePaths) {
  const all = [...new Set([...before.keys(), ...after.keys()])].sort();
  const changes = [];
  for (const relativePath of all) {
    const previous = before.get(relativePath);
    const current = after.get(relativePath);
    if (previous?.sha256 === current?.sha256) {
      continue;
    }
    if (!allowedWritePaths.some((allowed) => relativePath === allowed || relativePath.startsWith(`${allowed}/`))) {
      throw new ProtocolError("WORKER_WRITE_SCOPE_VIOLATION", "worker changed a path outside its write scope");
    }
    changes.push({
      relativePath,
      sha256: current?.sha256 ?? EMPTY_SHA256,
      sizeBytes: current?.sizeBytes ?? 0,
      kind: previous == null ? "ADD" : current == null ? "DELETE" : "UPDATE"
    });
  }
  return changes;
}

function sameSnapshot(first, second) {
  if (first.size !== second.size) return false;
  for (const [name, value] of first) {
    const other = second.get(name);
    if (other == null || value.sha256 !== other.sha256 || value.sizeBytes !== other.sizeBytes) return false;
  }
  return true;
}

function normalizeRelativePath(value, name) {
  if (typeof value !== "string" || value.length === 0 || path.isAbsolute(value)) {
    throw new ProtocolError("WORKER_PATH_INVALID", `${name} must be workspace-relative`);
  }
  const normalized = path.normalize(value);
  if (normalized === "." || normalized.startsWith("..") || path.isAbsolute(normalized)) {
    throw new ProtocolError("WORKER_PATH_INVALID", `${name} escapes the workspace`);
  }
  return normalized.split(path.sep).join("/");
}

function identityOf(value) {
  return {
    tenantId: value.tenantId,
    workOrderId: value.workOrderId,
    workerRunId: value.workerRunId,
    operationId: value.operationId
  };
}

function assertIdentity(state, request) {
  const identity = state?.identity;
  if (
    identity == null ||
    identity.tenantId !== request.tenantId ||
    identity.workerRunId !== request.workerRunId ||
    identity.operationId !== request.operationId ||
    (request.workOrderId != null && identity.workOrderId !== request.workOrderId)
  ) {
    throw new ProtocolError("WORKER_OPERATION_IDENTITY_MISMATCH", "operation identity does not match");
  }
}

function assertTenantPartition(state, tenantKey) {
  if (tenantPartition(state?.identity?.tenantId) !== tenantKey) {
    throw new ProtocolError(
      "WORKER_OPERATION_IDENTITY_MISMATCH",
      "operation state is in the wrong tenant partition"
    );
  }
}

function requestFingerprint(request) {
  return digest(Buffer.from(canonicalJson(request), "utf8"));
}

function terminalState(state, status, stableCode, clock, result = null) {
  const effectTerminalAt = clock.toISOString();
  return {
    ...state,
    status,
    stableCode,
    result,
    executionOwnerId: null,
    effectTerminalAt,
    updatedAt: effectTerminalAt
  };
}

function deterministicEventId(state) {
  const resultHash = digest(Buffer.from(canonicalJson(state.result ?? {}), "utf8"));
  return `evt_${digest(Buffer.from(`${state.identity.operationId}\n${state.status}\n${resultHash}`, "utf8"))}`;
}

function externalStatus(status) {
  if (status === "ACCEPTED" || status === "RUNNING") return "WAITING_EXTERNAL";
  return status;
}

function acceptedAt(state) {
  const value = state?.effectAcceptedAt;
  if (
    typeof value !== "string" ||
    value !== state.createdAt ||
    !Number.isFinite(Date.parse(value)) ||
    new Date(value).toISOString() !== value
  ) {
    throw new ProtocolError(
      "WORKER_ACCEPTANCE_TIME_INVALID",
      "durable operation acceptance time is invalid",
      true
    );
  }
  return value;
}

function terminalAt(state, effectAcceptedAt) {
  const value = state?.effectTerminalAt;
  if (!TERMINAL.has(state?.status)) {
    if (value != null) {
      throw new ProtocolError(
        "WORKER_TERMINAL_TIME_INVALID",
        "nonterminal operation has a terminal journal time",
        true
      );
    }
    return null;
  }
  if (
    typeof value !== "string" ||
    value !== state.updatedAt ||
    !Number.isFinite(Date.parse(value)) ||
    new Date(value).toISOString() !== value ||
    Date.parse(value) < Date.parse(effectAcceptedAt)
  ) {
    throw new ProtocolError(
      "WORKER_TERMINAL_TIME_INVALID",
      "durable operation terminal time is invalid",
      true
    );
  }
  return value;
}

function digest(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function isWithin(base, candidate) {
  const relative = path.relative(base, candidate);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

function normalizeAbsolute(value) {
  const normalized = path.resolve(value);
  return process.platform === "win32" ? normalized.toLowerCase() : normalized;
}

function allowlistedSdkEnvironment(source, codexHome, temporary) {
  const result = {
    HOME: codexHome,
    USERPROFILE: codexHome,
    CODEX_HOME: codexHome,
    TMP: temporary,
    TEMP: temporary
  };
  for (const name of ["PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC"]) {
    if (typeof source[name] === "string" && source[name].length <= 32 * 1024) {
      result[name] = source[name];
    }
  }
  return result;
}

async function writePrivateFile(destination, bytes) {
  const temporary = `${destination}.tmp-${process.pid}-${Date.now()}`;
  const noFollow = constants.O_NOFOLLOW ?? 0;
  const handle = await open(
    temporary,
    constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | noFollow,
    0o600
  );
  try {
    await handle.writeFile(bytes);
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename(temporary, destination);
  const persisted = await readBoundedRegularFile(
    destination,
    bytes.length,
    "WORKER_TRANSCRIPT_WRITE_FAILED"
  );
  if (!persisted.equals(bytes)) {
    throw new ProtocolError("WORKER_TRANSCRIPT_WRITE_FAILED", "transcript did not persist atomically");
  }
}

function monitorExecution(store, tenantKey, operationId, executionOwnerId, controller, clock) {
  let stopped = false;
  let timer = null;
  let inFlight = Promise.resolve();
  const tick = async () => {
    try {
      await withBusyRetry(store, tenantKey, operationId, async () => {
        const current = await store.read(tenantKey, operationId);
        if (
          current == null ||
          current.executionOwnerId !== executionOwnerId ||
          TERMINAL.has(current.status)
        ) {
          stopped = true;
          controller.abort();
          return;
        }
        if (current.status === "CANCEL_REQUESTED") {
          stopped = true;
          controller.abort();
          return;
        }
        if (current.status !== "RUNNING") {
          stopped = true;
          controller.abort();
          return;
        }
        current.heartbeatAt = clock().toISOString();
        current.updatedAt = current.heartbeatAt;
        await store.write(tenantKey, operationId, current);
      });
    } catch {
      stopped = true;
      controller.abort();
    }
  };
  const schedule = () => {
    if (stopped) return;
    timer = setTimeout(() => {
      inFlight = tick().finally(schedule);
    }, 250);
    timer.unref?.();
  };
  schedule();
  return {
    async stop() {
      stopped = true;
      if (timer != null) clearTimeout(timer);
      await inFlight;
    }
  };
}

async function reconcileOrphan(store, tenantKey, request, clock, observed, stableCode) {
  return withBusyRetry(store, tenantKey, request.operationId, async () => {
    const current = await store.read(tenantKey, request.operationId);
    if (current == null) {
      throw new ProtocolError("WORKER_OPERATION_NOT_FOUND", "operation disappeared during reconciliation", true);
    }
    assertIdentity(current, request);
    assertTenantPartition(current, tenantKey);
    if (
      current.status !== observed.status ||
      current.executionOwnerId !== observed.executionOwnerId ||
      current.heartbeatAt !== observed.heartbeatAt ||
      current.updatedAt !== observed.updatedAt
    ) {
      return current;
    }
    const inactiveSince = current.status === "ACCEPTED"
      ? current.updatedAt
      : current.heartbeatAt ?? current.updatedAt;
    if (clock().getTime() - Date.parse(inactiveSince) < 5_000) {
      return current;
    }
    const orphan = terminalState(current, "MANUAL_REVIEW", stableCode, clock());
    orphan.eventId = deterministicEventId(orphan);
    await store.write(tenantKey, request.operationId, orphan);
    return orphan;
  });
}

async function readBoundedRegularFile(file, maximum, code) {
  const before = await lstat(file).catch(() => null);
  if (before == null || !before.isFile() || before.isSymbolicLink() || before.size > maximum) {
    throw new ProtocolError(code, "bounded regular file is unavailable");
  }
  const noFollow = constants.O_NOFOLLOW ?? 0;
  const handle = await open(file, constants.O_RDONLY | noFollow).catch(() => {
    throw new ProtocolError(code, "bounded regular file could not be opened");
  });
  const chunks = [];
  let total = 0;
  let openedBefore;
  let openedAfter;
  try {
    openedBefore = await handle.stat();
    const buffer = Buffer.allocUnsafe(8192);
    while (true) {
      const { bytesRead } = await handle.read(buffer, 0, buffer.length, null);
      if (bytesRead === 0) break;
      total += bytesRead;
      if (total > maximum) {
        throw new ProtocolError(code, "bounded regular file exceeded its quota");
      }
      chunks.push(Buffer.from(buffer.subarray(0, bytesRead)));
    }
    openedAfter = await handle.stat();
  } finally {
    await handle.close();
  }
  const after = await lstat(file).catch(() => null);
  if (
    after == null ||
    !after.isFile() ||
    after.isSymbolicLink() ||
    total !== before.size ||
    total !== after.size ||
    total !== openedBefore.size ||
    total !== openedAfter.size ||
    !sameFileMetadata(before, openedBefore) ||
    !sameFileMetadata(openedBefore, openedAfter) ||
    !sameFileMetadata(openedAfter, after)
  ) {
    throw new ProtocolError(code, "bounded regular file changed while being read");
  }
  return Buffer.concat(chunks, total);
}

async function stableDirectory(directory) {
  const metadata = await lstat(directory).catch(() => null);
  if (metadata == null || !metadata.isDirectory() || metadata.isSymbolicLink()) {
    throw new ProtocolError("WORKER_WORKSPACE_LINK_REJECTED", "workspace directory is not trusted");
  }
  const resolved = await realpath(directory).catch(() => null);
  if (resolved == null || normalizeAbsolute(resolved) !== normalizeAbsolute(directory)) {
    throw new ProtocolError("WORKER_WORKSPACE_LINK_REJECTED", "workspace directory is redirected");
  }
  return metadata;
}

function sameFileMetadata(first, second) {
  if (first.dev === 0 || first.ino === 0 || second.dev === 0 || second.ino === 0) return true;
  return first.dev === second.dev && first.ino === second.ino;
}

async function withBusyRetry(store, tenantKey, operationId, action) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      return await store.withLock(tenantKey, operationId, action);
    } catch (error) {
      if (!(error instanceof ProtocolError) || error.code !== "WORKER_OPERATION_BUSY") {
        throw error;
      }
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
  }
  throw new ProtocolError("WORKER_OPERATION_BUSY", "operation remained busy", true);
}
