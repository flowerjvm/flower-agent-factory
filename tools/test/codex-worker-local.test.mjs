import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, writeFile, readdir, lstat, realpath, rm, rmdir, symlink } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { EventEmitter } from "node:events";
import { PassThrough, Writable } from "node:stream";
import { runLocalWorkerHelper } from "../codex-worker-local.mjs";
import { expectedPermissionProfileConfig } from "../../codex-worker-runner/src/credential-isolation.mjs";

const repository = fileURLToPath(new URL("../..", import.meta.url));
const fixtureAuth = "fixture-only; not an authentication credential\n";

async function fixture(t) {
  const temporaryParent = await realpath(os.tmpdir());
  const base = await mkdtemp(path.join(temporaryParent, "factory-local-helper-test-"));
  t.after(async () => {
    const resolved = await realpath(base);
    assert.equal(resolved, base);
    assert.equal(path.dirname(resolved), temporaryParent);
    assert.ok(path.basename(resolved).startsWith("factory-local-helper-test-"));
    // The exact dedicated mkdtemp target is checked above; rm never targets a home or workspace.
    await rm(resolved, { recursive: true, force: true });
  });
  const home = path.join(base, "user-home");
  const defaultCodexHome = path.join(home, ".codex");
  const customCodexHome = path.join(base, "saved-custom-codex");
  await mkdir(defaultCodexHome, { recursive: true });
  await mkdir(customCodexHome);
  for (const directory of [defaultCodexHome, customCodexHome]) {
    await writeFile(path.join(directory, "config.toml"), "# preserved fixture config\n", { flag: "wx" });
    await writeFile(path.join(directory, "auth.json"), fixtureAuth, { flag: "wx" });
  }
  const root = path.join(base, "dedicated-worker");
  const output = [];
  const forbidden = () => { throw new Error("TEST_MUST_NOT_CALL_LIVE_CODEX"); };
  const environment = {
    USERPROFILE: home, HOME: home, CODEX_HOME: customCodexHome,
    PATH: "fixture-path", OPENAI_API_KEY: "not-a-real-key", CODEX_API_KEY: "not-a-real-key",
    NODE_OPTIONS: "must-not-inherit", HTTP_PROXY: "must-not-inherit"
  };
  const dependencies = {
    environment, writeOutput: (text) => output.push(text),
    checkAuthentication: forbidden, checkIsolation: forbidden, spawnProcess: forbidden
  };
  const roots = {
    codexHome: path.join(root, "codex-home"), stateRoot: path.join(root, "state"),
    sourceWorkspaceBase: path.join(root, "source")
  };
  return { base, root, roots, home, defaultCodexHome, customCodexHome, output, dependencies };
}

test("prepare is idempotent and preserves dedicated auth, state, and default/custom Codex files", async (t) => {
  const f = await fixture(t);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  const configPath = path.join(f.roots.codexHome, "config.toml");
  const markerPath = path.join(f.root, ".factory-local-profile");
  const configBefore = await lstat(configPath);
  const markerBefore = await lstat(markerPath);
  await writeFile(path.join(f.roots.codexHome, "auth.json"), fixtureAuth, { flag: "wx" });
  await writeFile(path.join(f.roots.stateRoot, "state-fixture.txt"), "preserved state", { flag: "wx" });

  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);

  assert.equal(await readFile(configPath, "utf8"), expectedPermissionProfileConfig(f.roots));
  assert.equal((await lstat(configPath)).mtimeMs, configBefore.mtimeMs);
  assert.equal((await lstat(markerPath)).mtimeMs, markerBefore.mtimeMs);
  assert.equal(await readFile(path.join(f.roots.codexHome, "auth.json"), "utf8"), fixtureAuth);
  assert.equal(await readFile(path.join(f.roots.stateRoot, "state-fixture.txt"), "utf8"), "preserved state");
  for (const directory of [f.defaultCodexHome, f.customCodexHome]) {
    assert.equal(await readFile(path.join(directory, "config.toml"), "utf8"), "# preserved fixture config\n");
    assert.equal(await readFile(path.join(directory, "auth.json"), "utf8"), fixtureAuth);
    assert.deepEqual((await readdir(directory)).sort(), ["auth.json", "config.toml"]);
  }
  assert.equal(f.output.length, 2);
  for (const line of f.output) {
    assert.equal(JSON.parse(line).authentication, "NOT_RUN");
    assert.equal(JSON.parse(line).status, "PREPARED");
  }
});

