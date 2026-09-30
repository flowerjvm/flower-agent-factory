import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { PassThrough, Writable } from "node:stream";
import path from "node:path";
import os from "node:os";
import { statSync } from "node:fs";
import { setupWindowsSandbox } from "../codex-windows-sandbox-setup.mjs";

const options = { skip: process.platform !== "win32" };
const codexHome = path.join(os.tmpdir(), "not-created-sandbox-test", "codex-home");
const workspaceRoot = path.join(os.tmpdir(), "not-created-sandbox-test", "workspace");

function fakeSandboxProcess(onMessage, onSpawn = () => {}) {
  const messages = [];
  let count = 0;
  let kills = 0;
  let child;
  let closed = false;
  const spawnProcess = (executable, args, config) => {
    count++;
    assert.deepEqual(args, ["app-server", "--stdio"]);
    assert.equal(path.basename(executable), "codex.exe");
    assert.equal(path.basename(path.dirname(executable)), "bin");
    assert.equal(statSync(executable).isFile(), true, "the pinned package must provide this native executable");
    assert.equal(config.shell, false);
    assert.equal(config.windowsHide, true);
    assert.deepEqual(config.stdio, ["pipe", "pipe", "pipe"]);
    onSpawn(config);
    child = new EventEmitter();
    child.stdout = new PassThrough();
    child.stderr = new PassThrough();
    child.kill = () => { kills++; queueMicrotask(() => close(null)); return true; };
    child.stdin = new Writable({
      write(bytes, encoding, callback) {
        const message = JSON.parse(bytes.toString("utf8"));
        messages.push(message);
        callback();
        queueMicrotask(() => { if (!closed) onMessage(message, child, reply, close); });
      },
      final(callback) { callback(); queueMicrotask(() => close(0)); }
    });
    return child;
  };
  function reply(value) { child.stdout.write(Buffer.from(JSON.stringify(value) + "\n")); }
  function close(code) { if (!closed) { closed = true; child.emit("close", code); } }
  return { spawnProcess, messages, get count() { return count; }, get kills() { return kills; } };
}

function scripted({ readiness = "notConfigured", finish, onSpawn } = {}) {
  return fakeSandboxProcess((message, child, reply, close) => {
    if (message.method === "initialize") reply({ id: 1, result: { private: "not-for-output" } });
    if (message.method === "windowsSandbox/readiness") reply({ id: 2, result: { status: readiness } });
    if (message.method === "windowsSandbox/setupStart") {
      if (finish) finish(child, reply, close);
      else {
        reply({ id: 3, result: { started: true } });
        reply({ method: "windowsSandbox/setupCompleted", params: { mode: "elevated", success: true, error: null } });
      }
    }
  }, onSpawn);
}

test("ready probe does not start setup, authenticate, or invoke model/thread APIs", options, async () => {
  const fake = scripted({ readiness: "ready", onSpawn(config) {
    assert.equal(config.cwd, workspaceRoot);
    assert.deepEqual(config.env, { PATH: "fake-path", HOME: codexHome, USERPROFILE: codexHome, CODEX_HOME: codexHome });
  } });
  assert.deepEqual(await setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: fake.spawnProcess,
    environment: { PATH: "fake-path", OPENAI_API_KEY: "fixture-only", CODEX_HOME: "wrong", NODE_OPTIONS: "wrong" } }),
  { status: "WINDOWS_SANDBOX_READY", setupStarted: false });
  assert.deepEqual(fake.messages.map(m => m.method), ["initialize", "initialized", "windowsSandbox/readiness"]);
  assert.equal(fake.messages[0].params.capabilities.experimentalApi, true);
  assert.equal(fake.messages[2].params, null);
  assert.equal(fake.count, 1);
});

test("elevated setup awaits completion with exact operation cwd and discards private stderr", options, async () => {
  const privateBytes = Buffer.from("private fixture stderr");
  const fake = scripted({ finish(child, reply) {
    child.stderr.write(privateBytes);
    reply({ id: 3, result: { started: true } });
    reply({ method: "windowsSandbox/setupCompleted", params: { mode: "elevated", success: true } });
  } });
  assert.deepEqual(await setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: fake.spawnProcess }),
    { status: "WINDOWS_SANDBOX_SETUP_COMPLETED", setupStarted: true });
  assert.deepEqual(fake.messages[3], { id: 3, method: "windowsSandbox/setupStart", params: { mode: "elevated", cwd: workspaceRoot } });
  assert.equal(privateBytes.every(byte => byte === 0), true);
});

test("completion arriving before setup acknowledgment is retained until started true", options, async () => {
  const fake = scripted({ readiness: "updateRequired", finish(child, reply) {
    reply({ method: "windowsSandbox/setupCompleted", params: { mode: "elevated", success: true } });
    reply({ id: 3, result: { started: true } });
  } });
  assert.equal((await setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: fake.spawnProcess })).setupStarted, true);
});

test("explicit setup failure is sanitized and not retried", options, async () => {
  const fake = scripted({ finish(child, reply) {
    reply({ id: 3, result: { started: true } });
    reply({ method: "windowsSandbox/setupCompleted", params: { mode: "elevated", success: false, error: "private path fixture" } });
  } });
  await assert.rejects(setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: fake.spawnProcess }),
    { message: "WINDOWS_SANDBOX_SETUP_FAILED" });
  assert.equal(fake.count, 1);
  assert.equal(fake.kills, 1);
});

