import { createHash, createHmac } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  args.set(process.argv[index], process.argv[index + 1]);
}
const stateRoot = args.get("--state-root");
const mode = process.env.FACTORY_CODEX_MODEL ?? "normal";
const raw = await readStdin();
if (mode === "timeout") {
  await new Promise((resolve) => setTimeout(resolve, 5_000));
}
if (mode === "oversize") {
  process.stdout.write("x".repeat(300_000));
  process.exit(0);
}
let request;
try {
  request = JSON.parse(raw);
} catch {
  reply("unknown", false, { error: { code: "WORKER_INVALID_JSON", message: "invalid", retryable: false } });
  process.exit(2);
}
if (request.command !== "capabilities") {
  const token = process.env.FACTORY_WORKER_ATTEMPT_TOKEN;
  if (!token || raw.includes(token)) {
    reply(request.command, false, {
      error: { code: "WORKER_ATTEMPT_TOKEN_INVALID", message: "credential boundary invalid", retryable: false }
    });
    process.exit(2);
  }
}
if (request.command === "capabilities") {
  reply("capabilities", true, {
    worker: "codex",
    capabilities: [
      "repository-read",
      "bounded-patch-write",
      "file-create",
      "command-build-test",
      "structured-output",
      "progress-events",
      "cooperative-cancel",
      "usage-reporting",
      "sandbox-enforcement"
    ],
    execution: {
      approvalPolicy: "never",
      commandNetworkEnabled: false,
      credentialIsolation: "COMMAND_SENTINEL_PROVEN",
      credentialIsolationEvidenceSha256: "a".repeat(64),
      permissionProfile: "factory-worker",
      sessionResumeRequired: false,
      webSearchMode: "disabled"
    }
  });
  process.exit(0);
}

const operationHash = sha(Buffer.from(request.operationId));
const tenantHash = sha(Buffer.from(`factory.codex-worker.tenant-partition.v1\n${request.tenantId}`));
const operationDirectory = path.join(stateRoot, "tenants", tenantHash, "operations", operationHash);
const stateFile = path.join(operationDirectory, "fake-state.json");
await mkdir(operationDirectory, { recursive: true });
if (request.command === "submit") {
  const acceptedState = {
    tenantId: request.tenantId,
    workOrderId: request.workOrderId,
    workerRunId: request.workerRunId,
    operationId: request.operationId,
    effectAcceptedAt: new Date().toISOString(),
    effectTerminalAt: null,
    workspaceRoot: request.workspaceRoot,
    allowedWritePaths: request.allowedWritePaths
  };
  await writeFile(stateFile, JSON.stringify(acceptedState));
  await writeFile(path.join(operationDirectory, "last-request.json"), raw);
  replyWithSnapshot("submit", acceptedState, "WAITING_EXTERNAL", "WORKER_ACCEPTED", null);
  process.exit(0);
}

let state;
try {
  state = JSON.parse(await readFile(stateFile, "utf8"));
} catch {
  reply(request.command, true, { observation: "NOT_FOUND", snapshot: null });
  process.exit(0);
}
if (
  state.tenantId !== request.tenantId ||
  state.workOrderId !== request.workOrderId ||
  state.workerRunId !== request.workerRunId ||
  state.operationId !== request.operationId
) {
  reply(request.command, false, {
    error: { code: "WORKER_OPERATION_IDENTITY_MISMATCH", message: "mismatch", retryable: false }
  });
  process.exit(2);
}
if (request.command === "cancel") {
  replyWithSnapshot("cancel", state, "CANCEL_REQUESTED", "WORKER_CANCEL_REQUESTED", null);
  process.exit(0);
}

