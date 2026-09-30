import { spawn } from "node:child_process";
import { lstat, mkdtemp, realpath, rmdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ProtocolError } from "./protocol.mjs";

const CODEX_ENTRYPOINT = fileURLToPath(new URL("../node_modules/@openai/codex/bin/codex.js", import.meta.url));
const CHATGPT_STATUS_LINES = ["", "\n", "\r\n"].map((ending) => Buffer.from(`Logged in using ChatGPT${ending}`));
const MAX_STATUS_BYTES = 4096;
const STATUS_TIMEOUT_MS = 15_000;

/**
 * Check the dedicated profile through the CLI, never by reading auth.json or a keyring.
 * Do not pass forced_login_method to this preflight: Codex can log out a mismatched
 * account when that restriction is applied. Only the later, gated SDK invocation
 * receives the restriction. The dedicated profile must not be shared with another
 * process that changes authentication between this check and the SDK invocation.
 */
export async function verifyChatGptAuthentication({
  codexHome, codexPath, environment = {}, signal, runCommand = runLoginStatus
}) {
  let reply;
  let temporary;
  try {
    if (typeof codexHome !== "string" || !path.isAbsolute(codexHome)) throw loginRequired();
    const metadata = await lstat(codexHome);
    if (!metadata.isDirectory() || metadata.isSymbolicLink()) throw loginRequired();
    const resolvedHome = await realpath(codexHome);
    // A stripped Windows launch can resolve the OS temp root to USERPROFILE. Codex
    // then refuses PATH helper creation under that root and adds a warning to an
    // otherwise valid login status. Give this invocation a private temp child;
    // never inherit ambient temp roots or weaken the exact authentication parser.
    temporary = await mkdtemp(path.join(resolvedHome, ".factory-auth-status-"));
    const env = { HOME: resolvedHome, USERPROFILE: resolvedHome, CODEX_HOME: resolvedHome,
      TMP: temporary, TEMP: temporary, TMPDIR: temporary };
    for (const name of ["PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC"]) {
      if (typeof environment[name] === "string" && environment[name].length <= 32 * 1024) {
        env[name] = environment[name];
      }
    }
    reply = await runCommand({
      executable: codexPath ?? process.execPath,
      args: [...(codexPath == null ? [CODEX_ENTRYPOINT] : []), "login", "status"],
      env,
      // Run outside the product workspace so project-local config cannot change auth selection.
      cwd: resolvedHome,
      signal
    });
    if (reply?.exitCode !== 0 || !Buffer.isBuffer(reply.stdout) || !Buffer.isBuffer(reply.stderr)
        || reply.stdout.length + reply.stderr.length > MAX_STATUS_BYTES) throw loginRequired();
    // Pinned CLI 0.148.0 has no login-status JSON flag. Accept only its exact ChatGPT
    // success line; API, missing, ambiguous, changed and malformed output fail closed.
    // Byte comparisons avoid creating immutable strings containing private status output.
    const matches = (bytes) => CHATGPT_STATUS_LINES.some((line) => bytes.equals(line));
    if (!((matches(reply.stdout) && reply.stderr.length === 0)
        || (matches(reply.stderr) && reply.stdout.length === 0))) throw loginRequired();
    return Object.freeze({ method: "chatgpt" });
  } catch {
    // Login status may include an API-key suffix or account details. Never attach
    // raw output, a subprocess exception or a caller-supplied cause to this error.
    throw loginRequired();
  } finally {
    if (Buffer.isBuffer(reply?.stdout)) reply.stdout.fill(0);
    if (Buffer.isBuffer(reply?.stderr)) reply.stderr.fill(0);
    // Remove only our empty invocation directory, never recursively delete profile
    // contents if a future CLI starts writing files into the selected temp root.
    if (temporary) await rmdir(temporary).catch(() => {});
  }
}

export async function runLoginStatus({
  executable, args, env, cwd, signal, spawnProcess = spawn, timeoutMs = STATUS_TIMEOUT_MS
}) {
  if (signal?.aborted) throw loginRequired();
  return new Promise((resolve, reject) => {
    let child;
    try {
      child = spawnProcess(executable, args, { env, cwd, shell: false, windowsHide: true,
        stdio: ["ignore", "pipe", "pipe"] });
    } catch {
      reject(loginRequired());
      return;
    }
    const stdout = [];
    const stderr = [];
    let size = 0;
    let settled = false;
    let failed = false;
    const stop = () => {
      failed = true;
      try { child.kill("SIGKILL"); } catch { /* Keep subprocess failures private. */ }
      finish(null, "SIGKILL");
    };
    const timer = setTimeout(stop, timeoutMs);
    timer.unref?.();
    const erase = () => { for (const chunk of [...stdout, ...stderr]) chunk.fill(0); };
    const finish = (exitCode, exitSignal) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener("abort", stop);
      if (failed || exitSignal != null || !Number.isInteger(exitCode)) {
        erase();
        reject(loginRequired());
      } else {
        const result = { exitCode, stdout: Buffer.concat(stdout), stderr: Buffer.concat(stderr) };
        erase();
        resolve(result);
      }
    };
    const collect = (target, chunk) => {
      if (settled) return;
      size += chunk.length;
      if (size > MAX_STATUS_BYTES) { stop(); return; }
      target.push(Buffer.from(chunk));
    };
    child.stdout.on("data", (chunk) => collect(stdout, chunk));
    child.stderr.on("data", (chunk) => collect(stderr, chunk));
    child.once("error", () => { failed = true; finish(null, null); });
    child.once("close", finish);
    signal?.addEventListener("abort", stop, { once: true });
    if (signal?.aborted) stop();
  });
}

function loginRequired() {
  return new ProtocolError("WORKER_CHATGPT_LOGIN_REQUIRED",
    "verified ChatGPT login in the dedicated Codex profile is required");
}