test("setup timeout is uncertain, closes process, and never automatically retries", options, async () => {
  const fake = scripted({ finish(child, reply) { reply({ id: 3, result: { started: true } }); } });
  await assert.rejects(setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: fake.spawnProcess, timeoutMs: 15 }),
    { message: "WINDOWS_SANDBOX_SETUP_UNCERTAIN" });
  assert.equal(fake.count, 1);
  assert.equal(fake.kills, 1);
});

test("malformed, unknown response, server request, and over-limit streams fail closed", options, async () => {
  for (const fail of [
    (child) => child.stdout.write(Buffer.from("private malformed input\n")),
    (child, reply) => reply({ id: 999, result: {} }),
    (child, reply) => reply({ id: 999, method: "account/login/start", params: {} }),
    (child) => child.stdout.write(Buffer.alloc(1024 * 1024 + 1, 65)),
    (child) => child.stderr.write(Buffer.alloc(1024 * 1024 + 1, 65)),
    (child) => child.stdin.emit("error", new Error("private broken pipe")),
    (child, reply, close) => close(1)
  ]) {
    const fake = scripted({ finish: fail });
    await assert.rejects(setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: fake.spawnProcess }),
      { message: "WINDOWS_SANDBOX_SETUP_UNCERTAIN" });
    assert.equal(fake.count, 1);
  }
});

test("false acknowledgment or malformed completion cannot claim success", options, async () => {
  for (const finish of [
    (child, reply) => reply({ id: 3, result: { started: false } }),
    (child, reply) => {
      reply({ id: 3, result: { started: true } });
      reply({ method: "windowsSandbox/setupCompleted", params: { mode: "unelevated", success: true } });
    },
    (child, reply) => {
      reply({ id: 3, result: { started: true } });
      reply({ method: "windowsSandbox/setupCompleted", params: { mode: "elevated", success: true, error: "private" } });
    }
  ]) {
    const fake = scripted({ finish });
    await assert.rejects(setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: fake.spawnProcess }),
      { message: "WINDOWS_SANDBOX_SETUP_UNCERTAIN" });
  }
});

test("split UTF-8 JSON frames and multiple notifications are consumed without disclosure", options, async () => {
  const fake = scripted({ finish(child, reply) {
    const bytes = Buffer.from(JSON.stringify({ method: "warning", params: { message: "private 한국어" } }) + "\n"
      + JSON.stringify({ id: 3, result: { started: true } }) + "\n");
    for (const byte of bytes) child.stdout.write(Buffer.from([byte]));
    reply({ method: "windowsSandbox/setupCompleted", params: { mode: "elevated", success: true } });
  } });
  assert.equal((await setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: fake.spawnProcess })).status,
    "WINDOWS_SANDBOX_SETUP_COMPLETED");
});

test("spawn failures never disclose raw exception details", options, async () => {
  await assert.rejects(setupWindowsSandbox({ codexHome, workspaceRoot,
    spawnProcess() { throw new Error("private filesystem path"); } }), { message: "WINDOWS_SANDBOX_SETUP_FAILED" });
});

test("pre-setup process failure and invalid readiness never start setup", options, async () => {
  for (const action of [
    (message, child) => child.emit("error", new Error("private failed spawn")),
    (message, child, reply, close) => close(1),
    (message, child, reply) => reply({ id: 1, error: { message: "private initialization failure" } }),
    (message, child, reply) => reply({ method: "windowsSandbox/setupCompleted", params: { mode: "elevated", success: true } })
  ]) {
    const fake = fakeSandboxProcess(action);
    await assert.rejects(setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: fake.spawnProcess }),
      { message: "WINDOWS_SANDBOX_SETUP_FAILED" });
    assert.deepEqual(fake.messages.map(message => message.method), ["initialize"]);
    assert.equal(fake.count, 1);
  }
  const invalidReadiness = scripted({ readiness: "unknown" });
  await assert.rejects(setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: invalidReadiness.spawnProcess }),
    { message: "WINDOWS_SANDBOX_SETUP_FAILED" });
  assert.equal(invalidReadiness.messages.some(message => message.method === "windowsSandbox/setupStart"), false);
});

test("success notification with nonzero shutdown is uncertain instead of a successful result", options, async () => {
  const fake = scripted({ finish(child, reply, close) {
    reply({ id: 3, result: { started: true } });
    reply({ method: "windowsSandbox/setupCompleted", params: { mode: "elevated", success: true } });
    close(1);
  } });
  await assert.rejects(setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: fake.spawnProcess }),
    { message: "WINDOWS_SANDBOX_SETUP_UNCERTAIN" });
});

test("a completion without setup acknowledgment remains uncertain after timeout", options, async () => {
  const fake = scripted({ finish(child, reply) {
    reply({ method: "windowsSandbox/setupCompleted", params: { mode: "elevated", success: true } });
  } });
  await assert.rejects(setupWindowsSandbox({ codexHome, workspaceRoot, spawnProcess: fake.spawnProcess, timeoutMs: 15 }),
    { message: "WINDOWS_SANDBOX_SETUP_UNCERTAIN" });
  assert.equal(fake.count, 1);
});