if (mode === "unknown-field") {
  const snapshot = snapshotOf(state, "WAITING_EXTERNAL", "WORKER_RUNNING", null);
  snapshot.surprise = "must be rejected";
  reply("status", true, { observation: "FOUND", snapshot });
  process.exit(0);
}
if (mode === "trailing-json") {
  replyWithSnapshot("status", state, "WAITING_EXTERNAL", "WORKER_RUNNING", null);
  process.stdout.write('{"second":"document"}\n');
  process.exit(0);
}
if (mode === "terminal-failed") {
  await markTerminal(state);
  const eventId = `evt_${sha(Buffer.from(`${state.operationId}\nFAILED\nempty`))}`;
  const completion = completionOf(state, "FAILED", "WORKER_PROVIDER_FAILED", eventId, [], null);
  replyWithSnapshot("status", state, "FAILED", "WORKER_PROVIDER_FAILED", completion);
  process.exit(0);
}
if (mode === "terminal-success" || mode === "terminal-success-design" || mode.startsWith("terminal-success-repair")) {
  await markTerminal(state);
  const repair = mode.startsWith("terminal-success-repair");
  const design = mode === "terminal-success-design";
  const outputPath = repair ? "source/changed.txt" : design ? "blueprint.json" : "candidate/result.txt";
  if (mode === "terminal-success-repair-readonly-mutated") {
    await writeFile(path.join(state.workspaceRoot, "source", "base.txt"), "malicious read-only mutation\n");
  }
  const candidate = path.join(state.workspaceRoot, ...outputPath.split("/"));
  await writeFile(
    candidate,
    repair ? "repaired by worker\n" : design ? '{"schemaVersion":"1","kind":"agent-blueprint"}\n' : "generated by worker\n"
  );
  const bytes = await readFile(candidate);
  const transcriptBytes = Buffer.from('{"events":[{"type":"turn.completed"}],"schemaVersion":"factory.codex-transcript.v1"}');
  await writeFile(path.join(operationDirectory, "transcript.json"), transcriptBytes);
  const outputs = [{
    relativePath: outputPath,
    sha256: sha(bytes),
    sizeBytes: bytes.length,
    kind: design ? "ADD" : "UPDATE"
  }];
  const resultHash = sha(Buffer.from(JSON.stringify(outputs)));
  const eventId = `evt_${sha(Buffer.from(`${state.operationId}\nSUCCEEDED\n${resultHash}`))}`;
  const completion = completionOf(state, "SUCCEEDED", "WORKER_SUCCEEDED", eventId, outputs, {
    relativePath: "transcript.json",
    sha256: sha(transcriptBytes),
    sizeBytes: transcriptBytes.length
  });
  replyWithSnapshot("status", state, "SUCCEEDED", "WORKER_SUCCEEDED", completion);
  process.exit(0);
}
replyWithSnapshot("status", state, "WAITING_EXTERNAL", "WORKER_RUNNING", null);

function completionOf(state, status, stableCode, eventId, outputs, transcript) {
  const material = `factory.worker.attempt-proof.v1\n${eventId}\n${state.operationId}\n${state.workerRunId}`;
  const proof = createHmac("sha256", process.env.FACTORY_WORKER_ATTEMPT_TOKEN).update(material).digest("hex");
  return {
    schemaVersion: "factory.coding-worker-completion.v1",
    eventId,
    workOrderId: state.workOrderId,
    workerRunId: state.workerRunId,
    operationId: state.operationId,
    status,
    outputs,
    transcript,
    stableCode,
    attemptProof: proof
  };
}

function replyWithSnapshot(command, state, status, stableCode, completionEnvelope) {
  reply(command, true, {
    observation: "FOUND",
    snapshot: snapshotOf(state, status, stableCode, completionEnvelope)
  });
}

function snapshotOf(state, status, stableCode, completionEnvelope) {
  const snapshot = {
    tenantId: state.tenantId,
    workOrderId: state.workOrderId,
    workerRunId: state.workerRunId,
    operationId: state.operationId,
    status,
    stableCode,
    effectAcceptedAt: state.effectAcceptedAt,
    effectTerminalAt: terminalStatus(status) ? state.effectTerminalAt : null,
    externalSessionRef: `codex-operation:${sha(Buffer.from(state.operationId))}`,
    completionEnvelope
  };
  const material = [
    "factory.worker.status-snapshot-proof.v2",
    snapshot.tenantId,
    snapshot.workOrderId,
    snapshot.workerRunId,
    snapshot.operationId,
    snapshot.status,
    snapshot.effectAcceptedAt,
    snapshot.effectTerminalAt ?? "",
    snapshot.stableCode
  ].join("\n");
  return {
    ...snapshot,
    snapshotProof: createHmac("sha256", process.env.FACTORY_WORKER_ATTEMPT_TOKEN)
      .update(material)
      .digest("hex")
  };
}

async function markTerminal(state) {
  state.effectTerminalAt ??= new Date().toISOString();
  await writeFile(stateFile, JSON.stringify(state));
}

function terminalStatus(status) {
  return ["SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "MANUAL_REVIEW"].includes(status);
}

function reply(command, ok, rest) {
  process.stdout.write(`${JSON.stringify({ protocolVersion: "flower-codex-worker/1", command, ok, ...rest })}\n`);
}

function sha(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

async function readStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  return Buffer.concat(chunks).toString("utf8");
}
