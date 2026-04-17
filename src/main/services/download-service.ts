import { v4 as uuid } from 'uuid';
import { app, BrowserWindow, shell } from 'electron';
import path from 'path';
import fs from 'fs';
import dns from 'node:dns/promises';
import net from 'node:net';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import log from 'electron-log/main';
import { getDb } from './db';
import { getSetting, setSetting } from './settings-service';
import { fetchAssetsForDownload } from './asset-fetcher';
import { IpcChannels } from '../../shared/ipc-channels';
import type {
  Download,
  DownloadStatus,
  DownloadProgress,
  DownloadStatusChange,
  EnqueueDownloadInput,
} from '../../shared/types/download';

// ---------------------------------------------------------------------------
// Settings keys + defaults
// ---------------------------------------------------------------------------

const SETTING_KEY_DIR = 'download_directory';
const SETTING_KEY_MAX_CONCURRENT = 'download_max_concurrent';
const SETTING_KEY_ALLOW_PRIVATE_IPS = 'download_allow_private_ips';
const SETTING_KEY_MAX_FILE_SIZE_GB = 'download_max_file_size_gb';
const SETTING_KEY_USER_AGENT = 'network_user_agent';
const SETTING_KEY_TIMEOUT = 'network_connection_timeout';

const DEFAULT_MAX_CONCURRENT = 2;
const DEFAULT_MAX_FILE_SIZE_GB = 50;
const DEFAULT_CONNECT_TIMEOUT_SECONDS = 30;
const DEFAULT_UA = 'YancoTV/1.0';
const MAX_REDIRECTS = 5;
const PROGRESS_BROADCAST_INTERVAL_MS = 500;
// SQLite persist interval is deliberately slower than the UI broadcast so the
// hot loop isn't blocked by a synchronous write every 500 ms.
const PROGRESS_PERSIST_INTERVAL_MS = 2000;
// Retry budgets for mid-stream socket terminations (server closed early, proxy
// drop, connection reset, body timeout, etc.). Two counters so a flaky server
// that drops every few MB can still finish a large file:
//   • TOTAL caps the absolute retry count regardless of progress.
//   • NO_PROGRESS caps retries where the attempt made less than
//     PROGRESS_THRESHOLD_BYTES of forward progress — that's how we detect a
//     server that's permanently wedged vs one that's just flaky.
// Forward progress resets NO_PROGRESS to zero.
const MAX_TOTAL_RETRIES = 15;
const MAX_NO_PROGRESS_RETRIES = 4;
const PROGRESS_THRESHOLD_BYTES = 1 << 20; // 1 MiB
const MIDSTREAM_RETRY_BASE_MS = 750;
const MIDSTREAM_RETRY_MAX_MS = 10_000;
// 4 MiB write buffer — fewer drain waits + fewer syscalls vs the 16 KiB default.
// Large enough that a single undici chunk rarely triggers backpressure.
const WRITE_HIGHWATERMARK = 4 << 20;

// ---------------------------------------------------------------------------
// In-memory state
// ---------------------------------------------------------------------------

interface ActiveState {
  abort: AbortController;
  bytes: number;
  lastBroadcastAt: number;
  lastBroadcastBytes: number;
  bytesTotal?: number;
}
const active = new Map<string, ActiveState>();

// ---------------------------------------------------------------------------
// Row ↔ domain mapping
// ---------------------------------------------------------------------------

interface DownloadRow {
  id: string;
  content_id: string | null;
  episode_id: string | null;
  title: string;
  stream_url: string;
  file_path: string;
  status: DownloadStatus;
  queued_at: number;
  started_at: number | null;
  completed_at: number | null;
  bytes_downloaded: number;
  bytes_total: number | null;
  error: string | null;
  resumable: number;
}

function rowToDownload(row: DownloadRow): Download {
  return {
    id: row.id,
    contentId: row.content_id ?? undefined,
    episodeId: row.episode_id ?? undefined,
    title: row.title,
    streamUrl: row.stream_url,
    filePath: row.file_path,
    status: row.status,
    queuedAt: row.queued_at,
    startedAt: row.started_at ?? undefined,
    completedAt: row.completed_at ?? undefined,
    bytesDownloaded: row.bytes_downloaded,
    bytesTotal: row.bytes_total ?? undefined,
    error: row.error ?? undefined,
    resumable: row.resumable === 1,
  };
}

// ---------------------------------------------------------------------------
// Settings helpers
// ---------------------------------------------------------------------------

export function getDownloadsDirectory(): string {
  const saved = getSetting(SETTING_KEY_DIR);
  if (saved) return saved;
  return path.join(app.getPath('videos'), 'YancoTV', 'Downloads');
}

export function setDownloadsDirectory(dir: string): void {
  setSetting(SETTING_KEY_DIR, dir);
}

