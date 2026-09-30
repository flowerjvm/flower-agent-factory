import { randomBytes } from "node:crypto";
import { constants } from "node:fs";
import { link, lstat, mkdir, open, realpath, rename, unlink } from "node:fs/promises";
import { createServer } from "node:net";
import path from "node:path";
import { canonicalJson, operationDigest, ProtocolError } from "./protocol.mjs";

const MAX_STATE_BYTES = 256 * 1024;
const MAX_LOCK_BYTES = 4 * 1024;
const LOCK_SCHEMA_VERSION = "factory.codex-worker.operation-lock.v1";

export class AtomicStateStore {
  constructor(stateRoot) {
    if (typeof stateRoot !== "string" || !path.isAbsolute(stateRoot)) {
      throw new ProtocolError("WORKER_STATE_ROOT_INVALID", "state root must be an absolute configured path");
    }
    this.stateRoot = path.resolve(stateRoot);
    this.tenantsRoot = path.join(this.stateRoot, "tenants");
    this.bootId = randomBytes(16).toString("hex");
  }

  async initialize() {
    const rootMetadata = await lstat(this.stateRoot).catch(() => null);
    if (rootMetadata == null || !rootMetadata.isDirectory() || rootMetadata.isSymbolicLink()) {
      throw new ProtocolError("WORKER_STATE_ROOT_INVALID", "configured state root is unavailable");
    }
    this.stateRoot = await realpath(this.stateRoot).catch(() => {
      throw new ProtocolError("WORKER_STATE_ROOT_INVALID", "configured state root is unavailable");
    });
    this.tenantsRoot = await secureChildDirectory(this.stateRoot, "tenants");
  }

  operationDirectory(tenantKey, operationId) {
    return path.join(
      this.tenantsRoot,
      partitionKey(tenantKey),
      "operations",
      operationDigest(operationId)
    );
  }

  async isolationProbeWorkspace() {
    return secureChildDirectory(this.stateRoot, "credential-isolation-probe-workspace");
  }

  async read(tenantKey, operationId) {
    const directory = await this.#operationDirectory(tenantKey, operationId, false);
    if (directory == null) return null;
    const file = path.join(directory, "state.json");
    let raw;
    try {
      raw = await readPrivateRegularFile(file, MAX_STATE_BYTES);
    } catch (error) {
      if (error?.code === "ENOENT") return null;
      if (error instanceof ProtocolError) throw error;
      throw new ProtocolError("WORKER_STATE_READ_FAILED", "operation state could not be read", true);
    }
    try {
      return JSON.parse(raw.toString("utf8"));
    } catch {
      throw new ProtocolError("WORKER_STATE_CORRUPT", "operation state is invalid");
    }
  }

  async write(tenantKey, operationId, state) {
    const directory = await this.#operationDirectory(tenantKey, operationId, true);
    const destination = path.join(directory, "state.json");
    const temporary = path.join(directory, `.state-${process.pid}-${Date.now()}.tmp`);
    const bytes = Buffer.from(canonicalJson(state), "utf8");
    if (bytes.length > MAX_STATE_BYTES) {
      throw new ProtocolError("WORKER_STATE_TOO_LARGE", "operation state exceeds its bound");
    }
    const noFollow = constants.O_NOFOLLOW ?? 0;
    const handle = await open(
      temporary,
      constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | noFollow,
      0o600
    );
    try {
      await handle.writeFile(bytes);
      await handle.sync();
    } finally {
      await handle.close();
    }
    await rename(temporary, destination);
    const persisted = await readPrivateRegularFile(destination, MAX_STATE_BYTES);
    if (!persisted.equals(bytes)) {
      throw new ProtocolError("WORKER_STATE_WRITE_FAILED", "operation state did not persist atomically", true);
    }
    await syncDirectory(directory);
  }

  async withLock(tenantKey, operationId, action) {
    const directory = await this.#operationDirectory(tenantKey, operationId, true);
    const lockPath = path.join(directory, "operation.lock");
    let ownership;
    try {
      ownership = await acquireOperationLock(directory, lockPath, this.bootId);
    } catch (error) {
      if (error instanceof ProtocolError) throw error;
      throw new ProtocolError("WORKER_OPERATION_LOCK_FAILED", "operation lock could not be acquired", true);
    }
    try {
      return await action();
    } finally {
      await releaseOperationLock(directory, lockPath, ownership);
    }
  }

