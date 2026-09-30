import { mkdir, lstat, realpath, readdir, open } from "node:fs/promises";
import { constants } from "node:fs";
import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expectedPermissionProfileConfig, readValidatedPermissionProfileConfig, verifyCredentialIsolation }
  from "../codex-worker-runner/src/credential-isolation.mjs";
import { verifyChatGptAuthentication }
  from "../codex-worker-runner/src/chatgpt-authentication.mjs";
import { setupWindowsSandbox } from "./codex-windows-sandbox-setup.mjs";

// Local production-test preparation, not a deployment/operations controller.
// Never reads auth.json, reuses an API key, or changes the user's default Codex home.
const repository = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const modes = new Set(["prepare", "status", "login", "isolation", "sandbox-setup"]);
const marker = "factory.codex-local-production-validation.v1\n";

/** Dependencies are replaceable only by imported tests; CLI callers cannot bypass the preflight. */
export async function runLocalWorkerHelper(args, {
  environment: sourceEnvironment = process.env,
  writeOutput = (text) => process.stdout.write(text),
  checkAuthentication = verifyChatGptAuthentication,
  checkIsolation = verifyCredentialIsolation,
  spawnProcess = spawn
} = {}) {
  const [mode, suppliedRoot] = args;
  if (args.length !== 2 || !modes.has(mode) || !suppliedRoot || !path.isAbsolute(suppliedRoot)) {
    throw new Error("USAGE: node tools/codex-worker-local.mjs prepare|status|login|isolation|sandbox-setup ABSOLUTE_LOCAL_ROOT");
  }
  const root = path.resolve(suppliedRoot);
  const homes = [sourceEnvironment.USERPROFILE, sourceEnvironment.HOME]
    .filter((value) => typeof value === "string" && path.isAbsolute(value)).map((value) => path.resolve(value));
  const codexHomes = homes.map((home) => path.join(home, ".codex"));
  if (typeof sourceEnvironment.CODEX_HOME === "string" && sourceEnvironment.CODEX_HOME.length > 0) {
    codexHomes.push(path.resolve(sourceEnvironment.CODEX_HOME));
  }
  if (/[\u0000-\u001f\u007f]/u.test(root)
      || within(repository, root) || within(root, repository) || root === path.parse(root).root
      || homes.some((home) => within(root, home))
      || codexHomes.some((home) => within(root, home) || within(home, root))) {
    throw new Error("LOCAL_ROOT_MUST_BE_DEDICATED_AND_OUTSIDE_REPOSITORY");
  }
  await noLinks(root);
  await noRepositoryAncestor(root);
  const markerPath = path.join(root, ".factory-local-profile");
  const prepared = await exactExistingFile(markerPath, marker, "LOCAL_PROFILE_NOT_PREPARED", mode === "prepare");
  const roots = {
    codexHome: path.join(root, "codex-home"),
    stateRoot: path.join(root, "state"),
    sourceWorkspaceBase: path.join(root, "source")
  };
  for (const directory of Object.values(roots)) {
    await noLinks(directory);
  }
  const config = expectedPermissionProfileConfig(roots);
  const configPath = path.join(roots.codexHome, "config.toml");
  // Inspect every existing authority file before creating anything. An unrelated nonempty
  // directory cannot be adopted as a profile merely by writing our marker into it.
  const existingConfig = await validatedExistingConfig(roots, mode === "prepare");
  const existingRoot = await metadata(root);
  if (mode === "prepare" && existingRoot && !prepared && (await readdir(root)).length > 0) {
    throw new Error("UNPREPARED_LOCAL_ROOT_NOT_EMPTY");
  }
  if (mode === "prepare") {
    await mkdir(root, { recursive: true, mode: 0o700 });
    await noLinks(root);
    await exactFile(markerPath, marker);
    for (const directory of Object.values(roots)) {
      await mkdir(directory, { recursive: true, mode: 0o700 });
      await noLinks(directory);
      await realpath(directory);
    }
    // Validated CLI project metadata is preserved. prepare never rewrites an existing profile.
    if (!existingConfig) await exactFile(configPath, config);
    await validatedExistingConfig(roots, false);
    writeOutput(JSON.stringify({ status: "PREPARED", ...roots, authentication: "NOT_RUN" }) + "\n");
  } else {
    for (const directory of Object.values(roots)) await realpath(directory);
    const environment = {};
    for (const name of ["PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC"]) {
      if (typeof sourceEnvironment[name] === "string" && sourceEnvironment[name].length <= 32 * 1024) {
        environment[name] = sourceEnvironment[name];
      }
    }
    const savedAuth = await metadata(path.join(roots.codexHome, "auth.json"));
    if (mode !== "login" && savedAuth && (!savedAuth.isFile() || savedAuth.isSymbolicLink())) {
      throw new Error("LOCAL_AUTHENTICATION_LINK_REJECTED");
    }
    if (mode === "sandbox-setup") {
      const workspaceRoot = path.join(roots.stateRoot, "isolation-workspace");
      await noLinks(workspaceRoot);
      await mkdir(workspaceRoot, { recursive: true, mode: 0o700 });
      await noLinks(workspaceRoot);
      let setup;
      try {
        setup = await setupWindowsSandbox({ codexHome: roots.codexHome, workspaceRoot, environment, spawnProcess });
      } finally {
        // Setup must not silently rewrite the restricted permission profile. Never auto-restore it.
        await noLinks(workspaceRoot);
        await noLinks(roots.codexHome);
        await exactExistingFile(markerPath, marker, "LOCAL_PROFILE_NOT_PREPARED", false);
        const finalConfig = await validatedExistingConfig(roots, false);
        if (!finalConfig.equals(existingConfig)) throw new Error("LOCAL_PROFILE_CONFIG_MISMATCH");
      }
      writeOutput(JSON.stringify(setup) + "\n");
      return;
    }
    if (mode === "login") {
      // Refuse to touch any existing authentication. The caller must choose a fresh
      // dedicated profile; don't replace or log out a saved user/API account.
      if (savedAuth) throw new Error("EXISTING_AUTHENTICATION_NOT_MODIFIED");
      const entrypoint = fileURLToPath(new URL(
        "../codex-worker-runner/node_modules/@openai/codex/bin/codex.js", import.meta.url));
      writeOutput("CHATGPT_SIGN_IN: complete the dedicated Worker browser login. No API key is used.\n");
      const exitCode = await new Promise((resolve, reject) => {
        const child = spawnProcess(process.execPath,
          [entrypoint, "-c", 'cli_auth_credentials_store="file"', "login"], {
          cwd: roots.codexHome, shell: false, windowsHide: true,
          env: { ...environment, HOME: roots.codexHome, USERPROFILE: roots.codexHome, CODEX_HOME: roots.codexHome },
          stdio: ["ignore", "pipe", "pipe"]
        });
        // OAuth URLs/status can contain sensitive state. Drain without displaying or retaining.
        child.stdout.on("data", (bytes) => bytes.fill(0));
        child.stderr.on("data", (bytes) => bytes.fill(0));
        child.once("error", () => reject(new Error("CHATGPT_LOGIN_PROCESS_FAILED")));
        child.once("close", resolve);
      });
      if (exitCode !== 0) throw new Error("CHATGPT_LOGIN_NOT_COMPLETED");
    }
    if (mode === "status" || mode === "login") {
      const auth = await checkAuthentication({ codexHome: roots.codexHome, environment });
      writeOutput(JSON.stringify({ status: "AUTHENTICATED", method: auth.method }) + "\n");
    } else if (mode === "isolation") {
      const workspaceRoot = path.join(roots.stateRoot, "isolation-workspace");
      await noLinks(workspaceRoot);
      await mkdir(workspaceRoot, { recursive: true, mode: 0o700 });
      const proof = await checkIsolation({ ...roots, workspaceRoot, environment });
      writeOutput(JSON.stringify({ status: "ISOLATION_PROVEN", ...proof }) + "\n");
    }
  }
}