function getMaxConcurrent(): number {
  const raw = getSetting(SETTING_KEY_MAX_CONCURRENT);
  const n = raw ? parseInt(raw, 10) : DEFAULT_MAX_CONCURRENT;
  if (!Number.isFinite(n) || n < 1) return 1;
  if (n > 10) return 10;
  return n;
}

function getAllowPrivateIps(): boolean {
  return getSetting(SETTING_KEY_ALLOW_PRIVATE_IPS) === '1';
}

function getMaxFileSizeBytes(): number {
  const raw = getSetting(SETTING_KEY_MAX_FILE_SIZE_GB);
  const gb = raw ? parseFloat(raw) : DEFAULT_MAX_FILE_SIZE_GB;
  if (!Number.isFinite(gb) || gb <= 0) return DEFAULT_MAX_FILE_SIZE_GB * 1024 ** 3;
  return Math.floor(gb * 1024 ** 3);
}

function getConnectTimeoutMs(): number {
  const raw = getSetting(SETTING_KEY_TIMEOUT);
  const n = raw ? parseInt(raw, 10) : DEFAULT_CONNECT_TIMEOUT_SECONDS;
  if (!Number.isFinite(n) || n <= 0) return DEFAULT_CONNECT_TIMEOUT_SECONDS * 1000;
  return n * 1000;
}

function getUserAgent(): string {
  const raw = getSetting(SETTING_KEY_USER_AGENT);
  return raw && raw.trim() ? raw.trim() : DEFAULT_UA;
}

// ---------------------------------------------------------------------------
// Security — URL + SSRF + filesystem confinement
// ---------------------------------------------------------------------------

function validateUrl(raw: string): URL {
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    throw new Error('Invalid URL');
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') {
    throw new Error(`Only http/https URLs are allowed (got ${u.protocol})`);
  }
  return u;
}

/**
 * Block SSRF by rejecting hosts that resolve to loopback, private, link-local,
 * or cloud-metadata IPs. Users who intentionally download from a LAN source
 * can flip `download_allow_private_ips` to '1' in settings.
 */
async function assertHostAllowed(url: URL): Promise<void> {
  if (getAllowPrivateIps()) return;
  const host = url.hostname;

  // If the host is already a literal IP, check it directly.
  if (net.isIP(host)) {
    if (isBlockedIp(host)) {
      throw new Error(`Downloads to private/loopback addresses are blocked: ${host}`);
    }
    return;
  }

  let addrs: { address: string; family: number }[];
  try {
    addrs = await dns.lookup(host, { all: true });
  } catch (err) {
    throw new Error(`Could not resolve host ${host}: ${String(err)}`);
  }
  for (const a of addrs) {
    if (isBlockedIp(a.address)) {
      throw new Error(
        `Host ${host} resolves to a private/loopback address (${a.address}); blocked for safety`,
      );
    }
  }
}

function isBlockedIp(ip: string): boolean {
  // IPv4
  if (net.isIPv4(ip)) {
    const parts = ip.split('.').map((p) => parseInt(p, 10));
    const [a, b] = parts;
    if (a === 10) return true; // 10.0.0.0/8
    if (a === 127) return true; // loopback
    if (a === 169 && b === 254) return true; // link-local + metadata
    if (a === 172 && b >= 16 && b <= 31) return true; // 172.16.0.0/12
    if (a === 192 && b === 168) return true; // 192.168.0.0/16
    if (a === 0) return true; // 0.0.0.0/8
    if (a >= 224) return true; // multicast + reserved
    return false;
  }
  // IPv6 — reject obvious private/loopback/link-local
  const lower = ip.toLowerCase();
  if (lower === '::1') return true; // loopback
  if (lower === '::') return true;
  if (lower.startsWith('fe80:')) return true; // link-local
  if (lower.startsWith('fc') || lower.startsWith('fd')) return true; // ULA fc00::/7
  if (lower.startsWith('ff')) return true; // multicast
  return false;
}

