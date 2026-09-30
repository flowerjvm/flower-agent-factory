import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import path from "node:path";
import { ProtocolError } from "./protocol.mjs";

const requireCodex = createRequire(import.meta.url);
const MAX_PROTOCOL_BYTES = 64 * 1024;
const MAX_COMMAND_OUTPUT_BYTES = 4 * 1024;
const PROBE_TIMEOUT_MS = 15_000;
const COMMAND_TIMEOUT_MS = 10_000;
const STOP_TIMEOUT_MS = 2_000;
export const SENTINEL_READ_DENIED_EXIT_CODE = 77;
const READ_SCRIPT =
  "const fs=require('node:fs');try{process.stdout.write(fs.readFileSync(process.argv[1],'utf8'))}"
  + `catch(error){process.exitCode=error?.code==='EACCES'||error?.code==='EPERM'?${SENTINEL_READ_DENIED_EXIT_CODE}:74}`;

/**
 * Only reads the synthetic sentinel created by verifyCredentialIsolation; never starts a model,
 * thread, login, setup, or unsandboxed process API. Codex 0.148.0's Windows `sandbox` diagnostic
 * passes empty deny-read overrides. command/exec instead derives them from effective permissions
 * through the same build_exec_request/execute_env path as ordinary sandboxed execution.
 */
