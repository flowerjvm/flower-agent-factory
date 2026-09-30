import { createHash, createHmac, timingSafeEqual } from "node:crypto";

export const PROTOCOL_VERSION = "flower-codex-worker/1";
export const DISPATCH_SCHEMA_VERSION = "factory.coding-worker-dispatch.v1";
export const COMPLETION_SCHEMA_VERSION = "factory.coding-worker-completion.v1";
export const RESULT_SCHEMA_VERSION = "factory.coding-worker-result-manifest.v1";
export const ATTEMPT_PROOF_VERSION = "factory.worker.attempt-proof.v1";
export const STATUS_SNAPSHOT_PROOF_VERSION = "factory.worker.status-snapshot-proof.v2";
export const MAX_REQUEST_BYTES = 192 * 1024;
export const MAX_RESPONSE_BYTES = 256 * 1024;

const COMMON_FIELDS = ["protocolVersion", "command"];
const SUBMIT_FIELDS = [
  ...COMMON_FIELDS,
  "schemaVersion",
  "tenantId",
  "workOrderId",
  "workerRunId",
  "operationId",
  "taskType",
  "purpose",
  "workspaceRef",
  "workspaceRoot",
  "allowedReadPaths",
  "allowedWritePaths",
  "requiredCapabilities",
  "expectedOutputSchemaId",
  "expectedOutputSchemaVersion",
  "instructionArtifactRef",
  "instructionSha256",
  "inputManifestArtifactRef",
  "inputManifestSha256",
  "policySnapshotRef",
  "deadlineAt"
];

export class ProtocolError extends Error {
  constructor(code, message, retryable = false) {
    super(message);
    this.name = "ProtocolError";
    this.code = stableCode(code);
    this.retryable = Boolean(retryable);
  }
}

export function parseRequest(raw) {
  if (typeof raw !== "string" || Buffer.byteLength(raw, "utf8") > MAX_REQUEST_BYTES) {
    throw new ProtocolError("WORKER_REQUEST_TOO_LARGE", "request exceeds the protocol byte limit");
  }
  let value;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new ProtocolError("WORKER_INVALID_JSON", "request is not valid JSON");
  }
  enforceTreeQuota(value);
  object(value, "request");
  text(value.protocolVersion, "protocolVersion", 64);
  if (value.protocolVersion !== PROTOCOL_VERSION) {
    throw new ProtocolError("WORKER_PROTOCOL_VERSION_UNSUPPORTED", "unsupported protocol version");
  }
  const command = text(value.command, "command", 32);
  if (command === "capabilities") {
    exactFields(value, COMMON_FIELDS, "capabilities request");
    return Object.freeze({ ...value });
  }
  if (command === "submit") {
    exactFields(value, SUBMIT_FIELDS, "submit request");
    if (value.schemaVersion !== DISPATCH_SCHEMA_VERSION) {
      throw new ProtocolError("WORKER_SCHEMA_VERSION_UNSUPPORTED", "unsupported dispatch schema version");
    }
    validateIdentity(value);
    text(value.taskType, "taskType", 128);
    text(value.purpose, "purpose", 16 * 1024);
    text(value.workspaceRef, "workspaceRef", 512);
    text(value.workspaceRoot, "workspaceRoot", 4096);
    textArray(value.allowedReadPaths, "allowedReadPaths", 128, 1024);
    textArray(value.allowedWritePaths, "allowedWritePaths", 128, 1024);
    textArray(value.requiredCapabilities, "requiredCapabilities", 64, 128);
    text(value.expectedOutputSchemaId, "expectedOutputSchemaId", 128);
    text(value.expectedOutputSchemaVersion, "expectedOutputSchemaVersion", 64);
    text(value.instructionArtifactRef, "instructionArtifactRef", 512);
    sha256(value.instructionSha256, "instructionSha256");
    text(value.inputManifestArtifactRef, "inputManifestArtifactRef", 512);
    sha256(value.inputManifestSha256, "inputManifestSha256");
    text(value.policySnapshotRef, "policySnapshotRef", 512);
    const deadline = Date.parse(text(value.deadlineAt, "deadlineAt", 64));
    if (!Number.isFinite(deadline)) {
      throw new ProtocolError("WORKER_INVALID_DEADLINE", "deadlineAt must be an ISO-8601 instant");
    }
    return deepFreeze(value);
  }
  if (command === "status") {
    exactFields(
      value,
      [...COMMON_FIELDS, "tenantId", "workOrderId", "workerRunId", "operationId"],
      "status request"
    );
    validateIdentity(value);
    return Object.freeze({ ...value });
  }
  if (command === "cancel") {
    exactFields(
      value,
      [...COMMON_FIELDS, "tenantId", "workOrderId", "workerRunId", "operationId", "reasonCode"],
      "cancel request"
    );
    validateIdentity(value);
    stableCode(value.reasonCode);
    return Object.freeze({ ...value });
  }
  throw new ProtocolError("WORKER_COMMAND_UNSUPPORTED", "unsupported command");
}