function within(parent, candidate) {
  const relative = path.relative(parent, candidate);
  return relative === "" || (relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

async function noLinks(candidate) {
  let current = candidate;
  while (true) {
    const info = await lstat(current).catch((error) => {
      if (error.code === "ENOENT") return null;
      throw error;
    });
    if (info && (!info.isDirectory() || info.isSymbolicLink())) throw new Error("LOCAL_ROOT_LINK_REJECTED");
    const parent = path.dirname(current);
    if (parent === current) return;
    current = parent;
  }
}

async function noRepositoryAncestor(candidate) {
  let current = candidate;
  while (true) {
    if (await metadata(path.join(current, ".git"))) {
      throw new Error("LOCAL_ROOT_MUST_BE_DEDICATED_AND_OUTSIDE_REPOSITORY");
    }
    const parent = path.dirname(current);
    if (parent === current) return;
    current = parent;
  }
}

async function exactFile(target, content) {
  if (await exactExistingFile(target, content, "EXISTING_LOCAL_FILE_NOT_MODIFIED", true)) return;
  const handle = await open(target,
    constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | (constants.O_NOFOLLOW ?? 0), 0o600);
  try {
    await handle.writeFile(content, "utf8");
    await handle.sync();
  } finally {
    await handle.close();
  }
}

async function metadata(target) {
  return lstat(target).catch((error) => {
    if (error.code === "ENOENT") return null;
    throw error;
  });
}

async function validatedExistingConfig(roots, allowMissing) {
  if (allowMissing && !await metadata(path.join(roots.codexHome, "config.toml"))) return null;
  try {
    return await readValidatedPermissionProfileConfig(roots);
  } catch {
    throw new Error("LOCAL_PROFILE_CONFIG_MISMATCH");
  }
}

async function exactExistingFile(target, content, code, allowMissing) {
  const before = await metadata(target);
  if (!before) {
    if (allowMissing) return false;
    throw new Error(code);
  }
  const expected = Buffer.from(content, "utf8");
  if (!before.isFile() || before.isSymbolicLink() || before.size !== expected.length) throw new Error(code);
  const handle = await open(target, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  try {
    const opened = await handle.stat();
    if (!opened.isFile() || opened.dev !== before.dev || opened.ino !== before.ino) throw new Error(code);
    const actual = Buffer.alloc(expected.length + 1);
    let total = 0;
    while (total < actual.length) {
      const { bytesRead } = await handle.read(actual, total, actual.length - total, null);
      if (!bytesRead) break;
      total += bytesRead;
    }
    const after = await metadata(target);
    if (total !== expected.length || !actual.subarray(0, total).equals(expected)
        || !after?.isFile() || after.isSymbolicLink() || after.dev !== before.dev || after.ino !== before.ino
        || after.size !== before.size) throw new Error(code);
  } finally {
    await handle.close();
  }
  return true;
}

// Importing this module for tests never runs a CLI, reads a profile, or initiates authentication.
const normalizeCase = (value) => process.platform === "win32" ? value.toLowerCase() : value;
if (process.argv[1]
    && normalizeCase(path.resolve(process.argv[1])) === normalizeCase(fileURLToPath(import.meta.url))) {
  try {
    await runLocalWorkerHelper(process.argv.slice(2));
  } catch (error) {
    const safeCode = typeof error.code === "string" && /^[A-Z][A-Z0-9_]+$/.test(error.code)
      ? error.code : typeof error.message === "string" && /^[A-Z][A-Z0-9_]+$/.test(error.message)
        ? error.message : "LOCAL_PREFLIGHT_FAILED";
    process.stderr.write(safeCode + "\n");
    process.exitCode = 2;
  }
}
