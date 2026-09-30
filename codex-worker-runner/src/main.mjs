#!/usr/bin/env node
import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { AtomicStateStore } from "./atomic-state-store.mjs";
import { CAPABILITIES, CodexOperationService, publicSnapshot } from "./codex-operation.mjs";
import { verifyChatGptAuthentication } from "./chatgpt-authentication.mjs";
import {
  PERMISSION_PROFILE_NAME,
  verifyCredentialIsolation
} from "./credential-isolation.mjs";
import {
  captureSecrets,
  failure,
  MAX_REQUEST_BYTES,
  parseRequest,
  ProtocolError,
  success
} from "./protocol.mjs";

const ENTRYPOINT = fileURLToPath(import.meta.url);

export async function runMain(argv = process.argv.slice(2), environment = process.env) {
  const options = parseArguments(argv);
  const store = new AtomicStateStore(options.stateRoot);
  const trusted = captureTrustedRuntime(environment);
  let secrets = null;
  const service = new CodexOperationService({
    store,
    workspaceBase: options.workspaceBase,
    launcher: options.internalOperation == null
      ? (tenantKey, operationId, runtime) =>
          launchInternal(options, tenantKey, operationId, runtime, trusted)
      : null
  });
  await service.initialize();

  if (options.internalOperation != null) {
    try {
      secrets = captureSecrets(environment);
      await service.execute(
        options.internalTenantKey,
        options.internalOperation,
        runtimeOf(secrets, trusted, environment, options)
      );
      return 0;
    } finally {
      secrets?.dispose();
    }
  }

  let command = "unknown";
  try {
    const raw = await readBoundedStdin(process.stdin);
    const request = parseRequest(raw);
    command = request.command;
    if (request.command === "capabilities") {
      const isolation = await verifyCredentialIsolation({
        codexHome: trusted.FACTORY_CODEX_HOME,
        stateRoot: options.stateRoot,
        sourceWorkspaceBase: trusted.FACTORY_CODEX_SOURCE_WORKSPACE_BASE,
        workspaceRoot: await store.isolationProbeWorkspace(),
        codexPath: trusted.FACTORY_CODEX_PATH,
        environment: safeBaseEnvironment(environment)
      });
      await verifyChatGptAuthentication({
        codexHome: trusted.FACTORY_CODEX_HOME,
        codexPath: trusted.FACTORY_CODEX_PATH,
        environment: safeBaseEnvironment(environment)
      });
      writeResponse(success(command, {
        worker: "codex",
        capabilities: CAPABILITIES,
        execution: {
          approvalPolicy: "never",
          commandNetworkEnabled: false,
          credentialIsolation: "COMMAND_SENTINEL_PROVEN",
          credentialIsolationEvidenceSha256: isolation.evidenceSha256,
          permissionProfile: PERMISSION_PROFILE_NAME,
          sessionResumeRequired: false,
          webSearchMode: "disabled"
        }
      }));
      return 0;
    }

    secrets = captureSecrets(environment);
    const runtime = runtimeOf(secrets, trusted, environment, options);
    if (request.command === "submit") {
      const state = await service.submit(request, runtime);
      writeResponse(success(command, { observation: "FOUND", snapshot: publicSnapshot(state, secrets.attemptToken) }));
      return 0;
    }
    if (request.command === "status") {
      const observation = await service.status(request, runtime);
      writeResponse(success(command, {
        observation: observation.observation,
        snapshot: observation.state == null ? null : publicSnapshot(observation.state, secrets.attemptToken)
      }));
      return 0;
    }
    const observation = await service.cancel(request);
    writeResponse(success(command, {
      observation: observation.observation,
      snapshot: observation.state == null ? null : publicSnapshot(observation.state, secrets.attemptToken)
    }));
    return 0;
  } catch (error) {
    writeResponse(failure(command, error));
    return error instanceof ProtocolError ? 2 : 3;
  } finally {
    secrets?.dispose();
  }
}

