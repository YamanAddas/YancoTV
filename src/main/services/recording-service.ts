import { v4 as uuid } from 'uuid';
import { app, BrowserWindow, shell } from 'electron';
import path from 'path';
import fs from 'fs';
import { spawn, ChildProcessWithoutNullStreams } from 'child_process';
import log from 'electron-log/main';
import { getDb } from './db';
import { findFfmpegPath } from './ffmpeg-path';
import { getSetting, setSetting } from './settings-service';
import { IpcChannels } from '../../shared/ipc-channels';
import type {
  Recording,
  RecordingStatus,
  StartRecordingInput,
  RecordingProgress,
} from '../../shared/types/recording';

interface RecordingRow {
  id: string;
  content_id: string | null;
  title: string;
  stream_url: string;
  file_path: string;
  status: RecordingStatus;
  started_at: number;
  ended_at: number | null;
  duration_seconds: number | null;
  file_size_bytes: number | null;
  error: string | null;
}

function rowToRecording(row: RecordingRow): Recording {
  return {
    id: row.id,
    contentId: row.content_id ?? undefined,
    title: row.title,
    streamUrl: row.stream_url,
    filePath: row.file_path,
    status: row.status,
    startedAt: row.started_at,
    endedAt: row.ended_at ?? undefined,
    durationSeconds: row.duration_seconds ?? undefined,
    fileSizeBytes: row.file_size_bytes ?? undefined,
    error: row.error ?? undefined,
  };
}

const SETTING_KEY_DIR = 'recording_directory';
const SETTING_KEY_MAX_DURATION = 'recording_max_duration_minutes';
const activeProcesses = new Map<string, ChildProcessWithoutNullStreams>();

function getMaxDurationSeconds(): number {
  const raw = getSetting(SETTING_KEY_MAX_DURATION);
  const minutes = raw ? parseInt(raw, 10) : 240;
  if (!Number.isFinite(minutes) || minutes <= 0) return 0;
  return minutes * 60;
}

export function getRecordingsDirectory(): string {
  const saved = getSetting(SETTING_KEY_DIR);
  if (saved) return saved;
  const def = path.join(app.getPath('videos'), 'YancoTV');
  return def;
}

export function setRecordingsDirectory(dir: string): void {
  setSetting(SETTING_KEY_DIR, dir);
}

function ensureDir(dir: string): void {
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
}

function sanitizeFilename(name: string): string {
  return name.replace(/[<>:"/\\|?*\x00-\x1F]/g, '_').slice(0, 180);
}

function broadcast(channel: string, ...args: unknown[]): void {
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win.isDestroyed()) win.webContents.send(channel, ...args);
  }
}

/** Parse ffmpeg stderr for progress (out_time_ms / total_size). */
function parseProgressLine(buf: string): Partial<RecordingProgress> | null {
  const out: Partial<RecordingProgress> = {};
  const timeMatch = buf.match(/out_time_ms=(\d+)/);
  if (timeMatch) out.durationSeconds = Math.floor(Number(timeMatch[1]) / 1_000_000);
  const sizeMatch = buf.match(/total_size=(\d+)/);
  if (sizeMatch) out.fileSizeBytes = Number(sizeMatch[1]);
  return Object.keys(out).length ? out : null;
}

export function checkFfmpegAvailable(): boolean {
  return findFfmpegPath() !== null;
}