test("prepare preserves validated auto project metadata and status uses the same read-only profile gate", async (t) => {
  const f = await fixture(t);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  const operation = "a".repeat(64);
  const workspace = path.join(f.roots.stateRoot, "tenants", "b".repeat(64), "operations", operation, "workspace");
  await mkdir(workspace, { recursive: true });
  await writeFile(path.join(path.dirname(workspace), "workspace.ready"),
    `factory.codex-operation-workspace.v1\n${operation}\n${"c".repeat(64)}\n`, { flag: "wx" });
  const configPath = path.join(f.roots.codexHome, "config.toml");
  const config = expectedPermissionProfileConfig(f.roots) + `\n[projects.'${workspace}']\ntrust_level = "trusted"\n`;
  await writeFile(configPath, config);
  const before = await lstat(configPath);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  let authenticated = 0;
  await runLocalWorkerHelper(["status", f.root], { ...f.dependencies, async checkAuthentication() {
    authenticated++;
    return { method: "chatgpt" };
  } });
  assert.equal(authenticated, 1);
  assert.equal(await readFile(configPath, "utf8"), config);
  assert.equal((await lstat(configPath)).mtimeMs, before.mtimeMs);
  assert.equal((await lstat(configPath)).ino, before.ino);
  assert.equal(JSON.parse(f.output.at(-1)).status, "AUTHENTICATED");
  await mkdir(path.join(workspace, ".codex"));
  for (const mode of ["prepare", "status", "isolation", "sandbox-setup"]) {
    await assert.rejects(runLocalWorkerHelper([mode, f.root], f.dependencies), /LOCAL_PROFILE_CONFIG_MISMATCH/);
  }
  assert.equal(await readFile(configPath, "utf8"), config);
});

test("helper rejects a Worker-root .codex redirect and external trust additions without cleanup or auth", async (t) => {
  const f = await fixture(t);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  const configPath = path.join(f.roots.codexHome, "config.toml");
  const base = expectedPermissionProfileConfig(f.roots);
  const external = base + `\n[projects.'${f.home}']\ntrust_level = "trusted"\n`;
  await writeFile(configPath, external);
  await assert.rejects(runLocalWorkerHelper(["prepare", f.root], f.dependencies), /LOCAL_PROFILE_CONFIG_MISMATCH/);
  assert.equal(await readFile(configPath, "utf8"), external);
  await writeFile(configPath, base);
  await writeFile(path.join(f.root, ".codex"), "fixture-only redirect", { flag: "wx" });
  await assert.rejects(runLocalWorkerHelper(["status", f.root], f.dependencies), /LOCAL_PROFILE_CONFIG_MISMATCH/);
  assert.equal(await readFile(path.join(f.root, ".codex"), "utf8"), "fixture-only redirect");
  assert.equal(await readFile(configPath, "utf8"), base);
});

test("prepare does not adopt an existing nonempty root or modify unrelated files", async (t) => {
  const f = await fixture(t);
  await mkdir(f.root);
  await writeFile(path.join(f.root, "existing.txt"), "preserve this", { flag: "wx" });
  await assert.rejects(runLocalWorkerHelper(["prepare", f.root], f.dependencies), /UNPREPARED_LOCAL_ROOT_NOT_EMPTY/);
  assert.deepEqual(await readdir(f.root), ["existing.txt"]);
  assert.equal(await readFile(path.join(f.root, "existing.txt"), "utf8"), "preserve this");
});

test("prepare refuses changed marker/config before adding missing child directories", async (t) => {
  const f = await fixture(t);
  await mkdir(f.root);
  await writeFile(path.join(f.root, ".factory-local-profile"), "unrelated-marker\n", { flag: "wx" });
  await assert.rejects(runLocalWorkerHelper(["prepare", f.root], f.dependencies), /LOCAL_PROFILE_NOT_PREPARED/);
  assert.deepEqual(await readdir(f.root), [".factory-local-profile"]);

  // These are exclusively test-owned fixture files, not the user's existing profile.
  await writeFile(path.join(f.root, ".factory-local-profile"), "factory.codex-local-production-validation.v1\n");
  await mkdir(f.roots.codexHome);
  await writeFile(path.join(f.roots.codexHome, "config.toml"), "# different fixture config\n", { flag: "wx" });
  await assert.rejects(runLocalWorkerHelper(["prepare", f.root], f.dependencies), /LOCAL_PROFILE_CONFIG_MISMATCH/);
  assert.deepEqual((await readdir(f.root)).sort(), [".factory-local-profile", "codex-home"]);
  assert.equal(await readFile(path.join(f.roots.codexHome, "config.toml"), "utf8"), "# different fixture config\n");
});

