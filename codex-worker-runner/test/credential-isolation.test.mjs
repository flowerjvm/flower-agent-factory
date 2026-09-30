import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, readdir, realpath, rm, writeFile, rename, symlink } from "node:fs/promises";
import { createHash } from "node:crypto";
import os from "node:os";
import path from "node:path";
import { afterEach, test } from "node:test";
import {
  expectedPermissionProfileConfig,
  PERMISSION_PROFILE_NAME,
  readValidatedPermissionProfileConfig,
  verifyCredentialIsolation
} from "../src/credential-isolation.mjs";
import { ProtocolError } from "../src/protocol.mjs";

const roots = [];
afterEach(async () => {
  await Promise.all(roots.splice(0).map(async root => {
    const resolved = await realpath(root);
    assert.equal(path.dirname(resolved), await realpath(os.tmpdir()));
    assert.ok(path.basename(resolved).startsWith("factory-codex-isolation-"));
    await rm(resolved, { recursive: true, force: true });
  }));
});

test("factory permission profile denies host reads and contains no legacy sandbox settings", async () => {
  const fixture = await createFixture();
  const config = expectedPermissionProfileConfig(fixture);

  assert.match(config, /default_permissions = "factory-worker"/u);
  assert.match(config, /":root" = "deny"/u);
  assert.match(config, /":minimal" = "read"/u);
  assert.match(config, /":tmpdir" = "deny"/u);
  assert.match(config, /enabled = false/u);
  if (process.platform === "win32") {
    assert.match(config, /\[windows\]\nsandbox = "elevated"/u);
  }
  assert.doesNotMatch(config, /sandbox_mode|sandbox_workspace_write/u);
  for (const deniedRoot of [fixture.codexHome, fixture.stateRoot, fixture.sourceWorkspaceBase]) {
    assert.match(config, new RegExp(escapeRegex(tomlPath(deniedRoot)) + ' = "deny"', "u"));
  }
});

test("command sentinel requires one workspace control read and all host-root reads to fail", async () => {
  const fixture = await createFixture();
  await writeFile(
    path.join(fixture.codexHome, "config.toml"),
    expectedPermissionProfileConfig(fixture)
  );
  const observed = [];
  const proof = await verifyCredentialIsolation({
    ...fixture,
    async runCommand({ target }) {
      observed.push(target);
      if (isWithin(fixture.workspaceRoot, target)) {
        return { exitCode: 0, stdout: await readFile(target) };
      }
      return { exitCode: 77, stdout: Buffer.alloc(0) };
    }
  });

  assert.equal(proof.profileName, PERMISSION_PROFILE_NAME);
  assert.equal(proof.deniedRootCount, 3);
  assert.match(proof.configSha256, /^[0-9a-f]{64}$/u);
  assert.match(proof.evidenceSha256, /^[0-9a-f]{64}$/u);
  assert.equal(observed.length, 4);
});

test("tampered profile and readable denied sentinel both fail closed", async () => {
  const fixture = await createFixture();
  await writeFile(path.join(fixture.codexHome, "config.toml"), "default_permissions = \"factory-worker\"\n");
  await assert.rejects(
    verifyCredentialIsolation({
      ...fixture,
      async runCommand() {
        throw new Error("must not execute with a tampered profile");
      }
    }),
    unproven
  );

  await writeFile(
    path.join(fixture.codexHome, "config.toml"),
    expectedPermissionProfileConfig(fixture)
  );
  await assert.rejects(
    verifyCredentialIsolation({
      ...fixture,
      async runCommand({ target }) {
        return { exitCode: 0, stdout: await readFile(target) };
      }
    }),
    unproven
  );
});

test("profile drift during successful fake probes fails closed and still removes every synthetic file", async () => {
  const fixture = await createFixture();
  await writeFile(path.join(fixture.codexHome, "config.toml"), expectedPermissionProfileConfig(fixture));
  let calls = 0;
  await assert.rejects(verifyCredentialIsolation({ ...fixture, async runCommand({ target }) {
    calls++;
    if (calls === 1) return { exitCode: 0, stdout: await readFile(target) };
    if (calls === 4) await writeFile(path.join(fixture.codexHome, "config.toml"), "changed during probe\n");
    return { exitCode: 77, stdout: Buffer.alloc(0) };
  } }), unproven);
  assert.equal(calls, 4);
  for (const root of [fixture.codexHome, fixture.stateRoot, fixture.sourceWorkspaceBase, fixture.workspaceRoot]) {
    assert.equal((await readdir(root)).some(name => name.startsWith(".factory-read-")), false);
  }
});