export function success(command, payload = {}) {
  const response = { protocolVersion: PROTOCOL_VERSION, command, ok: true, ...payload };
  const json = canonicalJson(response);
  if (Buffer.byteLength(json, "utf8") > MAX_RESPONSE_BYTES) {
    throw new ProtocolError("WORKER_RESPONSE_TOO_LARGE", "response exceeds the protocol byte limit");
  }
  return json;
}

export function failure(command, error) {
  const protocolError = error instanceof ProtocolError
    ? error
    : new ProtocolError("WORKER_INTERNAL_ERROR", "worker operation failed");
  return canonicalJson({
    protocolVersion: PROTOCOL_VERSION,
    command: typeof command === "string" ? command.slice(0, 32) : "unknown",
    ok: false,
    error: {
      code: protocolError.code,
      message: boundedMessage(protocolError.message),
      retryable: protocolError.retryable
    }
  });
}

export function canonicalJson(value) {
  return JSON.stringify(sortValue(value));
}

export function operationDigest(operationId) {
  return createHash("sha256").update(text(operationId, "operationId", 256), "utf8").digest("hex");
}

export function tenantPartition(tenantId) {
  const canonicalTenant = text(tenantId, "tenantId", 256);
  return createHash("sha256")
    .update(`factory.codex-worker.tenant-partition.v1\n${canonicalTenant}`, "utf8")
    .digest("hex");
}

export function eventIdFor(state) {
  return `evt_${createHash("sha256")
    .update(`${state.identity.operationId}\n${state.status}\n${state.updatedAt}`, "utf8")
    .digest("hex")}`;
}

export function attemptProof(tokenBuffer, eventId, operationId, workerRunId) {
  requireSecretBuffer(tokenBuffer, "attempt token");
  return createHmac("sha256", tokenBuffer)
    .update(`${ATTEMPT_PROOF_VERSION}\n${eventId}\n${operationId}\n${workerRunId}`, "utf8")
    .digest("hex");
}

