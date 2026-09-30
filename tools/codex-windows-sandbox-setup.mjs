import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import path from "node:path";

const requireCodex = createRequire(new URL("../codex-worker-runner/package.json", import.meta.url));
const MAX_STREAM_BYTES = 1024 * 1024;
const SETUP_TIMEOUT_MS = 180_000;

/** Local, explicitly authorized setup only. No authentication, model, thread, or command APIs. */
export async function setupWindowsSandbox({ codexHome, workspaceRoot, environment = {},
  spawnProcess = spawn, timeoutMs = SETUP_TIMEOUT_MS }) {
  if (process.platform !== "win32" || !["x64", "arm64"].includes(process.arch)) {
    throw new Error("WINDOWS_SANDBOX_SETUP_UNSUPPORTED");
  }
  if (!path.isAbsolute(codexHome) || !path.isAbsolute(workspaceRoot)
      || !Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > SETUP_TIMEOUT_MS) {
    throw new Error("WINDOWS_SANDBOX_SETUP_INVALID_INPUT");
  }
  const target = process.arch === "x64" ? "x86_64" : "aarch64";
  // Spawn the pinned native binary directly so terminating it cannot orphan a Node wrapper child.
  const packageRoot = path.dirname(requireCodex.resolve(`@openai/codex-win32-${process.arch}/package.json`));
  const executable = path.join(packageRoot, "vendor", `${target}-pc-windows-msvc`, "bin", "codex.exe");
  const isolatedEnvironment = {};
  for (const name of ["PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC"]) {
    if (typeof environment[name] === "string" && environment[name].length <= 32 * 1024) {
      isolatedEnvironment[name] = environment[name];
    }
  }
  Object.assign(isolatedEnvironment, { HOME: codexHome, USERPROFILE: codexHome, CODEX_HOME: codexHome });
  return new Promise((resolve, reject) => {
    let child;
    let phase = "initialize";
    let setupRequested = false;
    let completion = null;
    let result = null;
    let failure = null;
    let stopping = false;
    let settled = false;
    let received = 0;
    let pending = Buffer.alloc(0);
    let stopTimer;
    const timer = setTimeout(() => stop(null, "WINDOWS_SANDBOX_SETUP_UNCERTAIN"), timeoutMs);
    const uncertainty = () => setupRequested ? "WINDOWS_SANDBOX_SETUP_UNCERTAIN" : "WINDOWS_SANDBOX_SETUP_FAILED";
    function settle() {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      clearTimeout(stopTimer);
      pending.fill(0);
      pending = Buffer.alloc(0);
      if (failure) reject(new Error(failure));
      else resolve(result);
    }
    function stop(value, code) {
      if (stopping || settled) return;
      stopping = true;
      result = value;
      failure = code;
      clearTimeout(timer);
      pending.fill(0);
      pending = Buffer.alloc(0);
      if (!child) { settle(); return; }
      // A timed-out elevated OS setup may outlive app-server. Never claim cancellation or retry it.
      stopTimer = setTimeout(() => {
        failure = "WINDOWS_SANDBOX_SETUP_UNCERTAIN";
        try { child.kill(); } catch { /* private process details discarded */ }
        child.stdin.destroy();
        child.stdout.destroy();
        child.stderr.destroy();
        child.unref?.();
        settle();
      }, 2_000);
      try {
        child.stdin.end();
        if (code) child.kill();
      } catch { failure = "WINDOWS_SANDBOX_SETUP_UNCERTAIN"; }
    }
    function send(message) {
      if (stopping) return;
      try { child.stdin.write(JSON.stringify(message) + "\n"); }
      catch { stop(null, uncertainty()); }
    }
    function completed(params) {
      if (!params || params.mode !== "elevated" || typeof params.success !== "boolean"
          || (params.error !== undefined && params.error !== null && typeof params.error !== "string")
          || (params.success && params.error != null)) {
        stop(null, uncertainty());
      } else if (!params.success) {
        stop(null, "WINDOWS_SANDBOX_SETUP_FAILED");
      } else {
        stop({ status: "WINDOWS_SANDBOX_SETUP_COMPLETED", setupStarted: true }, null);
      }
    }
    function message(value) {
      if (!value || typeof value !== "object" || Array.isArray(value)) return stop(null, uncertainty());
      if (value.method === "windowsSandbox/setupCompleted" && value.id === undefined) {
        if (!setupRequested || completion) return stop(null, uncertainty());
        completion = value.params;
        if (phase === "completion") completed(completion);
        return;
      }
      // Unrelated notifications are private and inert; never service server-side requests.
      if (typeof value.method === "string" && value.id === undefined) return;
      if (value.method !== undefined || value.error !== undefined || !value.result
          || typeof value.result !== "object" || Array.isArray(value.result)) return stop(null, uncertainty());
      if (phase === "initialize" && value.id === 1) {
        phase = "readiness";
        send({ method: "initialized", params: {} });
        send({ method: "windowsSandbox/readiness", id: 2, params: null });
      } else if (phase === "readiness" && value.id === 2) {
        if (value.result.status === "ready") {
          stop({ status: "WINDOWS_SANDBOX_READY", setupStarted: false }, null);
        } else if (["notConfigured", "updateRequired"].includes(value.result.status)) {
          phase = "start";
          setupRequested = true;
          send({ method: "windowsSandbox/setupStart", id: 3, params: { mode: "elevated", cwd: workspaceRoot } });
        } else stop(null, uncertainty());
      } else if (phase === "start" && value.id === 3) {
        if (value.result.started !== true) return stop(null, uncertainty());
        phase = "completion";
        if (completion) completed(completion);
      } else stop(null, uncertainty());
    }
    function stream(bytes, parse) {
      if (!Buffer.isBuffer(bytes)) { stop(null, uncertainty()); return; }
      received += bytes.length;
      if (stopping || received > MAX_STREAM_BYTES) {
        bytes.fill(0);
        if (!stopping) stop(null, uncertainty());
        return;
      }
      if (!parse) { bytes.fill(0); return; }
      const joined = Buffer.concat([pending, bytes]);
      pending.fill(0);
      bytes.fill(0);
      pending = joined;
      while (!stopping) {
        const newline = pending.indexOf(10);
        if (newline < 0) return;
        const line = pending.subarray(0, newline);
        const remainder = Buffer.from(pending.subarray(newline + 1));
        let value;
        try { value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(line)); }
        catch { pending.fill(0); remainder.fill(0); stop(null, uncertainty()); return; }
        pending.fill(0);
        pending = remainder;
        message(value);
      }
    }
    try {
      child = spawnProcess(executable, ["app-server", "--stdio"], {
        cwd: workspaceRoot, env: isolatedEnvironment, shell: false, windowsHide: true,
        stdio: ["pipe", "pipe", "pipe"]
      });
      child.stdout.on("data", (bytes) => stream(bytes, true));
      child.stderr.on("data", (bytes) => stream(bytes, false));
      for (const emitter of [child, child.stdin, child.stdout, child.stderr]) {
        emitter.on("error", () => stop(null, uncertainty()));
      }
      child.once("close", (code) => {
        if (!stopping) failure = uncertainty();
        else if (!failure && code !== 0) failure = "WINDOWS_SANDBOX_SETUP_UNCERTAIN";
        settle();
      });
      send({ method: "initialize", id: 1, params: {
        clientInfo: { name: "factory_windows_sandbox_setup", version: "1.0.0" },
        capabilities: { experimentalApi: true }
      } });
    } catch { stop(null, uncertainty()); }
  });
}