test("generic command failure, ENOENT, timeout and denial with stdout never count as read-isolation evidence", async () => {
  const fixture = await createFixture();
  await writeFile(path.join(fixture.codexHome, "config.toml"), expectedPermissionProfileConfig(fixture));
  for (const [exitCode, stdout] of [
    [1, ""], [74, ""], [124, ""], [137, ""], [-1, ""], [77, "unexpected output"]
  ]) {
    let calls = 0;
    await assert.rejects(verifyCredentialIsolation({ ...fixture, async runCommand({ target }) {
      calls++;
      if (calls === 1) return { exitCode: 0, stdout: await readFile(target) };
      return { exitCode, stdout: Buffer.from(stdout) };
    } }), unproven);
    assert.equal(calls, 2);
  }
});

test("only exact Factory project metadata is admitted and all four sentinels still run with full config hash", async () => {
  const fixture = await createFixture();
  const first = await materializedProject(fixture, "a");
  const second = await materializedProject(fixture, "b");
  const actual = expectedPermissionProfileConfig(fixture) + projectBlock(first) + projectBlock(second);
  await writeFile(path.join(fixture.codexHome, "config.toml"), actual);
  assert.equal((await readValidatedPermissionProfileConfig(fixture)).toString("utf8"), actual);
  let calls = 0;
  const proof = await verifyCredentialIsolation({ ...fixture, async runCommand({ target }) {
    calls++;
    return isWithin(fixture.workspaceRoot, target)
      ? { exitCode: 0, stdout: await readFile(target) } : { exitCode: 77, stdout: Buffer.alloc(0) };
  } });
  assert.equal(calls, 4);
  assert.equal(proof.configSha256, createHash("sha256").update(actual).digest("hex"));
  assert.equal(await readFile(path.join(fixture.codexHome, "config.toml"), "utf8"), actual);
});

test("project metadata never admits modified safety prefix, broader paths, unknown TOML or duplicates", async () => {
  const fixture = await createFixture();
  const workspace = await materializedProject(fixture, "a");
  const base = expectedPermissionProfileConfig(fixture);
  const valid = projectBlock(workspace);
  const variants = [
    base.replace('enabled = false', 'enabled = true') + valid,
    base.replace('":root" = "deny"', '":root" = "read"') + valid,
    base.replace('approval_policy = "never"', 'approval_policy = "on-request"') + valid,
    base + valid + valid,
    base + valid + projectBlock(workspace.toUpperCase()),
    base + valid.replace('"trusted"', '"untrusted"'),
    base + valid.replace('"trusted"', '"trusted"\nmodel = "other"'),
    base + valid.replace(/\n/gu, "\r\n"),
    base + valid.replace("[projects.'", '[projects."').replace("']", '"]'),
    base + valid + '# unexpected comment\n',
    base + '\n[features]\nunknown = true\n',
    base + projectBlock(path.dirname(workspace)),
    base + projectBlock(fixture.sourceWorkspaceBase),
    base + projectBlock(path.join(workspace, "child")),
    base + projectBlock(path.join(workspace, "..", "workspace") + path.sep),
    base + projectBlock(workspace.replace("a".repeat(64), "A".repeat(64))),
    base + projectBlock("\\\\?\\" + workspace),
    base + "x".repeat(32769),
    base + valid + '\u0000'
  ];
  for (const [index, config] of variants.entries()) {
    await writeFile(path.join(fixture.codexHome, "config.toml"), config);
    let calls = 0;
    await assert.rejects(verifyCredentialIsolation({ ...fixture, async runCommand() { calls++; } }), unproven, `variant ${index}`);
    assert.equal(calls, 0, `variant ${index} must fail before sentinel/CLI`);
  }
});

test("project authority requires private regular materialization marker and exact operation digest", async () => {
  for (const kind of ["missing", "directory", "wrong-digest", "oversized", "linked"]) {
    const fixture = await createFixture();
    const workspace = await materializedProject(fixture, "a");
    const marker = path.join(path.dirname(workspace), "workspace.ready");
    await rename(marker, marker + ".saved");
    if (kind === "directory") await mkdir(marker);
    if (kind === "wrong-digest") await writeFile(marker, `factory.codex-operation-workspace.v1\n${"b".repeat(64)}\n${"c".repeat(64)}\n`);
    if (kind === "oversized") await writeFile(marker, "x".repeat(257));
    if (kind === "linked") await symlink(path.dirname(marker), marker, process.platform === "win32" ? "junction" : "dir");
    await writeFile(path.join(fixture.codexHome, "config.toml"), expectedPermissionProfileConfig(fixture) + projectBlock(workspace));
    await assert.rejects(readValidatedPermissionProfileConfig(fixture), unproven, kind);
  }
});