export function startRecording(
  input: StartRecordingInput,
): { ok: true; id: string } | { ok: false; error: string } {
  const ffmpeg = findFfmpegPath();
  if (!ffmpeg) return { ok: false, error: 'ffmpeg not found on this system' };

  const dir = getRecordingsDirectory();
  try {
    ensureDir(dir);
  } catch (err) {
    return { ok: false, error: `Could not create recordings directory: ${String(err)}` };
  }

  const id = uuid();
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const filename = `${sanitizeFilename(input.title)} - ${timestamp}.mp4`;
  const filePath = path.join(dir, filename);
  const startedAt = Date.now();

  const db = getDb();
  db.prepare(
    `INSERT INTO recordings (id, content_id, title, stream_url, file_path, status, started_at)
     VALUES (?, ?, ?, ?, ?, 'recording', ?)`,
  ).run(
    id,
    input.contentId ?? null,
    input.title,
    input.streamUrl,
    filePath,
    startedAt,
  );

  const args = [
    '-hide_banner',
    '-loglevel', 'error',
    '-progress', 'pipe:2',
    '-i', input.streamUrl,
    '-c', 'copy',
    '-bsf:a', 'aac_adtstoasc',
    '-f', 'mp4',
    '-movflags', '+faststart',
    '-y',
    filePath,
  ];

  let proc: ChildProcessWithoutNullStreams;
  try {
    proc = spawn(ffmpeg, args, { windowsHide: true });
  } catch (err) {
    db.prepare(
      `UPDATE recordings SET status = 'failed', ended_at = ?, error = ? WHERE id = ?`,
    ).run(Date.now(), String(err), id);
    return { ok: false, error: String(err) };
  }

  activeProcesses.set(id, proc);

  const maxDurationSeconds = getMaxDurationSeconds();
  let autoStopped = false;

  let stderrBuf = '';
  proc.stderr.on('data', (chunk: Buffer) => {
    stderrBuf += chunk.toString();
    // Keep only the tail to bound memory.
    if (stderrBuf.length > 16_384) stderrBuf = stderrBuf.slice(-8_192);
    const progress = parseProgressLine(stderrBuf);
    if (progress) {
      broadcast(IpcChannels.RECORDING_PROGRESS, {
        id,
        durationSeconds: progress.durationSeconds ?? 0,
        fileSizeBytes: progress.fileSizeBytes ?? 0,
      } satisfies RecordingProgress);

      // Auto-stop when the configured cap is reached. Let the row land as
      // 'completed' (not 'cancelled') since this is the user's intended limit.
      if (
        !autoStopped &&
        maxDurationSeconds > 0 &&
        (progress.durationSeconds ?? 0) >= maxDurationSeconds
      ) {
        autoStopped = true;
        log.info(`Recording ${id} hit duration cap (${maxDurationSeconds}s), stopping`);
        try {
          proc.stdin.write('q');
          proc.stdin.end();
        } catch {
          // ignored
        }
      }
    }
  });

  proc.on('error', (err) => {
    log.error(`Recording ${id} process error`, err);
  });

  proc.on('exit', (code, signal) => {
    activeProcesses.delete(id);
    const endedAt = Date.now();
    let size = 0;
    try {
      size = fs.existsSync(filePath) ? fs.statSync(filePath).size : 0;
    } catch {
      size = 0;
    }
    const durationSeconds = Math.floor((endedAt - startedAt) / 1000);

    // If the row is still 'recording' (not explicitly cancelled), decide based on exit.
    const current = db
      .prepare('SELECT status FROM recordings WHERE id = ?')
      .get(id) as { status: RecordingStatus } | undefined;

    let finalStatus: RecordingStatus;
    let errorMsg: string | null = null;
    if (current?.status === 'cancelled') {
      finalStatus = 'cancelled';
    } else if (code === 0 || (signal && signal !== 'SIGKILL')) {
      finalStatus = 'completed';
    } else {
      finalStatus = 'failed';
      errorMsg = `ffmpeg exited with code ${code}. ${stderrBuf.slice(-500)}`;
    }

    db.prepare(
      `UPDATE recordings
       SET status = ?, ended_at = ?, duration_seconds = ?, file_size_bytes = ?, error = ?
       WHERE id = ?`,
    ).run(finalStatus, endedAt, durationSeconds, size, errorMsg, id);

    broadcast(IpcChannels.RECORDING_STATUS, { id, status: finalStatus });
  });

  return { ok: true, id };
}

export function stopRecording(id: string): { ok: boolean; error?: string } {
  const proc = activeProcesses.get(id);
  if (!proc) {
    // No active process; mark row as cancelled if still recording.
    const db = getDb();
    db.prepare(
      `UPDATE recordings SET status = 'cancelled', ended_at = ? WHERE id = ? AND status = 'recording'`,
    ).run(Date.now(), id);
    return { ok: true };
  }
  // Mark as cancelled first so the exit handler doesn't flip it to 'failed'.
  getDb().prepare(`UPDATE recordings SET status = 'cancelled' WHERE id = ?`).run(id);
  // Gracefully stop ffmpeg with 'q' which finalizes the file; fall back to kill.
  try {
    proc.stdin.write('q');
    proc.stdin.end();
  } catch {
    // ignored
  }
  setTimeout(() => {
    if (activeProcesses.has(id)) {
      try {
        proc.kill('SIGKILL');
      } catch {
        // ignored
      }
    }
  }, 3000);
  return { ok: true };
}

export function listRecordings(): Recording[] {
  const rows = getDb()
    .prepare('SELECT * FROM recordings ORDER BY started_at DESC')
    .all() as RecordingRow[];
  return rows.map(rowToRecording);
}

export function deleteRecording(
  id: string,
  deleteFile: boolean,
): { ok: boolean; error?: string } {
  const db = getDb();
  const row = db
    .prepare('SELECT file_path, status FROM recordings WHERE id = ?')
    .get(id) as { file_path: string; status: RecordingStatus } | undefined;
  if (!row) return { ok: false, error: 'Recording not found' };
  if (row.status === 'recording') stopRecording(id);
  if (deleteFile && row.file_path && fs.existsSync(row.file_path)) {
    try {
      fs.unlinkSync(row.file_path);
    } catch (err) {
      return { ok: false, error: `Could not delete file: ${String(err)}` };
    }
  }
  db.prepare('DELETE FROM recordings WHERE id = ?').run(id);
  return { ok: true };
}

export function openRecordingsFolder(): void {
  const dir = getRecordingsDirectory();
  ensureDir(dir);
  shell.openPath(dir);
}

/** Called on app startup: mark any lingering 'recording' rows as failed. */
export function reconcileOnStartup(): void {
  const db = getDb();
  db.prepare(
    `UPDATE recordings SET status = 'failed', ended_at = ?, error = 'Interrupted (app closed during recording)'
     WHERE status = 'recording'`,
  ).run(Date.now());
}

/** Cancel everything on quit. */
export function stopAllOnQuit(): void {
  for (const id of activeProcesses.keys()) {
    stopRecording(id);
  }
}