function parseArguments(argv) {
  const values = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (
      !["--state-root", "--workspace-base", "--internal-execute", "--internal-tenant-key"].includes(key) ||
      value == null
    ) {
      throw new ProtocolError("WORKER_ARGUMENT_INVALID", "runner arguments are invalid");
    }
    if (values.has(key)) {
      throw new ProtocolError("WORKER_ARGUMENT_INVALID", "runner argument is duplicated");
    }
    values.set(key, value);
  }
  const stateRoot = values.get("--state-root");
  const workspaceBase = values.get("--workspace-base");
  if (!stateRoot || !workspaceBase || !path.isAbsolute(stateRoot) || !path.isAbsolute(workspaceBase)) {
    throw new ProtocolError("WORKER_ARGUMENT_INVALID", "configured roots must be absolute");
  }
  const internalOperation = values.get("--internal-execute") ?? null;
  const internalTenantKey = values.get("--internal-tenant-key") ?? null;
  if (
    (internalOperation == null) !== (internalTenantKey == null) ||
    (internalTenantKey != null && !/^[0-9a-f]{64}$/u.test(internalTenantKey))
  ) {
    throw new ProtocolError("WORKER_ARGUMENT_INVALID", "internal operation ownership is invalid");
  }
  return {
    stateRoot: path.resolve(stateRoot),
    workspaceBase: path.resolve(workspaceBase),
    internalOperation,
    internalTenantKey
  };
}

function captureTrustedRuntime(environment) {
  const names = [
    "FACTORY_CODEX_HOME",
    "FACTORY_CODEX_MODEL",
    "FACTORY_CODEX_PATH",
    "FACTORY_CODEX_SOURCE_WORKSPACE_BASE"
  ];
  const values = {};
  for (const name of names) {
    if (typeof environment[name] === "string" && environment[name].length > 0) {
      if (environment[name].length > 4096 || /[\u0000-\u001f\u007f]/u.test(environment[name])) {
        throw new ProtocolError("WORKER_CONFIGURATION_INVALID", "trusted runner configuration is invalid");
      }
      values[name] = environment[name];
    }
    environment[name] = "";
    delete environment[name];
  }
  return values;
}

function runtimeOf(secrets, trusted, environment, options) {
  return {
    attemptToken: secrets.attemptToken,
    trustedEnvironment: {
      ...safeBaseEnvironment(environment),
      ...trusted,
      FACTORY_CODEX_STATE_ROOT: options.stateRoot
    }
  };
}

function launchInternal(options, tenantKey, operationId, runtime, trusted) {
  const env = {
    ...safeBaseEnvironment(process.env),
    FACTORY_WORKER_ATTEMPT_TOKEN: runtime.attemptToken.toString("utf8"),
    ...(trusted.FACTORY_CODEX_HOME ? { FACTORY_CODEX_HOME: trusted.FACTORY_CODEX_HOME } : {}),
    ...(trusted.FACTORY_CODEX_MODEL ? { FACTORY_CODEX_MODEL: trusted.FACTORY_CODEX_MODEL } : {}),
    ...(trusted.FACTORY_CODEX_PATH ? { FACTORY_CODEX_PATH: trusted.FACTORY_CODEX_PATH } : {}),
    ...(trusted.FACTORY_CODEX_SOURCE_WORKSPACE_BASE
      ? { FACTORY_CODEX_SOURCE_WORKSPACE_BASE: trusted.FACTORY_CODEX_SOURCE_WORKSPACE_BASE }
      : {})
  };
  const child = spawn(
    process.execPath,
    [
      ENTRYPOINT,
      "--state-root",
      options.stateRoot,
      "--workspace-base",
      options.workspaceBase,
      "--internal-execute",
      operationId,
      "--internal-tenant-key",
      tenantKey
    ],
    {
      detached: true,
      shell: false,
      windowsHide: true,
      stdio: "ignore",
      env
    }
  );
  child.unref();
  return child;
}

function safeBaseEnvironment(source) {
  const result = {};
  for (const name of ["PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC"]) {
    if (typeof source[name] === "string" && source[name].length <= 32 * 1024) {
      result[name] = source[name];
    }
  }
  return result;
}

async function readBoundedStdin(stream) {
  const chunks = [];
  let size = 0;
  for await (const chunk of stream) {
    size += chunk.length;
    if (size > MAX_REQUEST_BYTES) {
      throw new ProtocolError("WORKER_REQUEST_TOO_LARGE", "request exceeds the protocol byte limit");
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

function writeResponse(json) {
  process.stdout.write(`${json}\n`);
}

if (path.resolve(process.argv[1] ?? "") === path.resolve(ENTRYPOINT)) {
  runMain().then(
    (code) => {
      process.exitCode = code;
    },
    () => {
      process.stdout.write(`${failure("unknown", new ProtocolError("WORKER_INTERNAL_ERROR", "worker failed"))}\n`);
      process.exitCode = 3;
    }
  );
}