function sanitizeFilename(name: string): string {
  // Strip control chars, path separators, and Windows-reserved chars.
  // eslint-disable-next-line no-control-regex
  let clean = name.replace(/[<>:"/\\|?*\x00-\x1F]/g, '_').trim();
  // Disallow reserved Windows device names
  const RESERVED = /^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)/i;
  if (RESERVED.test(clean)) clean = `_${clean}`;
  // Remove trailing dots/spaces (Windows rejects them)
  clean = clean.replace(/[. ]+$/, '');
  if (!clean) clean = 'download';
  return clean.slice(0, 180);
}

/**
 * Resolve a candidate path under `baseDir` and reject anything that escapes.
 * Any path traversal attempt (../ or absolute overrides) is refused.
 */
function confinePath(baseDir: string, filename: string): string {
  const safeBase = path.resolve(baseDir);
  const resolved = path.resolve(safeBase, filename);
  const rel = path.relative(safeBase, resolved);
  if (rel.startsWith('..') || path.isAbsolute(rel)) {
    throw new Error('Filename resolves outside downloads directory');
  }
  return resolved;
}

function ensureDir(dir: string): void {
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
}

// ---------------------------------------------------------------------------
// File naming — derive a sensible on-disk name
// ---------------------------------------------------------------------------

function extensionFromUrl(url: URL): string {
  const ext = path.extname(url.pathname).toLowerCase();
  if (/^\.[a-z0-9]{2,5}$/.test(ext)) return ext;
  return '.mp4';
}

function uniqueFilePath(dir: string, title: string, url: URL): string {
  const ext = extensionFromUrl(url);
  const base = sanitizeFilename(title);
  let candidate = `${base}${ext}`;
  let n = 1;
  while (fs.existsSync(path.join(dir, candidate)) || fs.existsSync(path.join(dir, `${candidate}.part`))) {
    candidate = `${base} (${n})${ext}`;
    n++;
  }
  return confinePath(dir, candidate);
}

// ---------------------------------------------------------------------------
// DB helpers
// ---------------------------------------------------------------------------

function getRow(id: string): DownloadRow | undefined {
  return getDb()
    .prepare('SELECT * FROM downloads WHERE id = ?')
    .get(id) as DownloadRow | undefined;
}

function setStatus(
  id: string,
  status: DownloadStatus,
  extra: Partial<{
    started_at: number;
    completed_at: number;
    bytes_downloaded: number;
    bytes_total: number;
    error: string | null;
    resumable: number;
  }> = {},
): void {
  const db = getDb();
  const fields: string[] = ['status = ?'];
  const values: unknown[] = [status];
  for (const [k, v] of Object.entries(extra)) {
    fields.push(`${k} = ?`);
    values.push(v);
  }
  values.push(id);
  db.prepare(`UPDATE downloads SET ${fields.join(', ')} WHERE id = ?`).run(...values);
}

function broadcast(channel: string, ...args: unknown[]): void {
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win.isDestroyed()) win.webContents.send(channel, ...args);
  }
}

function broadcastStatus(id: string, status: DownloadStatus, error?: string): void {
  const payload: DownloadStatusChange = { id, status };
  if (error) payload.error = error;
  broadcast(IpcChannels.DOWNLOAD_STATUS, payload);
}

function broadcastProgress(id: string, bytes: number, total: number | undefined, bps: number): void {
  const payload: DownloadProgress = {
    id,
    bytesDownloaded: bytes,
    bytesTotal: total,
    bytesPerSecond: bps,
  };
  broadcast(IpcChannels.DOWNLOAD_PROGRESS, payload);
}

// ---------------------------------------------------------------------------
// Public API — enqueue / pause / resume / cancel / remove / list
// ---------------------------------------------------------------------------

export function enqueueDownload(
  input: EnqueueDownloadInput,
): { ok: true; id: string } | { ok: false; error: string } {
  if (!input.title || !input.streamUrl) {
    return { ok: false, error: 'title and streamUrl are required' };
  }
  let url: URL;
  try {
    url = validateUrl(input.streamUrl);
  } catch (err) {
    return { ok: false, error: String((err as Error).message) };
  }

  const dir = getDownloadsDirectory();
  try {
    ensureDir(dir);
  } catch (err) {
    return { ok: false, error: `Could not create downloads directory: ${String(err)}` };
  }

  let filePath: string;
  try {
    filePath = uniqueFilePath(dir, input.title, url);
  } catch (err) {
    return { ok: false, error: String((err as Error).message) };
  }

  const id = uuid();
  getDb()
    .prepare(
      `INSERT INTO downloads
        (id, content_id, episode_id, title, stream_url, file_path, status, queued_at, bytes_downloaded, resumable)
       VALUES (?, ?, ?, ?, ?, ?, 'queued', ?, 0, 0)`,
    )
    .run(
      id,
      input.contentId ?? null,
      input.episodeId ?? null,
      input.title,
      input.streamUrl,
      filePath,
      Date.now(),
    );

  broadcastStatus(id, 'queued');
  processQueue();
  return { ok: true, id };
}

export function pauseDownload(id: string): { ok: boolean; error?: string } {
  const row = getRow(id);
  if (!row) return { ok: false, error: 'Download not found' };
  if (row.status === 'downloading') {
    const st = active.get(id);
    if (st) st.abort.abort();
    // The worker loop will observe the abort and transition to 'paused'.
    return { ok: true };
  }
  if (row.status === 'queued') {
    setStatus(id, 'paused');
    broadcastStatus(id, 'paused');
    return { ok: true };
  }
  return { ok: false, error: `Cannot pause a ${row.status} download` };
}

export function resumeDownload(id: string): { ok: boolean; error?: string } {
  const row = getRow(id);
  if (!row) return { ok: false, error: 'Download not found' };
  if (row.status !== 'paused' && row.status !== 'failed') {
    return { ok: false, error: `Cannot resume a ${row.status} download` };
  }
  setStatus(id, 'queued', { error: null });
  broadcastStatus(id, 'queued');
  processQueue();
  return { ok: true };
}

