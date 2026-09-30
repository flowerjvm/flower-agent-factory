import { createHash, randomBytes } from "node:crypto";
import { constants } from "node:fs";
import { lstat, open, realpath, unlink } from "node:fs/promises";
import path from "node:path";
import { runCodexSentinelCommand, SENTINEL_READ_DENIED_EXIT_CODE } from "./credential-isolation-command-exec.mjs";
import { ProtocolError } from "./protocol.mjs";

export const PERMISSION_PROFILE_NAME = "factory-worker";
const MAX_CONFIG_BYTES = 32 * 1024;

export function expectedPermissionProfileConfig({ codexHome, stateRoot, sourceWorkspaceBase }) {
  const deniedRoots = [...new Set([codexHome, stateRoot, sourceWorkspaceBase].map(configuredRoot))]
    .sort((first, second) => first.localeCompare(second, "en"));
  return [
    `default_permissions = ${tomlString(PERMISSION_PROFILE_NAME)}`,
    'approval_policy = "never"',
    'web_search = "disabled"',
    "",
    `[permissions.${PERMISSION_PROFILE_NAME}]`,
    'description = "Factory operation workspace only; credential and host roots denied."',
    'extends = ":workspace"',
    "",
    `[permissions.${PERMISSION_PROFILE_NAME}.filesystem]`,
    '":root" = "deny"',
    '":minimal" = "read"',
    '":tmpdir" = "deny"',
    '":slash_tmp" = "deny"',
    ...deniedRoots.map((root) => `${tomlString(root)} = "deny"`),
    "",
    `[permissions.${PERMISSION_PROFILE_NAME}.network]`,
    "enabled = false",
    "",
    ...(process.platform === "win32" ? ["[windows]", 'sandbox = "elevated"', ""] : [])
  ].join("\n");
}

/**
 * Reads the pinned safety configuration without rewriting it. Codex 0.148.0 may append a
 * project trust record; only its observed literal-table form and Factory-materialized
 * operation workspaces are admitted. This is not a general TOML/configuration allowlist.
 */
export async function readValidatedPermissionProfileConfig({ codexHome, stateRoot, sourceWorkspaceBase }) {
  try {
    const roots = {
      codexHome: await trustedDirectory(codexHome),
      stateRoot: configuredRoot(stateRoot),
      sourceWorkspaceBase: configuredRoot(sourceWorkspaceBase)
    };
    const workerRoot = path.dirname(roots.codexHome);
    // A trusted project can load ancestor .codex config/hooks/rules. The Worker root is
    // outside the denied state subtree, so checking only workspace/.codex is insufficient.
    await rejectLocalCodex(workerRoot);
    await rejectLocalCodex(roots.stateRoot);
    const expected = Buffer.from(expectedPermissionProfileConfig(roots), "utf8");
    const actual = await readBoundedRegularFile(
      path.join(roots.codexHome, "config.toml"), MAX_CONFIG_BYTES, "WORKER_PERMISSION_PROFILE_INVALID"
    );
    if (!actual.subarray(0, expected.length).equals(expected)) throw unproven();
    const suffix = actual.subarray(expected.length).toString("utf8");
    if (!Buffer.from(suffix, "utf8").equals(actual.subarray(expected.length))) throw unproven();
    const projectBlock = /\n\[projects\.'([^'\r\n\u0000-\u001f\u007f]+)'\]\ntrust_level = "trusted"\n/uy;
    const seen = new Set();
    let offset = 0;
    while (offset < suffix.length) {
      projectBlock.lastIndex = offset;
      const match = projectBlock.exec(suffix);
      if (match == null) throw unproven();
      offset = projectBlock.lastIndex;
      const workspace = match[1];
      if (!path.isAbsolute(workspace) || workspace !== path.resolve(workspace)
          || workspace.startsWith("\\\\?\\")) throw unproven();
      const identity = normalizePath(workspace);
      if (seen.has(identity)) throw unproven();
      seen.add(identity);
      await requireFactoryProjectWorkspace(roots.stateRoot, workspace);
    }
    return actual;
  } catch {
    throw unproven();
  }
}