export function verifyAttemptProof(tokenBuffer, proof, eventId, operationId, workerRunId) {
  const expected = Buffer.from(attemptProof(tokenBuffer, eventId, operationId, workerRunId), "ascii");
  const actual = Buffer.from(typeof proof === "string" ? proof : "", "ascii");
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

export function statusSnapshotProof(tokenBuffer, snapshot) {
  requireSecretBuffer(tokenBuffer, "attempt token");
  const material = [
    STATUS_SNAPSHOT_PROOF_VERSION,
    text(snapshot.tenantId, "tenantId", 256),
    text(snapshot.workOrderId, "workOrderId", 256),
    text(snapshot.workerRunId, "workerRunId", 256),
    text(snapshot.operationId, "operationId", 256),
    text(snapshot.status, "status", 32),
    text(snapshot.effectAcceptedAt, "effectAcceptedAt", 64),
    optionalInstant(snapshot.effectTerminalAt, "effectTerminalAt"),
    stableCode(snapshot.stableCode)
  ].join("\n");
  return createHmac("sha256", tokenBuffer).update(material, "utf8").digest("hex");
}

function optionalInstant(value, name) {
  if (value == null) return "";
  const candidate = text(value, name, 64);
  if (!Number.isFinite(Date.parse(candidate)) || new Date(candidate).toISOString() !== candidate) {
    throw new ProtocolError("WORKER_INVALID_FIELD", `${name} must be a canonical instant or null`);
  }
  return candidate;
}

export function captureSecrets(environment = process.env) {
  const attemptToken = capture(environment, "FACTORY_WORKER_ATTEMPT_TOKEN", true);
  return {
    attemptToken,
    dispose() {
      attemptToken?.fill(0);
    }
  };
}

function capture(environment, name, required) {
  const value = environment[name];
  environment[name] = "";
  delete environment[name];
  if (value == null || value.length === 0) {
    if (required) {
      throw new ProtocolError("WORKER_ATTEMPT_TOKEN_MISSING", "attempt credential is unavailable");
    }
    return null;
  }
  if (value.length > 4096 || /[\u0000-\u001f\u007f]/u.test(value)) {
    throw new ProtocolError("WORKER_SECRET_INVALID", "worker credential is invalid");
  }
  return Buffer.from(value, "utf8");
}

function validateIdentity(value) {
  text(value.workOrderId, "workOrderId", 256);
  validateRunIdentity(value);
}

function validateRunIdentity(value) {
  text(value.tenantId, "tenantId", 256);
  text(value.workerRunId, "workerRunId", 256);
  text(value.operationId, "operationId", 256);
}

function exactFields(value, allowed, name) {
  const expected = new Set(allowed);
  for (const key of Object.keys(value)) {
    if (!expected.has(key)) {
      throw new ProtocolError("WORKER_UNKNOWN_FIELD", `${name} contains an unknown field`);
    }
  }
  for (const key of expected) {
    if (!Object.hasOwn(value, key)) {
      throw new ProtocolError("WORKER_MISSING_FIELD", `${name} is missing a required field`);
    }
  }
}

function enforceTreeQuota(root) {
  let nodes = 0;
  const visit = (value, depth) => {
    nodes += 1;
    if (nodes > 2048 || depth > 8) {
      throw new ProtocolError("WORKER_JSON_QUOTA_EXCEEDED", "JSON shape exceeds protocol quotas");
    }
    if (typeof value === "string" && value.length > 32 * 1024) {
      throw new ProtocolError("WORKER_JSON_QUOTA_EXCEEDED", "JSON string exceeds protocol quotas");
    }
    if (Array.isArray(value)) {
      if (value.length > 256) {
        throw new ProtocolError("WORKER_JSON_QUOTA_EXCEEDED", "JSON array exceeds protocol quotas");
      }
      value.forEach((entry) => visit(entry, depth + 1));
    } else if (value && typeof value === "object") {
      const keys = Object.keys(value);
      if (keys.length > 128) {
        throw new ProtocolError("WORKER_JSON_QUOTA_EXCEEDED", "JSON object exceeds protocol quotas");
      }
      keys.forEach((key) => {
        text(key, "JSON field", 128);
        visit(value[key], depth + 1);
      });
    }
  };
  visit(root, 0);
}

function object(value, name) {
  if (value == null || typeof value !== "object" || Array.isArray(value)) {
    throw new ProtocolError("WORKER_INVALID_FIELD", `${name} must be an object`);
  }
  return value;
}

function text(value, name, maximumLength) {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.length > maximumLength ||
    /[\u0000-\u001f\u007f]/u.test(value)
  ) {
    throw new ProtocolError("WORKER_INVALID_FIELD", `${name} must be bounded non-control text`);
  }
  return value;
}

function textArray(value, name, maximumEntries, maximumEntryLength) {
  if (!Array.isArray(value) || value.length > maximumEntries) {
    throw new ProtocolError("WORKER_INVALID_FIELD", `${name} must be a bounded array`);
  }
  value.forEach((entry) => text(entry, `${name} entry`, maximumEntryLength));
  if (new Set(value).size !== value.length) {
    throw new ProtocolError("WORKER_INVALID_FIELD", `${name} must not contain duplicates`);
  }
  return value;
}

function sha256(value, name) {
  if (typeof value !== "string" || !/^[0-9a-f]{64}$/u.test(value)) {
    throw new ProtocolError("WORKER_INVALID_FIELD", `${name} must be lowercase SHA-256 text`);
  }
  return value;
}

function stableCode(value) {
  if (typeof value !== "string" || !/^[A-Z][A-Z0-9_]{0,127}$/u.test(value)) {
    throw new ProtocolError("WORKER_INVALID_STABLE_CODE", "stable code is invalid");
  }
  return value;
}

function requireSecretBuffer(value, name) {
  if (!Buffer.isBuffer(value) || value.length === 0 || value.length > 4096) {
    throw new ProtocolError("WORKER_SECRET_INVALID", `${name} is unavailable`);
  }
}

function boundedMessage(value) {
  const textValue = typeof value === "string" ? value : "worker operation failed";
  return textValue.replace(/[\u0000-\u001f\u007f]/gu, " ").slice(0, 256) || "worker operation failed";
}

function sortValue(value) {
  if (Array.isArray(value)) {
    return value.map(sortValue);
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sortValue(value[key])]));
  }
  return value;
}

function deepFreeze(value) {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    Object.values(value).forEach(deepFreeze);
  }
  return value;
}