export function cancelDownload(id: string): { ok: boolean; error?: string } {
  const row = getRow(id);
  if (!row) return { ok: false, error: 'Download not found' };
  if (row.status === 'completed') {
    return { ok: false, error: 'Cannot cancel a completed download' };
  }
  const st = active.get(id);
  if (st) st.abort.abort();
  setStatus(id, 'cancelled', { completed_at: Date.now() });
  broadcastStatus(id, 'cancelled');
  // Clean up the partial file
  cleanupPartFile(row.file_path);
  processQueue();
  return { ok: true };
}

export function removeDownload(
  id: string,
  deleteFile: boolean,
): { ok: boolean; error?: string } {
  const row = getRow(id);
  if (!row) return { ok: false, error: 'Download not found' };
  // If active, stop it first
  if (row.status === 'downloading' || row.status === 'queued') {
    cancelDownload(id);
  }
  if (deleteFile) {
    try {
      if (fs.existsSync(row.file_path)) fs.unlinkSync(row.file_path);
    } catch (err) {
      log.warn(`Could not delete file ${row.file_path}:`, err);
    }
    cleanupPartFile(row.file_path);
  }
  getDb().prepare('DELETE FROM downloads WHERE id = ?').run(id);
  return { ok: true };
}

export function listDownloads(): Download[] {
  const rows = getDb()
    .prepare('SELECT * FROM downloads ORDER BY queued_at DESC')
    .all() as DownloadRow[];
  return rows.map(rowToDownload);
}

export function openDownloadsFolder(): void {
  const dir = getDownloadsDirectory();
  ensureDir(dir);
  shell.openPath(dir);
}

function cleanupPartFile(finalPath: string): void {
  const partPath = `${finalPath}.part`;
  try {
    if (fs.existsSync(partPath)) fs.unlinkSync(partPath);
  } catch (err) {
    log.warn(`Could not delete part file ${partPath}:`, err);
  }
}

// ---------------------------------------------------------------------------
// Queue worker
// ---------------------------------------------------------------------------

function processQueue(): void {
  const slots = getMaxConcurrent() - active.size;
  if (slots <= 0) return;
  const next = getDb()
    .prepare(`SELECT id FROM downloads WHERE status = 'queued' ORDER BY queued_at ASC LIMIT ?`)
    .all(slots) as { id: string }[];
  for (const { id } of next) {
    // Optimistically claim the slot so concurrent processQueue() calls don't re-pick.
    setStatus(id, 'downloading', { started_at: Date.now(), error: null });
    broadcastStatus(id, 'downloading');
    startDownloadWorker(id).catch((err) => {
      log.error(`Download worker ${id} crashed:`, err);
    });
  }
}

// Error codes undici / Node's net stack uses for transient socket failures
// that are safe to retry via Range resume (no data loss — we pick up from
// the last byte we persisted). Exported for tests.
export const RETRIABLE_ERROR_CODES = new Set([
  'UND_ERR_SOCKET',
  'UND_ERR_CONNECT_TIMEOUT',
  'UND_ERR_HEADERS_TIMEOUT',
  'UND_ERR_BODY_TIMEOUT',
  'UND_ERR_RESPONSE_STATUS_CODE', // e.g. 502/503/504 from an upstream proxy
  'ECONNRESET',
  'ECONNREFUSED',
  'ETIMEDOUT',
  'EPIPE',
  'EAI_AGAIN', // transient DNS failure
]);

const RETRIABLE_MESSAGE_FRAGMENTS = [
  'terminated',
  'other side closed',
  'socket hang up',
  'premature close',
  'network error',
];

/**
 * True when the error looks like a transient network failure that's safe to
 * retry via Range resume. Walks the full `cause` chain — undici often nests
 * the real SocketError two or three wrappers deep behind a `TypeError: fetch
 * failed`.
 *
 * Exported for unit testing.
 */
export function isRetriableStreamError(err: unknown): boolean {
  if (!err || typeof err !== 'object') return false;
  // A genuine user abort is never retriable. Callers treat connect-timeout
  // AbortErrors separately (the connect-timer fires its own controller).
  if ((err as { name?: string }).name === 'AbortError') return false;

  // Walk the cause chain — up to 8 levels is more than enough in practice and
  // bounds the loop so a cyclic cause can't hang us.
  let current: unknown = err;
  for (let depth = 0; depth < 8 && current && typeof current === 'object'; depth++) {
    const e = current as { message?: string; code?: string; cause?: unknown };
    const msg = String(e.message ?? '').toLowerCase();
    if (e.code && RETRIABLE_ERROR_CODES.has(e.code)) return true;
    for (const frag of RETRIABLE_MESSAGE_FRAGMENTS) {
      if (msg.includes(frag)) return true;
    }
    current = e.cause;
  }
  return false;
}

