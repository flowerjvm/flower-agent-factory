import assert from "node:assert/strict";
import { test } from "node:test";
import {
  attemptProof,
  captureSecrets,
  DISPATCH_SCHEMA_VERSION,
  parseRequest,
  PROTOCOL_VERSION,
  ProtocolError,
  verifyAttemptProof
} from "../src/protocol.mjs";

test("strict versioned requests reject unknown fields and incomplete status identity", () => {
  const request = baseSubmit();
  assert.equal(parseRequest(JSON.stringify(request)).command, "submit");
  assert.throws(
    () => parseRequest(JSON.stringify({ ...request, surprise: true })),
    (error) => error instanceof ProtocolError && error.code === "WORKER_UNKNOWN_FIELD"
  );
  assert.throws(
    () => parseRequest(JSON.stringify({ protocolVersion: PROTOCOL_VERSION, command: "status", tenantId: "t", workerRunId: "r" })),
    (error) => error instanceof ProtocolError && error.code === "WORKER_MISSING_FIELD"
  );
});

test("path arrays and JSON shape are bounded before execution", () => {
  const request = baseSubmit();
  request.allowedWritePaths = Array.from({ length: 129 }, (_, index) => `candidate/${index}`);
  assert.throws(
    () => parseRequest(JSON.stringify(request)),
    (error) => error instanceof ProtocolError && error.code === "WORKER_INVALID_FIELD"
  );
  assert.throws(
    () => parseRequest("{" + "x".repeat(200_000)),
    (error) => error instanceof ProtocolError && error.code === "WORKER_REQUEST_TOO_LARGE"
  );
});

test("attempt secret leaves process environment and its buffer can be zeroed", () => {
  const environment = {
    FACTORY_WORKER_ATTEMPT_TOKEN: "attempt-secret-value"
  };
  const secrets = captureSecrets(environment);
  assert.equal(environment.FACTORY_WORKER_ATTEMPT_TOKEN, undefined);
  assert.equal(secrets.attemptToken.toString("utf8"), "attempt-secret-value");
  secrets.dispose();
  assert.ok(secrets.attemptToken.every((byte) => byte === 0));
});

test("attempt proof follows the cross-language versioned HMAC contract", () => {
  const attempt = Buffer.from("attempt-token");
  const proof = attemptProof(attempt, "event-1", "operation-1", "run-1");
  assert.equal(proof, "8e095c1c0627141eae7f9cab4feb7bfd5a4e7036b1f24bcc1eed984d10646fb6");
  assert.equal(verifyAttemptProof(attempt, proof, "event-1", "operation-1", "run-1"), true);
  assert.equal(verifyAttemptProof(attempt, proof, "event-1", "operation-2", "run-1"), false);
});

function baseSubmit() {
  return {
    protocolVersion: PROTOCOL_VERSION,
    command: "submit",
    schemaVersion: DISPATCH_SCHEMA_VERSION,
    tenantId: "tenant-a",
    workOrderId: "work-1",
    workerRunId: "run-1",
    operationId: "operation-1",
    taskType: "generate-candidate",
    purpose: "Generate a bounded candidate",
    workspaceRef: "workspace:one",
    workspaceRoot: "C:\\factory\\workspaces\\one",
    allowedReadPaths: ["inputs"],
    allowedWritePaths: ["candidate"],
    requiredCapabilities: ["repository-read"],
    expectedOutputSchemaId: "factory.pack-candidate",
    expectedOutputSchemaVersion: "1",
    instructionArtifactRef: "artifact:instruction",
    instructionSha256: "a".repeat(64),
    inputManifestArtifactRef: "artifact:manifest",
    inputManifestSha256: "b".repeat(64),
    policySnapshotRef: "artifact:policy",
    deadlineAt: "2099-01-01T00:00:00Z"
  };
}