  async #operationDirectory(tenantKey, operationId, create) {
    const operationsRoot = await this.#tenantOperationsRoot(tenantKey, create);
    if (operationsRoot == null) return null;
    const name = operationDigest(operationId);
    const candidate = path.join(operationsRoot, name);
    if (create) return secureChildDirectory(operationsRoot, name);
    const metadata = await lstat(candidate).catch((error) => {
      if (error?.code === "ENOENT") return null;
      throw error;
    });
    if (metadata == null) return null;
    return verifyExistingDirectory(operationsRoot, candidate);
  }

  async #tenantOperationsRoot(tenantKey, create) {
    const key = partitionKey(tenantKey);
    const tenantCandidate = path.join(this.tenantsRoot, key);
    let tenantRoot;
    if (create) {
      tenantRoot = await secureChildDirectory(this.tenantsRoot, key);
    } else {
      const metadata = await lstat(tenantCandidate).catch((error) => {
        if (error?.code === "ENOENT") return null;
        throw error;
      });
      if (metadata == null) return null;
      tenantRoot = await verifyExistingDirectory(this.tenantsRoot, tenantCandidate);
    }
    const operationsCandidate = path.join(tenantRoot, "operations");
    if (create) return secureChildDirectory(tenantRoot, "operations");
    const metadata = await lstat(operationsCandidate).catch((error) => {
      if (error?.code === "ENOENT") return null;
      throw error;
    });
    if (metadata == null) return null;
    return verifyExistingDirectory(tenantRoot, operationsCandidate);
  }
}

async function acquireOperationLock(directory, lockPath, bootId) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      return await tryAcquireOperationLock(directory, lockPath, bootId);
    } catch (error) {
      if (!(error instanceof ProtocolError) || error.code !== "WORKER_OPERATION_LOCK_RETRY") {
        throw error;
      }
    }
  }
  throw new ProtocolError("WORKER_OPERATION_BUSY", "operation lock changed during acquisition", true);
}

async function tryAcquireOperationLock(directory, lockPath, bootId) {
  const existing = await lstat(lockPath).catch((error) => {
    if (error?.code === "ENOENT") return null;
    throw error;
  });
  if (existing != null) {
    return recoverPublishedLock(directory, lockPath);
  }

  const listener = await listenForOwnership(0);
  const ownerId = randomBytes(16).toString("hex");
  const metadata = {
    schemaVersion: LOCK_SCHEMA_VERSION,
    bootId,
    ownerId,
    port: listener.port
  };
  const bytes = Buffer.from(canonicalJson(metadata), "utf8");
  const temporary = path.join(directory, `.operation-lock-${bootId}-${ownerId}.tmp`);
  try {
    await writePrivateNew(temporary, bytes, MAX_LOCK_BYTES);
    try {
      await link(temporary, lockPath);
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;
      await listener.close();
      await unlink(temporary).catch(() => {});
      throw retryLockAcquisition();
    }
    await unlink(temporary);
    await verifyPublishedLock(directory, lockPath, bytes);
    return { ...listener, bytes };
  } catch (error) {
    await listener.close().catch(() => {});
    await unlink(temporary).catch(() => {});
    throw error;
  }
}

async function recoverPublishedLock(directory, lockPath) {
  const bytes = await readPrivateRegularFile(lockPath, MAX_LOCK_BYTES).catch((error) => {
    if (error?.code === "ENOENT") throw retryLockAcquisition();
    if (error instanceof ProtocolError) throw error;
    throw new ProtocolError("WORKER_OPERATION_LOCK_INVALID", "operation lock is not trusted", true);
  });
  const metadata = parseLockMetadata(bytes);
  await verifyPublishedLock(directory, lockPath, bytes, true);
  const listener = await listenForOwnership(metadata.port, true);
  if (listener == null) {
    throw new ProtocolError(
      "WORKER_OPERATION_BUSY",
      "operation is already being updated",
      true
    );
  }
  await verifyPublishedLock(directory, lockPath, bytes, true).catch(async (error) => {
    await listener.close().catch(() => {});
    throw error;
  });
  return { ...listener, bytes };
}

