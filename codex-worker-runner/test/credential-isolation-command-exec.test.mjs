import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import os from "node:os";
import path from "node:path";
import { PassThrough, Writable } from "node:stream";
import test from "node:test";
import { runInNewContext } from "node:vm";
import { runCodexSentinelCommand } from "../src/credential-isolation-command-exec.mjs";
import { ProtocolError } from "../src/protocol.mjs";

// No directories or credentials are created or read; every process below is an in-memory fake.
const codexHome = path.join(os.tmpdir(), "not-created-command-exec-test", "codex-home");
const workspaceRoot = path.join(os.tmpdir(), "not-created-command-exec-test", "workspace");
const target = path.join(workspaceRoot, `.factory-read-control-${"a".repeat(32)}`);
const base = { codexHome, workspaceRoot, target };

function fakeProcess(onMessage, { onSpawn = () => {}, closeOnEnd = true, closeOnKill = true } = {}) {
  const messages = [];
  let count = 0;
  let kills = 0;
  let unrefs = 0;
  let child;
  let closed = false;
  const spawnProcess = (executable, args, config) => {
    count++;
    assert.equal(path.isAbsolute(executable), true);
    assert.equal(path.basename(executable), process.platform === "win32" ? "codex.exe" : "codex");
    assert.equal(path.basename(path.dirname(executable)), "bin");
    assert.deepEqual(args, ["app-server", "--stdio"]);
    assert.equal(config.cwd, workspaceRoot);
    assert.equal(config.shell, false);
    assert.equal(config.windowsHide, true);
    assert.deepEqual(config.stdio, ["pipe", "pipe", "pipe"]);
    onSpawn(config);
    child = new EventEmitter();
    child.stdout = new PassThrough();
    child.stderr = new PassThrough();
    child.kill = (signal) => {
      assert.equal(signal, "SIGKILL");
      kills++;
      if (closeOnKill) queueMicrotask(() => close(null, signal));
      return true;
    };
    child.unref = () => { unrefs++; };
    child.stdin = new Writable({
      write(bytes, encoding, callback) {
        const message = JSON.parse(bytes.toString("utf8"));
        messages.push(message);
        callback();
        queueMicrotask(() => { if (!closed) onMessage(message, child, reply, close); });
      },
      final(callback) {
        callback();
        if (closeOnEnd) queueMicrotask(() => close(0));
      }
    });
    return child;
  };
  function reply(value) { child.stdout.write(Buffer.from(JSON.stringify(value) + "\n")); }
  function close(code, signal = null) {
    if (!closed) { closed = true; child.emit("close", code, signal); }
  }
  return { spawnProcess, messages, get count() { return count; }, get kills() { return kills; },
    get unrefs() { return unrefs; }, get child() { return child; } };
}

function scripted(finish = (_child, reply) => reply({ id: 2, result: { exitCode: 0, stdout: "synthetic marker", stderr: "" } }), options) {
  return fakeProcess((message, child, reply, close) => {
    if (message.method === "initialize") reply({ id: 1, result: { private: "never disclosed" } });
    if (message.method === "command/exec") finish(child, reply, close);
  }, options);
}

test("sentinel uses only initialized experimental command/exec with the exact named profile and buffered argv", async () => {
  const fake = scripted(undefined, { onSpawn(config) {
    assert.deepEqual(config.env, {
      PATH: "fixture-path", LANG: "C", HOME: codexHome, USERPROFILE: codexHome, CODEX_HOME: codexHome
    });
  } });
  assert.deepEqual(await runCodexSentinelCommand({ ...base, spawnProcess: fake.spawnProcess, environment: {
    PATH: "fixture-path", LANG: "C", HOME: "personal", USERPROFILE: "personal", CODEX_HOME: "personal",
    OPENAI_API_KEY: "fixture-key", CODEX_API_KEY: "fixture-key", openai_api_key: "fixture-key",
    CODEX_ACCESS_TOKEN: "fixture-token", NODE_OPTIONS: "fixture-options", TMP: "personal", TEMP: "personal"
  } }), { exitCode: 0, stdout: Buffer.from("synthetic marker") });
  assert.deepEqual(fake.messages.map(message => message.method), ["initialize", "initialized", "command/exec"]);
  assert.deepEqual(fake.messages[0].params.capabilities, { experimentalApi: true });
  assert.deepEqual(fake.messages[2], { method: "command/exec", id: 2, params: {
    command: [process.execPath, "-e",
      "const fs=require('node:fs');try{process.stdout.write(fs.readFileSync(process.argv[1],'utf8'))}"
      + "catch(error){process.exitCode=error?.code==='EACCES'||error?.code==='EPERM'?77:74}", target],
    permissionProfile: "factory-worker", cwd: workspaceRoot, timeoutMs: 10_000
  } });
  assert.equal(fake.count, 1);
  assert.equal(fake.kills, 0);
});