/**
 * Decide whether the worker should retry after a failed (or short) attempt.
 * Factored out so the branching is easy to test without spinning up sockets.
 * Exported for unit testing.
 */
export function shouldRetry(opts: {
  totalRetries: number;
  noProgressRetries: number;
}): boolean {
  return (
    opts.totalRetries < MAX_TOTAL_RETRIES &&
    opts.noProgressRetries < MAX_NO_PROGRESS_RETRIES
  );
}

function backoffDelay(totalRetries: number): number {
  return Math.min(MIDSTREAM_RETRY_BASE_MS * (totalRetries + 1), MIDSTREAM_RETRY_MAX_MS);
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

/**
 * Progress-tracking Transform. Pass bytes through unchanged while:
 *   - counting total bytes (exposed via `state`),
 *   - throwing if the cap is exceeded,
 *   - invoking `onTick` at broadcast-interval and `onPersist` at persist-interval.
 *
 * Extracted so it can be unit-tested without spinning up a real fetch.
 */
export interface ProgressState {
  bytes: number;
  startBytes: number;
}

export function createProgressTransform(opts: {
  state: ProgressState;
  maxBytes: number;
  isAborted: () => boolean;
  onTick: (bytes: number, bps: number) => void;
  onPersist: (bytes: number) => void;
  now?: () => number;
  broadcastIntervalMs?: number;
  persistIntervalMs?: number;
}): Transform {
  const now = opts.now ?? Date.now;
  const broadcastMs = opts.broadcastIntervalMs ?? PROGRESS_BROADCAST_INTERVAL_MS;
  const persistMs = opts.persistIntervalMs ?? PROGRESS_PERSIST_INTERVAL_MS;
  let lastBroadcastAt = now();
  let lastBroadcastBytes = opts.state.bytes;
  let lastPersistAt = now();
  return new Transform({
    // Match the file-writer highWaterMark so a single chunk can pass through
    // without the Transform becoming a backpressure bottleneck.
    highWaterMark: WRITE_HIGHWATERMARK,
    transform(chunk: Buffer, _enc, cb): void {
      if (opts.isAborted()) {
        cb();
        return;
      }
      const next = opts.state.bytes + chunk.length;
      if (next > opts.maxBytes) {
        cb(new Error('Download exceeded max allowed size'));
        return;
      }
      opts.state.bytes = next;

      const t = now();
      const dtBroadcast = t - lastBroadcastAt;
      if (dtBroadcast >= broadcastMs) {
        const bps = dtBroadcast > 0 ? ((next - lastBroadcastBytes) * 1000) / dtBroadcast : 0;
        opts.onTick(next, bps);
        lastBroadcastAt = t;
        lastBroadcastBytes = next;
      }
      if (t - lastPersistAt >= persistMs) {
        opts.onPersist(next);
        lastPersistAt = t;
      }
      cb(null, chunk);
    },
  });
}

function formatErr(err: unknown): string {
  const e = err as { message?: string; code?: string; cause?: { code?: string; message?: string } };
  const raw = e?.message ?? String(err);
  const causeMsg = e?.cause?.message;
  const code = e?.code ?? e?.cause?.code;
  let out = String(raw);
  if (causeMsg && !out.includes(causeMsg)) out += ` (${causeMsg})`;
  if (code && !out.includes(code)) out += ` [${code}]`;
  return out;
}

async function startDownloadWorker(id: string): Promise<void> {
  const row = getRow(id);
  if (!row) return;

  // Top-level controller — only aborted when the USER cancels/pauses.
  const userAbort = new AbortController();
  active.set(id, {
    abort: userAbort,
    bytes: row.bytes_downloaded,
    lastBroadcastAt: 0,
    lastBroadcastBytes: row.bytes_downloaded,
  });

  const partPath = `${row.file_path}.part`;
  let bytesWritten = row.bytes_downloaded;
  // Boxed so TS flow-analysis doesn't narrow it to `never` in the outer catch
  // after we've conditionally reassigned it in branches.
  const writerRef: { current: fs.WriteStream | null } = { current: null };

  // Retry budgets — see MAX_TOTAL_RETRIES / MAX_NO_PROGRESS_RETRIES.
  let totalRetries = 0;
  let noProgressRetries = 0;
  let lastProgressCheckpoint = bytesWritten;

  try {
    const url = validateUrl(row.stream_url);
    await assertHostAllowed(url);

    // If .part exists with bytes matching DB, attempt resume via Range.
    let resumeFrom = 0;
    if (fs.existsSync(partPath)) {
      const sz = fs.statSync(partPath).size;
      if (sz === row.bytes_downloaded && sz > 0) resumeFrom = sz;
      else {
        try {
          fs.unlinkSync(partPath);
        } catch {
          // ignored
        }
        resumeFrom = 0;
        bytesWritten = 0;
        lastProgressCheckpoint = 0;
      }
    }

    // The loop runs until we either succeed, hit a fatal error, or exhaust
    // both retry budgets. Every failed iteration is classified as
    // "made-progress" (resets noProgressRetries) or "no-progress"
    // (increments it); totalRetries is always incremented.
    // eslint-disable-next-line no-constant-condition
    while (true) {
      try {
        const { response, finalUrl } = await fetchWithRedirectsAndSsrfCheck(url, {
          userAbort,
          rangeStart: resumeFrom,
        });

        if (response.status === 416) {
          // Server says the requested range is unsatisfiable — likely because the
          // .part file is already the complete size. Treat as completed.
          response.body?.cancel().catch(() => undefined);
          if (fs.existsSync(partPath)) fs.renameSync(partPath, row.file_path);
          const finalSize = fs.existsSync(row.file_path) ? fs.statSync(row.file_path).size : 0;
          setStatus(id, 'completed', {
            completed_at: Date.now(),
            bytes_downloaded: finalSize,
            bytes_total: finalSize,
          });
          broadcastStatus(id, 'completed');
          void fetchAssetsForDownload({
            videoPath: row.file_path,
            contentId: row.content_id ?? undefined,
            episodeId: row.episode_id ?? undefined,
          }).catch((err) => log.warn(`Download ${id} asset fetch failed:`, err));
          return;
        }

        if (!response.ok && response.status !== 206) {
          throw new Error(`Server returned HTTP ${response.status}`);
        }

        const gotRange = response.status === 206;
        const advertisesRanges =
          response.headers.get('accept-ranges')?.toLowerCase() === 'bytes';
        const resumable = gotRange || advertisesRanges;

        // We asked for a Range but got a 200. If we already have bytes on disk,
        // we must restart from scratch — the stream starts at byte 0. This is
        // not a "retry" in the retry-budget sense; it's the server telling us
        // how it wants to serve this file, so don't count it against the cap.
        if (resumeFrom > 0 && !gotRange) {
          log.warn(
            `Server did not honour Range for ${finalUrl.href}; restarting from 0`,
          );
          response.body?.cancel().catch(() => undefined);
          try {
            fs.unlinkSync(partPath);
          } catch {
            // ignored
          }
          resumeFrom = 0;
          bytesWritten = 0;
          lastProgressCheckpoint = 0;
          continue;
        }

        // Total size — from Content-Range (when resumed) or Content-Length.
        let bytesTotal: number | undefined;
        const cr = response.headers.get('content-range');
        const cl = response.headers.get('content-length');
        if (cr) {
          const m = cr.match(/\/(\d+)\s*$/);
          if (m) bytesTotal = Number(m[1]);
        } else if (cl) {
          bytesTotal = Number(cl) + resumeFrom;
        }

        const maxBytes = getMaxFileSizeBytes();
        if (bytesTotal !== undefined && bytesTotal > maxBytes) {
          response.body?.cancel().catch(() => undefined);
          throw new Error(`File exceeds max size (${bytesTotal} > ${maxBytes} bytes)`);
        }

        setStatus(id, 'downloading', {
          ...(bytesTotal !== undefined ? { bytes_total: bytesTotal } : {}),
          resumable: resumable ? 1 : 0,
        });
        const st = active.get(id);
        if (st) st.bytesTotal = bytesTotal;

        writerRef.current = fs.createWriteStream(partPath, {
          flags: resumeFrom > 0 ? 'a' : 'w',
          highWaterMark: WRITE_HIGHWATERMARK,
        });
        const w = writerRef.current;
        if (!response.body) throw new Error('Response has no body');

        const body = Readable.fromWeb(
          response.body as unknown as Parameters<typeof Readable.fromWeb>[0],
        );

        const progressState: ProgressState = { bytes: bytesWritten, startBytes: bytesWritten };
        const progress = createProgressTransform({
          state: progressState,
          maxBytes,
          isAborted: () => userAbort.signal.aborted,
          onTick: (b, bps) => {
            broadcastProgress(id, b, bytesTotal, bps);
            const st2 = active.get(id);
            if (st2) st2.bytes = b;
          },
          onPersist: (b) => {
            setStatus(id, 'downloading', { bytes_downloaded: b });
          },
        });

        let streamError: unknown = null;
        try {
          // pipeline wires body → progress → writer with native backpressure,
          // auto-closes all streams, and rejects on any error (including
          // AbortError from the signal). Much less JS overhead per chunk than
          // a manual for-await loop, which translates directly to throughput.
          await pipeline(body, progress, w, { signal: userAbort.signal });
        } catch (streamErr) {
          streamError = streamErr;
          response.body?.cancel().catch(() => undefined);
        } finally {
          bytesWritten = progressState.bytes;
        }
        writerRef.current = null;

        // Persist the final byte count regardless of error — we always want
        // the DB row to reflect what's actually on disk for resume.
        setStatus(id, 'downloading', { bytes_downloaded: bytesWritten });

        if (userAbort.signal.aborted) {
          setStatus(id, 'paused', { bytes_downloaded: bytesWritten });
          broadcastStatus(id, 'paused');
          return;
        }

        const madeProgress =
          bytesWritten - lastProgressCheckpoint >= PROGRESS_THRESHOLD_BYTES;

        if (streamError) {
          // A connect timeout on the hop controller fires AbortError; only a
          // *user* abort short-circuits here.
          if ((streamError as Error).name === 'AbortError' && !userAbort.signal.aborted) {
            // fallthrough to the retriable path below
          } else if (!isRetriableStreamError(streamError)) {
            throw streamError;
          }
          totalRetries++;
          if (madeProgress) {
            noProgressRetries = 0;
            lastProgressCheckpoint = bytesWritten;
          } else {
            noProgressRetries++;
          }
          if (!shouldRetry({ totalRetries, noProgressRetries })) throw streamError;
          log.warn(
            `Download ${id} ${formatErr(streamError)}; retrying (total=${totalRetries}, noProgress=${noProgressRetries}) at byte ${bytesWritten}${bytesTotal ? `/${bytesTotal}` : ''}`,
          );
          await sleep(backoffDelay(totalRetries));
          resumeFrom = bytesWritten;
          continue;
        }

        // If Content-Length said there's more to come but the body ended
        // cleanly, retry from where we stopped. Still a mid-stream failure.
        if (bytesTotal !== undefined && bytesWritten < bytesTotal) {
          totalRetries++;
          if (madeProgress) {
            noProgressRetries = 0;
            lastProgressCheckpoint = bytesWritten;
          } else {
            noProgressRetries++;
          }
          if (!shouldRetry({ totalRetries, noProgressRetries })) {
            throw new Error(
              `Server closed connection at ${bytesWritten}/${bytesTotal} bytes after ${totalRetries} attempts`,
            );
          }
          log.warn(
            `Download ${id} ended short at ${bytesWritten}/${bytesTotal}; retrying (total=${totalRetries}, noProgress=${noProgressRetries})`,
          );
          await sleep(backoffDelay(totalRetries));
          resumeFrom = bytesWritten;
          continue;
        }

        // Success — atomically rename to final name.
        fs.renameSync(partPath, row.file_path);
        const finalSize = fs.statSync(row.file_path).size;
        setStatus(id, 'completed', {
          completed_at: Date.now(),
          bytes_downloaded: finalSize,
          bytes_total: finalSize,
        });
        broadcastStatus(id, 'completed');
        // Fire-and-forget the companion assets (poster, backdrop, .nfo,
        // provider subtitles, embedded-subtitle extraction via ffmpeg).
        // Never let it throw — the download itself is already 'completed'.
        void fetchAssetsForDownload({
          videoPath: row.file_path,
          contentId: row.content_id ?? undefined,
          episodeId: row.episode_id ?? undefined,
        })
          .then((r) => {
            const summary = [
              r.poster && 'poster',
              r.backdrop && 'backdrop',
              r.nfo && 'nfo',
              r.providerSubtitles.length ? `${r.providerSubtitles.length} provider-sub(s)` : null,
              r.extractedSubtitles.length ? `${r.extractedSubtitles.length} embedded-sub(s)` : null,
            ]
              .filter(Boolean)
              .join(', ');
            log.info(`Download ${id} assets: ${summary || 'none'}`);
            if (r.errors.length > 0) {
              log.warn(`Download ${id} asset warnings: ${r.errors.join(' | ')}`);
            }
          })
          .catch((err) => log.warn(`Download ${id} asset fetch failed:`, err));
        return;
      } catch (err) {
        // Only a genuine user-triggered abort short-circuits. A connect-timeout
        // also throws AbortError but leaves userAbort untouched — that one we
        // treat as a retriable network failure.
        if (userAbort.signal.aborted) throw err;

        if (writerRef.current) {
          try {
            writerRef.current.destroy();
          } catch {
            // ignored
          }
          writerRef.current = null;
        }

        const name = (err as Error)?.name;
        const canRetry = name === 'AbortError' || isRetriableStreamError(err);
        if (!canRetry) throw err;

        const madeProgress =
          bytesWritten - lastProgressCheckpoint >= PROGRESS_THRESHOLD_BYTES;
        totalRetries++;
        if (madeProgress) {
          noProgressRetries = 0;
          lastProgressCheckpoint = bytesWritten;
        } else {
          noProgressRetries++;
        }
        if (!shouldRetry({ totalRetries, noProgressRetries })) throw err;

        setStatus(id, 'downloading', { bytes_downloaded: bytesWritten });
        log.warn(
          `Download ${id} ${formatErr(err)}; retrying (total=${totalRetries}, noProgress=${noProgressRetries}) at byte ${bytesWritten}`,
        );
        await sleep(backoffDelay(totalRetries));
        resumeFrom = bytesWritten;
        // Loop continues — next iteration will try Range from resumeFrom. If
        // the server refuses with a 200, the block at the top resets to 0.
      }
    }
  } catch (err) {
    const aborted = (err as Error)?.name === 'AbortError' || userAbort.signal.aborted;
    try {
      if (writerRef.current) writerRef.current.destroy();
    } catch {
      // ignored
    }
    if (aborted) {
      setStatus(id, 'paused', { bytes_downloaded: bytesWritten });
      broadcastStatus(id, 'paused');
    } else {
      const msg = formatErr(err);
      log.error(`Download ${id} failed:`, msg);
      setStatus(id, 'failed', {
        bytes_downloaded: bytesWritten,
        error: msg,
      });
      broadcastStatus(id, 'failed', msg);
    }
  } finally {
    active.delete(id);
    processQueue();
  }
}

// ---------------------------------------------------------------------------
// HTTP — manual redirect handling so each hop is SSRF-validated.
// ---------------------------------------------------------------------------

async function fetchWithRedirectsAndSsrfCheck(
  url: URL,
  opts: { userAbort: AbortController; rangeStart: number },
): Promise<{ response: Response; finalUrl: URL }> {
  let current = url;
  const connectTimeout = getConnectTimeoutMs();

  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    await assertHostAllowed(current);

    // Per-hop controller scoped to the connection phase. We pipe the user's
    // main abort into it so user cancellation still works, but a connect
    // timeout here does NOT poison the main controller — it fires only this
    // one hop and leaves the outer retry loop free to try again.
    const hopAbort = new AbortController();
    const onUserAbort = (): void => hopAbort.abort(opts.userAbort.signal.reason);
    if (opts.userAbort.signal.aborted) hopAbort.abort(opts.userAbort.signal.reason);
    else opts.userAbort.signal.addEventListener('abort', onUserAbort, { once: true });

    const connectTimer = setTimeout(
      () => hopAbort.abort(new Error(`Connect timeout after ${connectTimeout}ms`)),
      connectTimeout,
    );

    const headers: Record<string, string> = {
      'User-Agent': getUserAgent(),
      Accept: '*/*',
      // Force identity so a gzip-compressed response can't desync byte counts
      // or silently truncate a video file.
      'Accept-Encoding': 'identity',
      Connection: 'keep-alive',
    };
    if (opts.rangeStart > 0) headers['Range'] = `bytes=${opts.rangeStart}-`;

    let response: Response;
    try {
      response = await fetch(current, {
        method: 'GET',
        headers,
        signal: hopAbort.signal,
        redirect: 'manual',
      });
    } finally {
      clearTimeout(connectTimer);
      opts.userAbort.signal.removeEventListener('abort', onUserAbort);
    }

    // Manual redirect handling — fetch('redirect:manual') returns either
    // an opaqueredirect (type='opaqueredirect', status=0) in browsers,
    // or an actual 3xx status in Node's undici. Handle both.
    const isRedirect =
      response.status === 301 ||
      response.status === 302 ||
      response.status === 303 ||
      response.status === 307 ||
      response.status === 308;

    if (!isRedirect) {
      return { response, finalUrl: current };
    }

    const location = response.headers.get('location');
    // Drain the redirect body so undici releases the socket.
    response.body?.cancel().catch(() => undefined);

    if (!location) throw new Error(`Redirect with no Location header (${response.status})`);
    const next = new URL(location, current);
    if (next.protocol !== 'http:' && next.protocol !== 'https:') {
      throw new Error(`Refusing to redirect to non-http(s) URL: ${next.protocol}`);
    }
    current = next;
  }

  throw new Error(`Too many redirects (>${MAX_REDIRECTS})`);
}

