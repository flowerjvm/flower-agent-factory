import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, realpath, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { CodexSdkBackend } from "../src/codex-operation.mjs";

if (
  process.env.FACTORY_CODEX_SMOKE !== "1" ||
  process.env.FACTORY_CODEX_SMOKE_DEDICATED_PROFILE !== "YES"
) {
  process.stdout.write(
    "NOT_RUN: set FACTORY_CODEX_SMOKE=1 and " +
      "FACTORY_CODEX_SMOKE_DEDICATED_PROFILE=YES with a dedicated FACTORY_CODEX_HOME\n"
  );
  process.exit(0);
}

const configuredHome = process.env.FACTORY_CODEX_HOME;
const configuredStateRoot = process.env.FACTORY_CODEX_STATE_ROOT;
const configuredSourceWorkspaceBase = process.env.FACTORY_CODEX_SOURCE_WORKSPACE_BASE;
if ([configuredHome, configuredStateRoot, configuredSourceWorkspaceBase]
  .some((value) => typeof value !== "string" || !path.isAbsolute(value))) {
  process.stderr.write("SMOKE_CONFIGURATION_INVALID: dedicated profile/state/source roots must be absolute\n");
  process.exit(2);
}
const codexHome = await realpath(configuredHome).catch(() => null);
const stateRoot = await realpath(configuredStateRoot).catch(() => null);
const sourceWorkspaceBase = await realpath(configuredSourceWorkspaceBase).catch(() => null);
if (codexHome == null || stateRoot == null || sourceWorkspaceBase == null) {
  process.stderr.write("SMOKE_CONFIGURATION_INVALID: dedicated profile/state/source roots are unavailable\n");
  process.exit(2);
}

const root = await mkdtemp(path.join(stateRoot, "factory-codex-smoke-"));
try {
  const workspace = path.join(root, "operations", "smoke", "workspace");
  const operation = path.dirname(workspace);
  const inputs = path.join(operation, "inputs");
  await mkdir(path.join(inputs, "locked"), { recursive: true });
  await mkdir(workspace, { recursive: true });
  const instruction = "Create candidate/smoke.txt containing exactly: factory codex smoke ok\n";
  await writeFile(path.join(inputs, "instruction"), instruction);
  await writeFile(path.join(inputs, "input-manifest.json"), '{"schemaVersion":"smoke.v1"}\n');
  await writeFile(path.join(inputs, "policy"), "No network. Modify only candidate/smoke.txt.\n");
  await writeFile(path.join(inputs, "locked", "skill"), "Perform the bounded file creation only.\n");
  const events = [];
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 120_000);
  try {
    await new CodexSdkBackend().run({
      request: {
        taskType: "codex-sdk-smoke",
        purpose: "Verify the dedicated Codex permission profile and bounded workspace",
        workspaceRoot: workspace,
        allowedWritePaths: ["candidate/smoke.txt"]
      },
      operationDirectory: operation,
      signal: controller.signal,
      onEvent: (event) => events.push(event?.type ?? "unknown"),
      trustedEnvironment: {
        ...safeLaunchEnvironment(process.env),
        FACTORY_CODEX_HOME: codexHome,
        FACTORY_CODEX_STATE_ROOT: stateRoot,
        FACTORY_CODEX_SOURCE_WORKSPACE_BASE: sourceWorkspaceBase
      }
    });
  } finally {
    clearTimeout(timeout);
  }
  const output = await readFile(path.join(workspace, "candidate", "smoke.txt"), "utf8");
  if (output !== "factory codex smoke ok\n") {
    throw new Error("SMOKE_OUTPUT_INVALID");
  }
  process.stdout.write(
    `PASS: dedicated profile smoke completed; eventTypeDigest=${digest(events.join("\n"))}\n`
  );
} finally {
  await rm(root, { recursive: true, force: true });
}

function safeLaunchEnvironment(source) {
  const result = {};
  for (const name of ["PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC"]) {
    if (typeof source[name] === "string" && source[name].length <= 32 * 1024) result[name] = source[name];
  }
  return result;
}

function digest(value) {
  return createHash("sha256").update(value).digest("hex");
}