test("command denial returns only bounded stdout and its exit code, discarding private stderr", async () => {
  const bytes = Buffer.from("private process stderr fixture");
  const fake = scripted((child, reply) => {
    child.stderr.write(bytes);
    reply({ id: 2, result: { exitCode: 77, stdout: "", stderr: "private denial diagnostic fixture" } });
  });
  assert.deepEqual(await runCodexSentinelCommand({ ...base, spawnProcess: fake.spawnProcess }),
    { exitCode: 77, stdout: Buffer.alloc(0) });
  assert.equal(bytes.every(byte => byte === 0), true);
});

test("the fixed reader distinguishes EACCES and EPERM from ENOENT, other errors and success without real file access", async () => {
  const fake = scripted();
  await runCodexSentinelCommand({ ...base, spawnProcess: fake.spawnProcess });
  const script = fake.messages[2].params.command[2];
  for (const [code, expectedExit, expectedOutput] of [
    [null, 0, "synthetic marker"], ["EACCES", 77, ""], ["EPERM", 77, ""],
    ["ENOENT", 74, ""], ["EIO", 74, ""], ["ENOTDIR", 74, ""]
  ]) {
    let output = "";
    let reads = 0;
    const readerProcess = { argv: [process.execPath, target], exitCode: 0,
      stdout: { write(value) { output += value; } } };
    runInNewContext(script, { process: readerProcess, require(module) {
      assert.equal(module, "node:fs");
      return { readFileSync(file, encoding) {
        reads++;
        assert.equal(file, target);
        assert.equal(encoding, "utf8");
        if (code) throw Object.assign(new Error("private fake read error"), { code });
        return "synthetic marker";
      } };
    } }, { timeout: 1000 });
    assert.equal(reads, 1);
    assert.equal(readerProcess.exitCode, expectedExit);
    assert.equal(output, expectedOutput);
  }
});

test("split UTF-8 frames and unrelated notifications remain private and never trigger extra APIs", async () => {
  const fake = scripted((child) => {
    const bytes = Buffer.from(JSON.stringify({ method: "warning", params: { message: "private 한국어" } }) + "\n"
      + JSON.stringify({ id: 2, result: { exitCode: 0, stdout: "synthetic marker", stderr: "" } }) + "\n");
    for (const byte of bytes) child.stdout.write(Buffer.from([byte]));
  });
  assert.equal((await runCodexSentinelCommand({ ...base, spawnProcess: fake.spawnProcess })).exitCode, 0);
  assert.equal(fake.messages.length, 3);
});

test("malformed protocol, wrong response IDs, server requests and process errors fail closed without disclosure", async () => {
  for (const finish of [
    (child) => child.stdout.write(Buffer.from("private malformed frame\n")),
    (child) => child.stdout.write(Buffer.from([0xff, 10])),
    (_child, reply) => reply(null),
    (_child, reply) => reply([]),
    (_child, reply) => reply({ id: 999, result: {} }),
    (_child, reply) => reply({ id: 1, result: {} }),
    (_child, reply) => reply({ id: 2, error: { message: "private server failure fixture" } }),
    (_child, reply) => reply({ id: 99, method: "item/commandExecution/requestApproval", params: {} }),
    (_child, reply) => reply({ id: 99, method: "account/login/start", params: {} }),
    (child) => child.stdin.emit("error", new Error("private broken pipe fixture")),
    (child) => child.stderr.emit("error", new Error("private stderr failure fixture")),
    (child) => child.emit("error", new Error("private process failure fixture"))
  ]) {
    const fake = scripted(finish);
    await assert.rejects(runCodexSentinelCommand({ ...base, spawnProcess: fake.spawnProcess }), unproven);
    assert.equal(fake.count, 1);
    assert.equal(fake.messages.length, 3);
    assert.ok(fake.kills >= 1);
  }
});

test("invalid command results and byte-based command output caps cannot become evidence", async () => {
  for (const result of [
    {}, { exitCode: 0, stdout: "" }, { exitCode: 0, stdout: [], stderr: "" },
    { exitCode: 0.5, stdout: "", stderr: "" }, { exitCode: 2147483648, stdout: "", stderr: "" },
    { exitCode: -2147483649, stdout: "", stderr: "" },
    { exitCode: 0, stdout: "a".repeat(4097), stderr: "" },
    { exitCode: 0, stdout: "한".repeat(1366), stderr: "" },
    { exitCode: 1, stdout: "", stderr: "a".repeat(4097) }
  ]) {
    const fake = scripted((_child, reply) => reply({ id: 2, result }));
    await assert.rejects(runCodexSentinelCommand({ ...base, spawnProcess: fake.spawnProcess }), unproven);
  }
});