async function requireFactoryProjectWorkspace(stateRoot, workspace) {
  const relative = path.relative(stateRoot, workspace);
  const parts = relative.split(path.sep);
  if (parts.length !== 5 || parts[0] !== "tenants" || !/^[0-9a-f]{64}$/u.test(parts[1])
      || parts[2] !== "operations" || !/^[0-9a-f]{64}$/u.test(parts[3])
      || parts[4] !== "workspace" || path.isAbsolute(relative)) throw unproven();
  let directory = await trustedDirectory(stateRoot);
  await rejectLocalCodex(directory);
  for (const part of parts) {
    directory = await trustedDirectory(path.join(directory, part));
    // Reject a .codex file as well as a directory/link: Codex supports redirecting local
    // configuration, and neither generated config nor hooks/rules are Factory authority.
    await rejectLocalCodex(directory);
  }
  const marker = await readBoundedRegularFile(
    path.join(path.dirname(workspace), "workspace.ready"), 256, "WORKER_PERMISSION_PROFILE_INVALID"
  );
  const markerPrefix = `factory.codex-operation-workspace.v1\n${parts[3]}\n`;
  const markerText = marker.toString("utf8");
  if (!markerText.startsWith(markerPrefix)
      || !/^[0-9a-f]{64}\n$/u.test(markerText.slice(markerPrefix.length))) throw unproven();
}

async function rejectLocalCodex(directory) {
  const local = await lstat(path.join(directory, ".codex")).catch(error => {
    if (error?.code === "ENOENT") return null;
    throw error;
  });
  if (local != null) throw unproven();
}

/**
 * Runs a control read and exact denied-root reads through sandboxed app-server command/exec.
 * Only the reader's exact EACCES/EPERM exit code counts as denial. The same executable must
 * first read the workspace control file and return its exact bytes.
 */
export async function verifyCredentialIsolation({
  codexHome,
  stateRoot,
  sourceWorkspaceBase,
  workspaceRoot,
  codexPath,
  environment = {},
  runCommand = runCodexSentinelCommand
}) {
  const roots = {
    codexHome: await trustedDirectory(codexHome),
    stateRoot: await trustedDirectory(stateRoot),
    sourceWorkspaceBase: await trustedDirectory(sourceWorkspaceBase),
    workspaceRoot: await trustedDirectory(workspaceRoot)
  };
  const actual = await readValidatedPermissionProfileConfig(roots);

  const marker = `factory-isolation-${randomBytes(24).toString("hex")}`;
  const unique = randomBytes(16).toString("hex");
  const control = path.join(roots.workspaceRoot, `.factory-read-control-${unique}`);
  const denied = [...new Set([
    path.join(roots.codexHome, `.factory-read-denied-${unique}`),
    path.join(roots.stateRoot, `.factory-read-denied-${unique}`),
    path.join(roots.sourceWorkspaceBase, `.factory-read-denied-${unique}`)
  ])];
  const created = [];
  const evidence = [];
  let verificationFailure = null;
  try {
    for (const file of [control, ...denied]) {
      await writeExclusivePrivate(file, Buffer.from(marker, "utf8"));
      created.push(file);
    }
    const common = {
      codexHome: roots.codexHome,
      workspaceRoot: roots.workspaceRoot,
      codexPath,
      environment
    };
    const controlResult = await runCommand({ ...common, target: control });
    if (controlResult.exitCode !== 0 || controlResult.stdout.toString("utf8") !== marker) {
      throw unproven();
    }
    evidence.push(`control:${controlResult.exitCode}:${digest(controlResult.stdout)}`);
    for (const target of denied) {
      const deniedResult = await runCommand({ ...common, target });
      if (deniedResult.exitCode !== SENTINEL_READ_DENIED_EXIT_CODE || deniedResult.stdout.length !== 0) {
        throw unproven();
      }
      evidence.push(`deny:${deniedResult.exitCode}:${digest(deniedResult.stdout)}`);
    }
  } catch (error) {
    verificationFailure = error;
  } finally {
    for (const file of created) {
      try {
        await unlink(file);
      } catch {
        verificationFailure ??= unproven();
      }
    }
  }
  if (verificationFailure != null) throw verificationFailure;
  // Every probe loads the named profile. Do not certify evidence if its source changed meanwhile.
  const finalConfig = await readValidatedPermissionProfileConfig(roots);
  if (!finalConfig.equals(actual)) throw unproven();
  const configSha256 = digest(actual);
  return {
    profileName: PERMISSION_PROFILE_NAME,
    configSha256,
    deniedRootCount: denied.length,
    evidenceSha256: digest(Buffer.from([
      "factory.codex-worker.credential-isolation-evidence.v1",
      PERMISSION_PROFILE_NAME,
      configSha256,
      ...evidence
    ].join("\n"), "utf8"))
  };
}

