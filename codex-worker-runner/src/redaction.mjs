import path from "node:path";
import { canonicalJson, ProtocolError } from "./protocol.mjs";

const MAX_EVENTS = 256;
const MAX_TRANSCRIPT_BYTES = 64 * 1024;
const SECRET_PATTERNS = [
  /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/u,
  /(?<![A-Za-z0-9])sk-(?:proj-)?[A-Za-z0-9_-]{20,}/u,
  /(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9]{20,}/u,
  /(?<![A-Z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Z0-9])/u,
  /(?<![A-Za-z0-9])xox[baprs]-[A-Za-z0-9-]{20,}/u
];

export class TranscriptProjector {
  constructor(workspaceRoot) {
    this.workspaceRoot = path.resolve(workspaceRoot);
    this.events = [];
  }

  add(event) {
    if (this.events.length >= MAX_EVENTS) {
      return;
    }
    const projected = projectEvent(event, this.workspaceRoot);
    if (projected != null) {
      const candidate = [...this.events, projected];
      if (Buffer.byteLength(canonicalJson(candidate), "utf8") <= MAX_TRANSCRIPT_BYTES) {
        this.events.push(projected);
      }
    }
  }

  value() {
    return Object.freeze(this.events.map((event) => Object.freeze(event)));
  }
}

export function assertNoKnownSecret(bytes) {
  const text = Buffer.isBuffer(bytes) ? bytes.toString("utf8") : String(bytes);
  if (SECRET_PATTERNS.some((pattern) => pattern.test(text))) {
    throw new ProtocolError("WORKER_SECRET_DETECTED", "generated output contains credential-like content");
  }
}

function projectEvent(event, workspaceRoot) {
  if (event == null || typeof event !== "object" || typeof event.type !== "string") {
    return null;
  }
  if (event.type === "thread.started") {
    return { type: "thread.started" };
  }
  if (event.type === "turn.started") {
    return { type: "turn.started" };
  }
  if (event.type === "turn.completed") {
    return { type: "turn.completed", usage: safeUsage(event.usage) };
  }
  if (event.type === "turn.failed" || event.type === "error") {
    return { type: "turn.failed" };
  }
  if (!["item.started", "item.updated", "item.completed"].includes(event.type)) {
    return null;
  }
  const item = event.item;
  if (item == null || typeof item !== "object" || typeof item.type !== "string") {
    return null;
  }
  const base = { type: event.type, itemType: safeToken(item.type) };
  if (item.type === "file_change" && Array.isArray(item.changes)) {
    return {
      ...base,
      status: safeToken(item.status),
      changes: item.changes.slice(0, 128).map((change) => ({
        path: safeRelativePath(change?.path, workspaceRoot),
        kind: safeToken(change?.kind)
      }))
    };
  }
  if (item.type === "command_execution") {
    return {
      ...base,
      status: safeToken(item.status),
      ...(Number.isSafeInteger(item.exit_code) ? { exitCode: item.exit_code } : {})
    };
  }
  if (item.type === "mcp_tool_call") {
    return { ...base, status: safeToken(item.status) };
  }
  return base;
}

function safeUsage(value) {
  const result = {};
  for (const key of [
    "input_tokens",
    "cached_input_tokens",
    "cache_write_input_tokens",
    "output_tokens",
    "reasoning_output_tokens"
  ]) {
    const number = value?.[key];
    result[key] = Number.isSafeInteger(number) && number >= 0 ? number : 0;
  }
  return result;
}

function safeRelativePath(value, workspaceRoot) {
  if (typeof value !== "string") {
    return "[INVALID_PATH]";
  }
  const absolute = path.resolve(workspaceRoot, value);
  const relative = path.relative(workspaceRoot, absolute);
  if (relative === "" || relative.startsWith("..") || path.isAbsolute(relative)) {
    return "[OUTSIDE_WORKSPACE]";
  }
  return relative.split(path.sep).join("/").slice(0, 1024);
}

function safeToken(value) {
  return typeof value === "string" && /^[a-z0-9_.-]{1,64}$/iu.test(value) ? value : "unknown";
}