// ---------------------------------------------------------------------------
// Lifecycle — startup reconcile + shutdown
// ---------------------------------------------------------------------------

/**
 * On app startup, any row still in 'downloading' belongs to a crashed/closed
 * previous session. Transition it to 'paused' so the user can resume on demand.
 * Then kick the queue to auto-start anything still 'queued'.
 */
export function reconcileOnStartup(): void {
  const db = getDb();
  db.prepare(
    `UPDATE downloads SET status = 'paused' WHERE status = 'downloading'`,
  ).run();
  processQueue();
}

/**
 * On shutdown, abort all active downloads. The DB row stays 'downloading';
 * next startup will reconcile to 'paused' so the user can resume.
 */
export function stopAllOnQuit(): void {
  for (const [, st] of active) {
    st.abort.abort();
  }
  active.clear();
}

/**
 * Start the queue explicitly — useful after settings changes that may unblock
 * more concurrent downloads.
 */
export function kickQueue(): void {
  processQueue();
}

// ---------------------------------------------------------------------------
// Testing hook — expose internal security helpers to unit tests without
// widening the public API. Not for production callers.
// ---------------------------------------------------------------------------
export const __testing = {
  validateUrl,
  isBlockedIp,
  assertHostAllowed,
  sanitizeFilename,
  confinePath,
  extensionFromUrl,
};