test("prepare resumes a marker-owned partial profile without overwriting the marker", async (t) => {
  const f = await fixture(t);
  await mkdir(f.root);
  const marker = path.join(f.root, ".factory-local-profile");
  await writeFile(marker, "factory.codex-local-production-validation.v1\n", { flag: "wx" });
  await mkdir(f.roots.codexHome);
  const before = await lstat(marker);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  assert.equal((await lstat(marker)).mtimeMs, before.mtimeMs);
  for (const directory of Object.values(f.roots)) assert.ok((await lstat(directory)).isDirectory());
});

test("filesystem roots, home ancestors, repositories, and default Codex profiles are rejected", async (t) => {
  const f = await fixture(t);
  for (const root of [path.parse(f.root).root, f.home, f.base, repository,
    path.dirname(repository), path.join(repository, "uncreated-helper-test"),
    f.defaultCodexHome, path.join(f.defaultCodexHome, "child"),
    f.customCodexHome, path.join(f.customCodexHome, "child")]) {
    await assert.rejects(runLocalWorkerHelper(["prepare", root], f.dependencies),
      /LOCAL_ROOT_MUST_BE_DEDICATED_AND_OUTSIDE_REPOSITORY/);
  }
  await assert.rejects(runLocalWorkerHelper(["prepare", "relative-path"], f.dependencies), /USAGE/);
  await assert.rejects(runLocalWorkerHelper(["prepare", f.root, "extra"], f.dependencies), /USAGE/);
  assert.equal(f.output.length, 0);
  await assert.rejects(lstat(f.root), { code: "ENOENT" });
});

test("root links and linked ancestors are rejected without writing through a junction", async (t) => {
  const f = await fixture(t);
  const target = path.join(f.base, "link-target");
  const linkedRoot = path.join(f.base, "linked-root");
  await mkdir(target);
  await writeFile(path.join(target, "canary.txt"), "unchanged", { flag: "wx" });
  await symlink(target, linkedRoot, process.platform === "win32" ? "junction" : "dir");
  for (const supplied of [linkedRoot, path.join(linkedRoot, "nested")]) {
    await assert.rejects(runLocalWorkerHelper(["prepare", supplied], f.dependencies), /LOCAL_ROOT_LINK_REJECTED/);
  }
  assert.deepEqual(await readdir(target), ["canary.txt"]);
  assert.equal(await readFile(path.join(target, "canary.txt"), "utf8"), "unchanged");
});

test("another repository or worktree cannot contain the dedicated local profile", async (t) => {
  const f = await fixture(t);
  const otherRepository = path.join(f.base, "another-repository");
  await mkdir(otherRepository);
  // A .git file is also a worktree marker; do not inspect its potentially private contents.
  await writeFile(path.join(otherRepository, ".git"), "fixture-only worktree marker\n", { flag: "wx" });
  await assert.rejects(runLocalWorkerHelper(["prepare", path.join(otherRepository, "profile")], f.dependencies),
    /LOCAL_ROOT_MUST_BE_DEDICATED_AND_OUTSIDE_REPOSITORY/);
  assert.deepEqual(await readdir(otherRepository), [".git"]);
});

test("linked child roots fail before any authentication", async (t) => {
  const f = await fixture(t);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  const linkedChild = path.join(f.root, "source");
  const target = path.join(f.base, "external-source");
  await mkdir(target);
  await rmdir(linkedChild); // Exact, empty test-owned directory; this is not a recursive operation.
  await symlink(target, linkedChild, process.platform === "win32" ? "junction" : "dir");
  await assert.rejects(runLocalWorkerHelper(["status", f.root], f.dependencies), /LOCAL_ROOT_LINK_REJECTED/);
  assert.deepEqual(await readdir(target), []);
});

test("non-regular marker and oversized config are rejected without reading authentication", async (t) => {
  const f = await fixture(t);
  await mkdir(f.root);
  const marker = path.join(f.root, ".factory-local-profile");
  await mkdir(marker);
  await assert.rejects(runLocalWorkerHelper(["prepare", f.root], f.dependencies), /LOCAL_PROFILE_NOT_PREPARED/);
  await rmdir(marker);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  const oversized = "x".repeat(64 * 1024);
  await writeFile(path.join(f.roots.codexHome, "config.toml"), oversized);
  await assert.rejects(runLocalWorkerHelper(["status", f.root], f.dependencies), /LOCAL_PROFILE_CONFIG_MISMATCH/);
  assert.equal((await lstat(path.join(f.roots.codexHome, "config.toml"))).size, oversized.length);
});