async function verifyPublishedLock(directory, lockPath, expected, retryIfMissing = false) {
  const metadata = await lstat(lockPath).catch((error) => {
    if (error?.code === "ENOENT") return null;
    throw error;
  });
  if (metadata == null && retryIfMissing) throw retryLockAcquisition();
  if (metadata == null) {
    throw new ProtocolError("WORKER_OPERATION_LOCK_INVALID", "operation lock disappeared", true);
  }
  const resolvedDirectory = await realpath(directory).catch(() => null);
  const resolvedLock = await realpath(lockPath).catch(() => null);
  if (resolvedLock == null && retryIfMissing) throw retryLockAcquisition();
  if (
    resolvedDirectory == null ||
    resolvedLock == null ||
    normalizePath(path.dirname(resolvedLock)) !== normalizePath(resolvedDirectory)
  ) {
    throw new ProtocolError("WORKER_OPERATION_LOCK_INVALID", "operation lock escaped its directory", true);
  }
  const actual = await readPrivateRegularFile(lockPath, MAX_LOCK_BYTES).catch((error) => {
    if (retryIfMissing && error?.code === "ENOENT") throw retryLockAcquisition();
    throw error;
  });
  if (!actual.equals(expected)) {
    throw new ProtocolError("WORKER_OPERATION_LOCK_INVALID", "operation lock changed ownership", true);
  }
}

function retryLockAcquisition() {
  return new ProtocolError(
    "WORKER_OPERATION_LOCK_RETRY",
    "operation lock changed during acquisition",
    true
  );
}

async function releaseOperationLock(directory, lockPath, ownership) {
  let failure = null;
  try {
    if (ownership.lost()) {
      throw new ProtocolError(
        "WORKER_OPERATION_LOCK_RELEASE_FAILED",
        "operation lock OS ownership was lost",
        true
      );
    }
    await verifyPublishedLock(directory, lockPath, ownership.bytes);
    await unlink(lockPath);
  } catch (error) {
    failure = error instanceof ProtocolError
      ? error
      : new ProtocolError(
          "WORKER_OPERATION_LOCK_RELEASE_FAILED",
          "operation lock release is uncertain",
          true
        );
  } finally {
    await ownership.close().catch(() => {
      failure ??= new ProtocolError(
        "WORKER_OPERATION_LOCK_RELEASE_FAILED",
        "operation lock OS ownership release is uncertain",
        true
      );
    });
  }
  if (failure != null) throw failure;
}

async function listenForOwnership(port, recovery = false) {
  return new Promise((resolve, reject) => {
    const server = createServer((socket) => socket.destroy());
    let lost = false;
    const initialError = (error) => {
      server.removeListener("listening", listening);
      if (recovery && ["EADDRINUSE", "EACCES"].includes(error?.code)) {
        resolve(null);
        return;
      }
      reject(new ProtocolError(
        "WORKER_OPERATION_LOCK_FAILED",
        "OS-held operation ownership could not be acquired",
        true
      ));
    };
    const listening = () => {
      server.removeListener("error", initialError);
      server.on("error", () => {
        lost = true;
      });
      server.unref();
      const address = server.address();
      if (address == null || typeof address === "string" || !Number.isInteger(address.port)) {
        server.close();
        reject(new ProtocolError(
          "WORKER_OPERATION_LOCK_FAILED",
          "OS-held operation ownership is invalid",
          true
        ));
        return;
      }
      resolve({
        port: address.port,
        lost: () => lost || !server.listening,
        close: () => closeServer(server)
      });
    };
    server.once("error", initialError);
    server.once("listening", listening);
    server.listen({ host: "127.0.0.1", port, exclusive: true });
  });
}

function closeServer(server) {
  return new Promise((resolve, reject) => {
    if (!server.listening) {
      resolve();
      return;
    }
    server.close((error) => error == null ? resolve() : reject(error));
  });
}

function parseLockMetadata(bytes) {
  let value;
  try {
    value = JSON.parse(bytes.toString("utf8"));
  } catch {
    throw new ProtocolError("WORKER_OPERATION_LOCK_INVALID", "operation lock metadata is invalid", true);
  }
  if (
    value == null ||
    Array.isArray(value) ||
    typeof value !== "object" ||
    Object.keys(value).sort().join("\n") !== "bootId\nownerId\nport\nschemaVersion" ||
    value.schemaVersion !== LOCK_SCHEMA_VERSION ||
    typeof value.bootId !== "string" ||
    !/^[0-9a-f]{32}$/u.test(value.bootId) ||
    typeof value.ownerId !== "string" ||
    !/^[0-9a-f]{32}$/u.test(value.ownerId) ||
    !Number.isInteger(value.port) ||
    value.port < 1 ||
    value.port > 65_535
  ) {
    throw new ProtocolError("WORKER_OPERATION_LOCK_INVALID", "operation lock metadata is invalid", true);
  }
  return value;
}