export async function runCodexSentinelCommand({
  codexHome, workspaceRoot, codexPath, environment = {}, target,
  spawnProcess = spawn, timeoutMs = PROBE_TIMEOUT_MS
}) {
  let executable;
  try {
    for (const value of [codexHome, workspaceRoot, target]) {
      if (typeof value !== "string" || !path.isAbsolute(value) || /[\u0000-\u001f\u007f]/u.test(value)) {
        throw unproven();
      }
    }
    if (!/^\.factory-read-(?:control|denied)-[0-9a-f]{32}$/u.test(path.basename(target))
        || !Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > PROBE_TIMEOUT_MS) {
      throw unproven();
    }
    executable = codexPath ?? nativeCodexExecutable();
    if (typeof executable !== "string" || !path.isAbsolute(executable)
        || /[\u0000-\u001f\u007f]/u.test(executable)
        || (process.platform === "win32" && path.extname(executable).toLowerCase() !== ".exe")
        || /\.(?:cmd|bat|[cm]?js|ps1|sh)$/iu.test(executable)) {
      throw unproven();
    }
  } catch { throw unproven(); }
  const isolatedEnvironment = safeProcessEnvironment(environment);
  Object.assign(isolatedEnvironment, { HOME: codexHome, USERPROFILE: codexHome, CODEX_HOME: codexHome });

  return new Promise((resolve, reject) => {
    let child;
    let phase = "initialize";
    let pending = Buffer.alloc(0);
    let received = 0;
    let result = null;
    let failed = false;
    let stopping = false;
    let settled = false;
    let stopTimer;
    const timer = setTimeout(() => stop(null, true), timeoutMs);

    function settle() {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      clearTimeout(stopTimer);
      pending.fill(0);
      pending = Buffer.alloc(0);
      if (failed || result == null) {
        result?.stdout.fill(0);
        reject(unproven());
      } else resolve(result);
    }
    function killOwnedChild() {
      try { child?.kill("SIGKILL"); } catch { failed = true; }
    }
    function stop(value, failure) {
      if (settled) return;
      if (failure) failed = true;
      if (stopping) {
        if (failure) killOwnedChild();
        return;
      }
      stopping = true;
      result = value;
      clearTimeout(timer);
      pending.fill(0);
      pending = Buffer.alloc(0);
      if (!child) { settle(); return; }
      // Close/kill only the native app-server we spawned; never enumerate unrelated processes.
      // Windows has no command/exec/terminate. A missing close remains unproven, not cancelled.
      stopTimer = setTimeout(() => {
        failed = true;
        killOwnedChild();
        for (const stream of [child.stdin, child.stdout, child.stderr]) {
          try { stream?.destroy(); } catch { /* never disclose process or stream details */ }
        }
        child.unref?.();
        settle();
      }, STOP_TIMEOUT_MS);
      try { child.stdin.end(); } catch { failed = true; }
      if (failed) killOwnedChild();
    }
    function send(message) {
      if (stopping || settled) return;
      try { child.stdin.write(JSON.stringify(message) + "\n"); }
      catch { stop(null, true); }
    }
    function message(value) {
      if (!value || typeof value !== "object" || Array.isArray(value)) return stop(null, true);
      // Notifications are private and inert. Never respond to server-side requests or approvals.
      if (typeof value.method === "string" && value.id === undefined) return;
      if (value.method !== undefined || value.error !== undefined || !value.result
          || typeof value.result !== "object" || Array.isArray(value.result)) return stop(null, true);
      if (phase === "initialize" && value.id === 1) {
        phase = "command";
        send({ method: "initialized", params: {} });
        send({ method: "command/exec", id: 2, params: {
          command: [process.execPath, "-e", READ_SCRIPT, target],
          permissionProfile: "factory-worker",
          cwd: workspaceRoot,
          timeoutMs: Math.min(COMMAND_TIMEOUT_MS, timeoutMs)
          // Windows requires buffered mode; omit tty/streaming, outputBytesCap and sandboxPolicy.
        } });
      } else if (phase === "command" && value.id === 2) {
        const { exitCode, stdout, stderr } = value.result;
        if (!Number.isInteger(exitCode) || exitCode < -2147483648 || exitCode > 2147483647
            || typeof stdout !== "string" || typeof stderr !== "string"
            || Buffer.byteLength(stdout, "utf8") > MAX_COMMAND_OUTPUT_BYTES
            || Buffer.byteLength(stderr, "utf8") > MAX_COMMAND_OUTPUT_BYTES) {
          return stop(null, true);
        }
        stop({ exitCode, stdout: Buffer.from(stdout, "utf8") }, false);
      } else stop(null, true);
    }
    function stream(bytes, parse) {
      if (!Buffer.isBuffer(bytes)) { stop(null, true); return; }
      received += bytes.length;
      if (stopping || settled || received > MAX_PROTOCOL_BYTES) {
        bytes.fill(0);
        if (!stopping && !settled) stop(null, true);
        return;
      }
      if (!parse) { bytes.fill(0); return; }
      const joined = Buffer.concat([pending, bytes]);
      pending.fill(0);
      bytes.fill(0);
      pending = joined;
      while (!stopping && !settled) {
        const newline = pending.indexOf(10);
        if (newline < 0) return;
        const line = pending.subarray(0, newline);
        const remainder = Buffer.from(pending.subarray(newline + 1));
        let value;
        try { value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(line)); }
        catch { pending.fill(0); remainder.fill(0); stop(null, true); return; }
        pending.fill(0);
        pending = remainder;
        message(value);
      }
    }
    try {
      // Direct native spawn avoids orphaning a CLI process behind the npm Node wrapper.
      child = spawnProcess(executable, ["app-server", "--stdio"], {
        cwd: workspaceRoot, env: isolatedEnvironment, shell: false, windowsHide: true,
        stdio: ["pipe", "pipe", "pipe"]
      });
      child.stdout.on("data", (bytes) => stream(bytes, true));
      child.stderr.on("data", (bytes) => stream(bytes, false));
      for (const emitter of [child, child.stdin, child.stdout, child.stderr]) {
        emitter.on("error", () => stop(null, true));
      }
      child.once("close", (code, signal) => {
        if (!stopping || code !== 0 || signal != null) failed = true;
        settle();
      });
      send({ method: "initialize", id: 1, params: {
        clientInfo: { name: "factory_credential_isolation", version: "1.0.0" },
        capabilities: { experimentalApi: true }
      } });
    } catch { stop(null, true); }
  });
}

function nativeCodexExecutable() {
  const architecture = { x64: "x86_64", arm64: "aarch64" }[process.arch];
  const platform = process.platform === "android" ? "linux" : process.platform;
  const suffix = { linux: "unknown-linux-musl", darwin: "apple-darwin", win32: "pc-windows-msvc" }[platform];
  if (!architecture || !suffix) throw unproven();
  const packageRoot = path.dirname(requireCodex.resolve(`@openai/codex-${platform}-${process.arch}/package.json`));
  return path.join(packageRoot, "vendor", `${architecture}-${suffix}`, "bin", platform === "win32" ? "codex.exe" : "codex");
}

function safeProcessEnvironment(source) {
  const result = {};
  for (const name of [
    "PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC",
    "LANG", "LC_ALL", "TZ"
  ]) {
    if (typeof source[name] === "string" && source[name].length <= 32 * 1024) result[name] = source[name];
  }
  return result;
}

function unproven() {
  return new ProtocolError(
    "WORKER_CREDENTIAL_ISOLATION_UNPROVEN",
    "credential and host filesystem read isolation is unproven"
  );
}
