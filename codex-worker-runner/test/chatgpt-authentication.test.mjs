import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { lstat, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, test } from "node:test";
import { runLoginStatus, verifyChatGptAuthentication } from "../src/chatgpt-authentication.mjs";
import { ProtocolError } from "../src/protocol.mjs";

const roots = [];
afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

test("ChatGPT preflight uses only login status and excludes ambient keys and personal homes", async () => {
  const codexHome = await fixtureHome();
  for (const stream of ["stdout", "stderr"]) {
    const reply = { exitCode: 0, stdout: Buffer.alloc(0), stderr: Buffer.alloc(0) };
    reply[stream] = Buffer.from("Logged in using ChatGPT\r\n");
    let invocation;
    const proof = await verifyChatGptAuthentication({
      codexHome,
      environment: {
        PATH: "trusted-bin", HOME: "personal-home", CODEX_HOME: "personal-codex",
        TMP: "personal-temp", TEMP: "personal-temp", TMPDIR: "personal-temp",
        OPENAI_API_KEY: "must-not-be-forwarded", CODEX_API_KEY: "must-not-be-forwarded",
        CODEX_ACCESS_TOKEN: "must-not-be-forwarded", FACTORY_WORKER_ATTEMPT_TOKEN: "not-for-codex"
      },
      async runCommand(options) {
        invocation = options;
        assert.equal((await lstat(options.env.TEMP)).isDirectory(), true);
        return reply;
      }
    });
    assert.deepEqual(proof, { method: "chatgpt" });
    assert.deepEqual(invocation.args.slice(1), ["login", "status"]);
    assert.doesNotMatch(invocation.args.join(" "), /forced_login_method|logout|with-api-key/u);
    assert.deepEqual(invocation.env, {
      HOME: invocation.cwd, USERPROFILE: invocation.cwd, CODEX_HOME: invocation.cwd, PATH: "trusted-bin",
      TMP: invocation.env.TEMP, TEMP: invocation.env.TEMP, TMPDIR: invocation.env.TEMP
    });
    assert.equal(path.dirname(invocation.env.TEMP), invocation.cwd);
    assert.match(path.basename(invocation.env.TEMP), /^\.factory-auth-status-[A-Za-z0-9]+$/u);
    await assert.rejects(lstat(invocation.env.TEMP), { code: "ENOENT" });
    assert.equal(reply[stream].every((byte) => byte === 0), true);
  }
});

test("failed authentication also removes only its own empty temporary directory", async () => {
  const codexHome = await fixtureHome();
  let temporary;
  await assert.rejects(verifyChatGptAuthentication({ codexHome,
    async runCommand(options) {
      temporary = options.env.TEMP;
      throw new Error("private-subprocess-value");
    }
  }), sanitizedLoginRequired);
  await assert.rejects(lstat(temporary), { code: "ENOENT" });
  assert.equal((await lstat(codexHome)).isDirectory(), true);
});

test("authentication cleanup preserves unexpected temporary contents without recursive deletion", async () => {
  const codexHome = await fixtureHome();
  let marker;
  const proof = await verifyChatGptAuthentication({ codexHome,
    async runCommand(options) {
      marker = path.join(options.env.TEMP, "synthetic-marker");
      await writeFile(marker, "preserve-this-fixture");
      return { exitCode: 0, stdout: Buffer.alloc(0), stderr: Buffer.from("Logged in using ChatGPT\n") };
    }
  });
  assert.deepEqual(proof, { method: "chatgpt" });
  assert.equal(await readFile(marker, "utf8"), "preserve-this-fixture");
  assert.equal((await lstat(codexHome)).isDirectory(), true);
});

test("API login, no login, failed and ambiguous status are rejected without exposing output", async () => {
  const codexHome = await fixtureHome();
  for (const [exitCode, stdout, stderr] of [
    [0, "", "Logged in using an API key - private-key-suffix"],
    [1, "", "Not logged in"],
    [1, "Logged in using ChatGPT", ""],
    [0, "Logged in using ChatGPT", "private-account-warning"],
    [0, "prefix Logged in using ChatGPT", ""],
    [0, "", ""],
    [0, "x".repeat(4097), ""]
  ]) {
    const reply = { exitCode, stdout: Buffer.from(stdout), stderr: Buffer.from(stderr) };
    await assert.rejects(verifyChatGptAuthentication({ codexHome, async runCommand() { return reply; } }),
      sanitizedLoginRequired);
    assert.equal(reply.stdout.every((byte) => byte === 0), true);
    assert.equal(reply.stderr.every((byte) => byte === 0), true);
  }
});

test("preflight rejects malformed results and subprocess exceptions without retaining their cause", async () => {
  const codexHome = await fixtureHome();
  for (const reply of [undefined, { exitCode: 0 }, { exitCode: 0, stdout: "private-value", stderr: "" }]) {
    await assert.rejects(verifyChatGptAuthentication({ codexHome, async runCommand() { return reply; } }),
      sanitizedLoginRequired);
  }
  await assert.rejects(verifyChatGptAuthentication({ codexHome,
    async runCommand() { throw new Error("private-subprocess-value"); }
  }), sanitizedLoginRequired);
});