test("bounded total protocol streams reject oversized stdout and stderr and wipe received buffers", async () => {
  for (const stream of ["stdout", "stderr"]) {
    const bytes = Buffer.alloc(64 * 1024 + 1, 65);
    const fake = scripted((child) => child[stream].write(bytes));
    await assert.rejects(runCodexSentinelCommand({ ...base, spawnProcess: fake.spawnProcess }), unproven);
    assert.equal(bytes.every(byte => byte === 0), true);
    assert.equal(fake.kills, 1);
  }
});

test("timeouts before initialization or after command dispatch kill only the owned child and never retry", async () => {
  for (const fake of [fakeProcess(() => {}), scripted(() => {})]) {
    await assert.rejects(runCodexSentinelCommand({ ...base, spawnProcess: fake.spawnProcess, timeoutMs: 15 }), unproven);
    assert.equal(fake.count, 1);
    assert.equal(fake.kills, 1);
    assert.ok(fake.messages.every(message => ["initialize", "initialized", "command/exec"].includes(message.method)));
  }
});

test("initialization errors and premature close cannot dispatch a sentinel command", async () => {
  for (const action of [
    (_message, _child, reply) => reply({ id: 1, error: { message: "private initialization error fixture" } }),
    (_message, _child, reply) => reply({ id: 2, result: { exitCode: 0, stdout: "", stderr: "" } }),
    (_message, _child, _reply, close) => close(0),
    (_message, _child, _reply, close) => close(1),
    (_message, child) => child.emit("error", new Error("private spawn fixture"))
  ]) {
    const fake = fakeProcess(action);
    await assert.rejects(runCodexSentinelCommand({ ...base, spawnProcess: fake.spawnProcess }), unproven);
    assert.deepEqual(fake.messages.map(message => message.method), ["initialize"]);
  }
});

test("a command result needs clean process shutdown; failed, signaled or errored shutdown stays unproven", async () => {
  for (const finish of [
    (_child, reply, close) => { reply({ id: 2, result: { exitCode: 0, stdout: "marker", stderr: "" } }); close(1); },
    (_child, reply, close) => { reply({ id: 2, result: { exitCode: 0, stdout: "marker", stderr: "" } }); close(0, "SIGTERM"); },
    (child, reply) => {
      reply({ id: 2, result: { exitCode: 0, stdout: "marker", stderr: "" } });
      child.stdout.emit("error", new Error("private late stream error fixture"));
    }
  ]) {
    const fake = scripted(finish);
    await assert.rejects(runCodexSentinelCommand({ ...base, spawnProcess: fake.spawnProcess }), unproven);
  }
});

test("a child that never closes is bounded, detached after cleanup and cannot produce a successful proof", async () => {
  const fake = scripted(undefined, { closeOnEnd: false, closeOnKill: false });
  await assert.rejects(runCodexSentinelCommand({ ...base, spawnProcess: fake.spawnProcess }), unproven);
  assert.equal(fake.kills, 1);
  assert.equal(fake.unrefs, 1);
  assert.equal(fake.child.stdin.destroyed, true);
  assert.equal(fake.child.stdout.destroyed, true);
  assert.equal(fake.child.stderr.destroyed, true);
});

test("spawn failures are sanitized and arbitrary targets, wrappers and invalid deadlines are rejected before spawn", async () => {
  await assert.rejects(runCodexSentinelCommand({ ...base,
    spawnProcess() { throw new Error("private native spawn exception fixture"); } }), unproven);
  for (const overrides of [
    { codexHome: "relative" }, { workspaceRoot: "relative" }, { target: "relative" },
    { target: path.join(codexHome, "auth.json") }, { target: path.join(codexHome, ".env") },
    { target: path.join(codexHome, ".factory-read-denied-arbitrary") },
    { target: target + "\n" }, { codexPath: "codex" },
    { codexPath: path.join(codexHome, "codex.js") }, { codexPath: path.join(codexHome, "codex.cmd") },
    { timeoutMs: 0 }, { timeoutMs: 15001 }, { timeoutMs: 1.5 }
  ]) {
    let calls = 0;
    await assert.rejects(runCodexSentinelCommand({ ...base, ...overrides, spawnProcess() { calls++; } }), unproven);
    assert.equal(calls, 0);
  }
});

function unproven(error) {
  assert.equal(error instanceof ProtocolError, true);
  assert.equal(error.code, "WORKER_CREDENTIAL_ISOLATION_UNPROVEN");
  assert.equal(error.message, "credential and host filesystem read isolation is unproven");
  assert.equal(error.cause, undefined);
  return true;
}