test("login refuses any existing dedicated auth without invoking CLI or auth status", async (t) => {
  const f = await fixture(t);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  const authPath = path.join(f.roots.codexHome, "auth.json");
  await writeFile(authPath, fixtureAuth, { flag: "wx" });
  await assert.rejects(runLocalWorkerHelper(["login", f.root], f.dependencies), /EXISTING_AUTHENTICATION_NOT_MODIFIED/);
  assert.equal(await readFile(authPath, "utf8"), fixtureAuth);
  assert.equal(f.output.length, 1);
});

test("status refuses an auth junction and login never follows or replaces it", async (t) => {
  const f = await fixture(t);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  const target = path.join(f.base, "auth-link-target");
  await mkdir(target);
  await writeFile(path.join(target, "canary.txt"), "no credential in this fixture", { flag: "wx" });
  await symlink(target, path.join(f.roots.codexHome, "auth.json"), process.platform === "win32" ? "junction" : "dir");
  await assert.rejects(runLocalWorkerHelper(["status", f.root], f.dependencies), /LOCAL_AUTHENTICATION_LINK_REJECTED/);
  await assert.rejects(runLocalWorkerHelper(["login", f.root], f.dependencies), /EXISTING_AUTHENTICATION_NOT_MODIFIED/);
  assert.deepEqual(await readdir(target), ["canary.txt"]);
  assert.equal(f.output.length, 1);
});

test("login fake receives only dedicated profile environment and drains private subprocess output", async (t) => {
  const f = await fixture(t);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  const child = new EventEmitter();
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  let spawned = 0;
  let authenticated = 0;
  const dependencies = {
    ...f.dependencies,
    spawnProcess(executable, args, options) {
      spawned++;
      assert.equal(executable, process.execPath);
      assert.deepEqual(args.slice(1), ["-c", 'cli_auth_credentials_store="file"', "login"]);
      assert.equal(args.some((arg) => arg.includes("forced_login_method")), false);
      assert.deepEqual(options.env, {
        PATH: "fixture-path", HOME: f.roots.codexHome, USERPROFILE: f.roots.codexHome,
        CODEX_HOME: f.roots.codexHome
      });
      assert.equal(options.cwd, f.roots.codexHome);
      assert.equal(options.shell, false);
      assert.equal(options.windowsHide, true);
      queueMicrotask(() => {
        child.stdout.write(Buffer.from("fixture private OAuth output"));
        child.stderr.write(Buffer.from("fixture private stderr"));
        child.emit("close", 0);
      });
      return child;
    },
    async checkAuthentication(options) {
      authenticated++;
      assert.equal(options.codexHome, f.roots.codexHome);
      assert.deepEqual(options.environment, { PATH: "fixture-path" });
      return { method: "chatgpt" };
    }
  };
  await runLocalWorkerHelper(["login", f.root], dependencies);
  assert.equal(spawned, 1);
  assert.equal(authenticated, 1);
  assert.ok(f.output.join("").includes('"status":"AUTHENTICATED"'));
  assert.equal(f.output.join("").includes("fixture private"), false);
  for (const directory of [f.defaultCodexHome, f.customCodexHome]) {
    assert.equal(await readFile(path.join(directory, "auth.json"), "utf8"), fixtureAuth);
  }
});

test("status/isolation reject missing preparation and configuration drift before probes", async (t) => {
  const f = await fixture(t);
  await assert.rejects(runLocalWorkerHelper(["status", f.root], f.dependencies), /LOCAL_PROFILE_NOT_PREPARED/);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  await writeFile(path.join(f.roots.codexHome, "config.toml"), "# fixture drift\n");
  for (const mode of ["status", "isolation", "login", "sandbox-setup"]) {
    await assert.rejects(runLocalWorkerHelper([mode, f.root], f.dependencies), /LOCAL_PROFILE_CONFIG_MISMATCH/);
  }
  assert.equal(f.output.length, 1);
  await assert.rejects(lstat(path.join(f.roots.stateRoot, "isolation-workspace")), { code: "ENOENT" });
});