test("warnings and mixed status cannot turn a ChatGPT substring into authentication proof", async () => {
  const codexHome = await fixtureHome();
  const success = "Logged in using ChatGPT\n";
  for (const [stdout, stderr] of [
    ["", "WARNING: private-diagnostic\n" + success],
    ["", success + "WARNING: private-diagnostic\n"],
    ["", "WARNING: private-diagnostic\nLogged in using an API key - private-suffix\n"],
    ["", success + "Logged in using an API key - private-suffix\n"],
    ["", success + success],
    [success, success],
    ["", "\u001b[0m" + success],
    ["", success + "\u0000"]
  ]) {
    const reply = { exitCode: 0, stdout: Buffer.from(stdout), stderr: Buffer.from(stderr) };
    await assert.rejects(verifyChatGptAuthentication({ codexHome, async runCommand() { return reply; } }),
      sanitizedLoginRequired);
    assert.equal(reply.stdout.every((byte) => byte === 0), true);
    assert.equal(reply.stderr.every((byte) => byte === 0), true);
  }
});

test("missing or relative dedicated profile is rejected without launching any command", async () => {
  const codexHome = await fixtureHome();
  let launches = 0;
  for (const value of [undefined, "relative-home", path.join(codexHome, "not-created")]) {
    await assert.rejects(verifyChatGptAuthentication({ codexHome: value,
      async runCommand() { launches += 1; }
    }), sanitizedLoginRequired);
  }
  assert.equal(launches, 0);
});

test("status subprocess keeps private pipes, no shell or visible window and returns bounded bytes", async () => {
  const child = fakeChild();
  let options;
  const pending = runLoginStatus({ executable: "configured-codex", args: ["login", "status"],
    env: { CODEX_HOME: "dedicated-home" }, cwd: "dedicated-home",
    spawnProcess(executable, args, value) {
      assert.equal(executable, "configured-codex");
      assert.deepEqual(args, ["login", "status"]);
      options = value;
      return child;
    }
  });
  child.stderr.emit("data", Buffer.from("Logged in using ChatGPT\n"));
  child.emit("close", 0, null);
  const reply = await pending;
  assert.equal(options.shell, false);
  assert.equal(options.windowsHide, true);
  assert.deepEqual(options.stdio, ["ignore", "pipe", "pipe"]);
  assert.equal(reply.stderr.toString(), "Logged in using ChatGPT\n");
  reply.stderr.fill(0);
});

test("over-quota output, spawn error and cancellation all fail closed with sanitized errors", async () => {
  for (const failure of ["quota", "error", "abort", "kill-error"]) {
    const child = fakeChild();
    if (failure === "kill-error") {
      child.kill = () => { child.killed = true; throw new Error("private-kill-error"); };
    }
    const controller = new AbortController();
    const pending = runLoginStatus({ executable: "configured-codex", args: ["login", "status"],
      env: {}, cwd: "dedicated-home", signal: controller.signal, spawnProcess() { return child; }
    });
    const rejected = assert.rejects(pending, sanitizedLoginRequired);
    if (failure === "quota") child.stdout.emit("data", Buffer.alloc(4097));
    if (failure === "error") child.emit("error", new Error("private-process-error"));
    if (failure === "abort" || failure === "kill-error") controller.abort();
    await rejected;
    assert.equal(child.killed, failure !== "error");
  }
  await assert.rejects(runLoginStatus({ spawnProcess() { throw new Error("private-spawn-error"); } }),
    sanitizedLoginRequired);
});

test("status subprocess timeout is bounded without scheduler sleeps", async (context) => {
  context.mock.timers.enable({ apis: ["setTimeout"] });
  const child = fakeChild();
  const rejected = assert.rejects(runLoginStatus({ executable: "configured-codex", args: ["login", "status"],
    env: {}, cwd: "dedicated-home", spawnProcess() { return child; }
  }), sanitizedLoginRequired);
  context.mock.timers.tick(15_000);
  await rejected;
  assert.equal(child.killed, true);
});

function fakeChild() {
  const child = new EventEmitter();
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  child.killed = false;
  child.kill = () => { child.killed = true; return true; };
  return child;
}

async function fixtureHome() {
  const root = await mkdtemp(path.join(os.tmpdir(), "factory-chatgpt-auth-test-"));
  roots.push(root);
  return root;
}

function sanitizedLoginRequired(error) {
  assert.ok(error instanceof ProtocolError);
  assert.equal(error.code, "WORKER_CHATGPT_LOGIN_REQUIRED");
  assert.equal(error.message, "verified ChatGPT login in the dedicated Codex profile is required");
  assert.equal(error.cause, undefined);
  return true;
}