async function trustedDirectory(value) {
  if (typeof value !== "string" || !path.isAbsolute(value)) throw unproven();
  const normalized = path.resolve(value);
  const metadata = await lstat(normalized).catch(() => null);
  if (metadata == null || !metadata.isDirectory() || metadata.isSymbolicLink()) throw unproven();
  const resolved = await realpath(normalized).catch(() => null);
  if (resolved == null || normalizePath(resolved) !== normalizePath(normalized)) throw unproven();
  return resolved;
}

async function writeExclusivePrivate(file, bytes) {
  const noFollow = constants.O_NOFOLLOW ?? 0;
  const handle = await open(
    file,
    constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | noFollow,
    0o600
  ).catch(() => {
    throw unproven();
  });
  try {
    await handle.writeFile(bytes);
    await handle.sync();
  } finally {
    await handle.close();
  }
}

async function readBoundedRegularFile(file, maximum, code) {
  const before = await lstat(file).catch(() => null);
  if (before == null || !before.isFile() || before.isSymbolicLink() || before.size > maximum) {
    throw new ProtocolError(code, "permission profile is not a bounded regular file");
  }
  const noFollow = constants.O_NOFOLLOW ?? 0;
  const handle = await open(file, constants.O_RDONLY | noFollow).catch(() => {
    throw new ProtocolError(code, "permission profile cannot be opened");
  });
  const buffer = Buffer.alloc(before.size + 1);
  let total = 0;
  try {
    const opened = await handle.stat();
    if (!opened.isFile() || !sameFile(before, opened) || opened.size !== before.size) {
      throw new ProtocolError(code, "permission profile changed while being opened");
    }
    while (total < buffer.length) {
      const { bytesRead } = await handle.read(buffer, total, buffer.length - total, null);
      if (bytesRead === 0) break;
      total += bytesRead;
    }
  } finally {
    await handle.close();
  }
  const after = await lstat(file).catch(() => null);
  if (
    total > maximum ||
    total !== before.size ||
    after == null ||
    !after.isFile() ||
    after.isSymbolicLink() ||
    after.size !== total ||
    after.mtimeMs !== before.mtimeMs ||
    !sameFile(before, after)
  ) {
    throw new ProtocolError(code, "permission profile changed while being read");
  }
  return buffer.subarray(0, total);
}

function configuredRoot(value) {
  if (typeof value !== "string" || !path.isAbsolute(value)) throw unproven();
  const normalized = path.resolve(value);
  if (/[\u0000-\u001f\u007f]/u.test(normalized)) throw unproven();
  return normalized;
}

function tomlString(value) {
  return `"${value.replace(/\\/gu, "\\\\").replace(/"/gu, '\\"')}"`;
}

function digest(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function sameFile(first, second) {
  if (first.dev === 0 || first.ino === 0 || second.dev === 0 || second.ino === 0) return true;
  return first.dev === second.dev && first.ino === second.ino;
}

function normalizePath(value) {
  const normalized = path.resolve(value);
  return process.platform === "win32" ? normalized.toLowerCase() : normalized;
}

function unproven() {
  return new ProtocolError(
    "WORKER_CREDENTIAL_ISOLATION_UNPROVEN",
    "credential and host filesystem read isolation is unproven"
  );
}