async function writePrivateNew(file, bytes, maximum) {
  if (bytes.length > maximum) {
    throw new ProtocolError("WORKER_OPERATION_LOCK_INVALID", "operation lock metadata is oversized", true);
  }
  const noFollow = constants.O_NOFOLLOW ?? 0;
  const handle = await open(
    file,
    constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | noFollow,
    0o600
  );
  try {
    await handle.writeFile(bytes);
    await handle.sync();
  } finally {
    await handle.close();
  }
}

function partitionKey(value) {
  if (typeof value !== "string" || !/^[0-9a-f]{64}$/u.test(value)) {
    throw new ProtocolError("WORKER_TENANT_PARTITION_INVALID", "tenant partition is invalid");
  }
  return value;
}

async function secureChildDirectory(trustedParent, childName) {
  const candidate = path.join(trustedParent, childName);
  if (path.dirname(candidate) !== trustedParent) {
    throw new ProtocolError("WORKER_STATE_PATH_INVALID", "state path escaped its trusted parent");
  }
  try {
    await mkdir(candidate, { mode: 0o700 });
  } catch (error) {
    if (error?.code !== "EEXIST") throw error;
  }
  return verifyExistingDirectory(trustedParent, candidate);
}

async function verifyExistingDirectory(trustedParent, candidate) {
  const metadata = await lstat(candidate).catch(() => null);
  if (metadata == null || !metadata.isDirectory() || metadata.isSymbolicLink()) {
    throw new ProtocolError("WORKER_STATE_PATH_INVALID", "state path contains a redirect");
  }
  const resolved = await realpath(candidate).catch(() => null);
  if (
    resolved == null ||
    normalizePath(resolved) !== normalizePath(candidate) ||
    normalizePath(path.dirname(resolved)) !== normalizePath(trustedParent)
  ) {
    throw new ProtocolError("WORKER_STATE_PATH_INVALID", "state path escaped its trusted parent");
  }
  return resolved;
}

async function readPrivateRegularFile(file, maximum) {
  const before = await lstat(file);
  if (!privateRegular(before, maximum)) {
    throw new ProtocolError("WORKER_STATE_CORRUPT", "operation state is not a bounded private file");
  }
  const noFollow = constants.O_NOFOLLOW ?? 0;
  const handle = await open(file, constants.O_RDONLY | noFollow);
  const chunks = [];
  let total = 0;
  let openedBefore;
  let openedAfter;
  try {
    openedBefore = await handle.stat();
    const buffer = Buffer.allocUnsafe(8192);
    while (true) {
      const { bytesRead } = await handle.read(buffer, 0, buffer.length, null);
      if (bytesRead === 0) break;
      total += bytesRead;
      if (total > maximum) {
        throw new ProtocolError("WORKER_STATE_CORRUPT", "operation state exceeds its bound");
      }
      chunks.push(Buffer.from(buffer.subarray(0, bytesRead)));
    }
    openedAfter = await handle.stat();
  } finally {
    await handle.close();
  }
  const after = await lstat(file);
  if (
    !privateRegular(after, maximum) ||
    total !== before.size ||
    total !== after.size ||
    total !== openedBefore.size ||
    total !== openedAfter.size ||
    !sameFile(before, openedBefore) ||
    !sameFile(openedBefore, openedAfter) ||
    !sameFile(openedAfter, after)
  ) {
    throw new ProtocolError("WORKER_STATE_CORRUPT", "operation state changed while being read");
  }
  return Buffer.concat(chunks, total);
}

function privateRegular(metadata, maximum) {
  if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.size > maximum) return false;
  return process.platform === "win32" || (metadata.mode & 0o077) === 0;
}

function sameFile(first, second) {
  if (first.dev === 0 || first.ino === 0 || second.dev === 0 || second.ino === 0) return true;
  return first.dev === second.dev && first.ino === second.ino;
}

function normalizePath(value) {
  const normalized = path.resolve(value);
  return process.platform === "win32" ? normalized.toLowerCase() : normalized;
}

async function syncDirectory(directory) {
  let handle;
  try {
    handle = await open(directory, constants.O_RDONLY);
    await handle.sync();
  } catch {
    // Directory fsync is not supported by every Windows/filesystem combination. The file itself
    // has already been fsynced and atomically renamed.
  } finally {
    await handle?.close().catch(() => {});
  }
}