function setupFake(onReadiness = async () => {}) {
  let spawned = 0;
  const messages = [];
  return {
    messages,
    get spawned() { return spawned; },
    spawnProcess(executable, args, options) {
      spawned++;
      assert.deepEqual(args, ["app-server", "--stdio"]);
      assert.equal(path.basename(executable), "codex.exe");
      const child = new EventEmitter();
      child.stdout = new PassThrough();
      child.stderr = new PassThrough();
      child.kill = () => { queueMicrotask(() => child.emit("close", null)); return true; };
      child.stdin = new Writable({
        write(bytes, encoding, callback) {
          const message = JSON.parse(bytes.toString("utf8"));
          messages.push(message);
          callback();
          if (message.method === "initialize") {
            queueMicrotask(() => child.stdout.write(Buffer.from('{"id":1,"result":{}}\n')));
          } else if (message.method === "windowsSandbox/readiness") {
            Promise.resolve(onReadiness(options)).then(() => {
              child.stdout.write(Buffer.from('{"id":2,"result":{"status":"ready"}}\n'));
            }).catch(() => child.emit("error", new Error("test fixture failed")));
          }
        },
        final(callback) { callback(); queueMicrotask(() => child.emit("close", 0)); }
      });
      return child;
    }
  };
}

test("sandbox setup uses dedicated workspace and preserves authentication without auth probes", {
  skip: process.platform !== "win32"
}, async (t) => {
  const f = await fixture(t);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  await writeFile(path.join(f.roots.codexHome, "auth.json"), fixtureAuth, { flag: "wx" });
  const fake = setupFake(async options => {
    assert.equal(options.cwd, path.join(f.roots.stateRoot, "isolation-workspace"));
    assert.deepEqual(options.env, { PATH: "fixture-path", CODEX_HOME: f.roots.codexHome,
      HOME: f.roots.codexHome, USERPROFILE: f.roots.codexHome });
  });
  await runLocalWorkerHelper(["sandbox-setup", f.root], { ...f.dependencies, spawnProcess: fake.spawnProcess });
  assert.equal(fake.spawned, 1);
  assert.deepEqual(fake.messages.map(message => message.method), ["initialize", "initialized", "windowsSandbox/readiness"]);
  assert.equal(JSON.parse(f.output.at(-1)).status, "WINDOWS_SANDBOX_READY");
  for (const directory of [f.roots.codexHome, f.defaultCodexHome, f.customCodexHome]) {
    assert.equal(await readFile(path.join(directory, "auth.json"), "utf8"), fixtureAuth);
  }
  assert.equal(await readFile(path.join(f.roots.codexHome, "config.toml"), "utf8"), expectedPermissionProfileConfig(f.roots));
});

test("sandbox setup rejects auth junction before spawning or creating workspace", async (t) => {
  const f = await fixture(t);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  const target = path.join(f.base, "auth-setup-link-target");
  await mkdir(target);
  await symlink(target, path.join(f.roots.codexHome, "auth.json"), process.platform === "win32" ? "junction" : "dir");
  await assert.rejects(runLocalWorkerHelper(["sandbox-setup", f.root], f.dependencies), /LOCAL_AUTHENTICATION_LINK_REJECTED/);
  await assert.rejects(lstat(path.join(f.roots.stateRoot, "isolation-workspace")), { code: "ENOENT" });
  assert.deepEqual(await readdir(target), []);
});

test("sandbox setup never repairs config drift or emits a false successful setup status", {
  skip: process.platform !== "win32"
}, async (t) => {
  const f = await fixture(t);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  const configPath = path.join(f.roots.codexHome, "config.toml");
  const fake = setupFake(async () => { await writeFile(configPath, "# fixture setup changed config\n"); });
  await assert.rejects(runLocalWorkerHelper(["sandbox-setup", f.root], {
    ...f.dependencies, spawnProcess: fake.spawnProcess
  }), /LOCAL_PROFILE_CONFIG_MISMATCH/);
  assert.equal(fake.spawned, 1);
  assert.equal(f.output.length, 1);
  assert.equal(await readFile(configPath, "utf8"), "# fixture setup changed config\n");
});

test("sandbox setup refuses linked operation workspace before spawning", async (t) => {
  const f = await fixture(t);
  await runLocalWorkerHelper(["prepare", f.root], f.dependencies);
  const target = path.join(f.base, "linked-setup-workspace");
  await mkdir(target);
  await symlink(target, path.join(f.roots.stateRoot, "isolation-workspace"), process.platform === "win32" ? "junction" : "dir");
  await assert.rejects(runLocalWorkerHelper(["sandbox-setup", f.root], f.dependencies), /LOCAL_ROOT_LINK_REJECTED/);
  assert.deepEqual(await readdir(target), []);
});