test("trusted project rejects .codex config, hooks, rules or redirects at workspace and every Factory ancestor", async () => {
  for (const level of [0, 1, 2, 3, 4, 5, 6]) {
    const fixture = await createFixture();
    const workspace = await materializedProject(fixture, "a");
    let ancestor = workspace;
    for (let step = 0; step < level; step++) ancestor = path.dirname(ancestor);
    if (level % 2 === 0) await mkdir(path.join(ancestor, ".codex"));
    else await writeFile(path.join(ancestor, ".codex"), "fixture-only redirect");
    await writeFile(path.join(fixture.codexHome, "config.toml"), expectedPermissionProfileConfig(fixture) + projectBlock(workspace));
    await assert.rejects(readValidatedPermissionProfileConfig(fixture), unproven, `ancestor ${level}`);
  }
});

test("project workspace and Factory-state ancestor junctions are rejected", async () => {
  for (const levels of [0, 1, 2, 3, 4, 5]) {
    const fixture = await createFixture();
    const workspace = await materializedProject(fixture, "a");
    let linked = workspace;
    for (let step = 0; step < levels; step++) linked = path.dirname(linked);
    const target = linked + "-saved";
    await rename(linked, target);
    await symlink(target, linked, process.platform === "win32" ? "junction" : "dir");
    await writeFile(path.join(fixture.codexHome, "config.toml"), expectedPermissionProfileConfig(fixture) + projectBlock(workspace));
    await assert.rejects(readValidatedPermissionProfileConfig(fixture), unproven, `junction ${levels}`);
  }
});

test("even independently valid project additions during probes invalidate the full byte snapshot", async () => {
  const fixture = await createFixture();
  const first = await materializedProject(fixture, "a"), second = await materializedProject(fixture, "b");
  const before = expectedPermissionProfileConfig(fixture) + projectBlock(first);
  const after = before + projectBlock(second);
  await writeFile(path.join(fixture.codexHome, "config.toml"), before);
  let calls = 0;
  await assert.rejects(verifyCredentialIsolation({ ...fixture, async runCommand({ target }) {
    calls++;
    if (calls === 4) await writeFile(path.join(fixture.codexHome, "config.toml"), after);
    return isWithin(fixture.workspaceRoot, target)
      ? { exitCode: 0, stdout: await readFile(target) } : { exitCode: 77, stdout: Buffer.alloc(0) };
  } }), unproven);
  assert.equal(calls, 4);
  assert.equal((await readValidatedPermissionProfileConfig(fixture)).toString("utf8"), after);
});

async function materializedProject(fixture, character) {
  const operation = character.repeat(64);
  const workspace = path.join(fixture.stateRoot, "tenants", "f".repeat(64), "operations", operation, "workspace");
  await mkdir(workspace, { recursive: true });
  await writeFile(path.join(path.dirname(workspace), "workspace.ready"),
    `factory.codex-operation-workspace.v1\n${operation}\n${"c".repeat(64)}\n`, { flag: "wx" });
  return workspace;
}

function projectBlock(workspace) { return `\n[projects.'${workspace}']\ntrust_level = "trusted"\n`; }

test("installed Codex command/exec enforces the exact profile without an account", {
  skip: process.env.FACTORY_CODEX_PERMISSION_PROFILE_INTEGRATION !== "1"
}, async () => {
  const fixture = await createFixture();
  await writeFile(
    path.join(fixture.codexHome, "config.toml"),
    expectedPermissionProfileConfig(fixture)
  );
  const proof = await verifyCredentialIsolation({ ...fixture, environment: process.env });
  assert.match(proof.evidenceSha256, /^[0-9a-f]{64}$/u);
});

async function createFixture() {
  const root = await mkdtemp(path.join(os.tmpdir(), "factory-codex-isolation-"));
  roots.push(root);
  const fixture = {
    codexHome: path.join(root, "codex-home"),
    stateRoot: path.join(root, "state"),
    sourceWorkspaceBase: path.join(root, "source-workspaces"),
    workspaceRoot: path.join(root, "state", "operation", "workspace")
  };
  await Promise.all([
    mkdir(fixture.codexHome),
    mkdir(fixture.sourceWorkspaceBase),
    mkdir(fixture.workspaceRoot, { recursive: true })
  ]);
  return fixture;
}

function isWithin(base, candidate) {
  const relative = path.relative(base, candidate);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

function tomlPath(value) {
  return `"${value.replace(/\\/gu, "\\\\").replace(/"/gu, '\\"')}"`;
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
}

function unproven(error) {
  return error instanceof ProtocolError && error.code === "WORKER_CREDENTIAL_ISOLATION_UNPROVEN";
}
