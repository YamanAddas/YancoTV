/**
 * Reminder Service — alert the user when a scheduled programme is about to
 * start, optionally auto-tuning to the channel.
 *
 * Design:
 * - Reminders are a snapshot of the EPG row at set-time. If EPG refreshes
 *   and the programme slot changes, we still fire on the stored start_time
 *   rather than silently dropping the reminder.
 * - The fire_at column = start_time - lead_seconds, so the scanner only
 *   compares against `now` without per-row arithmetic.
 * - A short-lived setTimeout handles "fires within the next minute" so we
 *   deliver within a second or two of the target, and a 30s setInterval
 *   catches anything we missed (clock changes, sleep/wake, etc).
 */

import { BrowserWindow } from 'electron';
import log from 'electron-log/main';
import { randomUUID } from 'crypto';
import { getDb } from './db';
import { IpcChannels } from '../../shared/ipc-channels';

export interface Reminder {
  id: string;
  programmeId: string;
  channelTvgId: string;
  title: string;
  startTime: number;
  endTime: number;
  leadSeconds: number;
  fireAt: number;
  fired: boolean;
  createdAt: number;
}

export interface SetReminderInput {
  programmeId: string;
  channelTvgId: string;
  title: string;
  startTime: number;
  endTime: number;
  leadSeconds?: number;
}

interface ReminderRow {
  id: string;
  programme_id: string;
  channel_tvg_id: string;
  title: string;
  start_time: number;
  end_time: number;
  lead_seconds: number;
  fire_at: number;
  fired: number;
  created_at: number;
}

const DEFAULT_LEAD_SECONDS = 60; // fire 1 minute before start by default
const SCAN_INTERVAL_MS = 30_000; // every 30s, look for fires within the next 60s
const SCAN_WINDOW_MS = 60_000;

let scanTimer: ReturnType<typeof setInterval> | null = null;
let nextFireTimeout: ReturnType<typeof setTimeout> | null = null;

function rowToReminder(row: ReminderRow): Reminder {
  return {
    id: row.id,
    programmeId: row.programme_id,
    channelTvgId: row.channel_tvg_id,
    title: row.title,
    startTime: row.start_time,
    endTime: row.end_time,
    leadSeconds: row.lead_seconds,
    fireAt: row.fire_at,
    fired: row.fired === 1,
    createdAt: row.created_at,
  };
}

function emitToRenderer(channel: string, data: unknown): void {
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win.isDestroyed()) {
      win.webContents.send(channel, data);
    }
  }
}

export function listReminders(): Reminder[] {
  const db = getDb();
  const rows = db
    .prepare(
      `SELECT id, programme_id, channel_tvg_id, title, start_time, end_time,
              lead_seconds, fire_at, fired, created_at
       FROM reminders
       ORDER BY start_time ASC`,
    )
    .all() as ReminderRow[];
  return rows.map(rowToReminder);
}

/**
 * Return only unfired upcoming reminders — what the UI needs to show a
 * "bell" badge on programme cells.
 */
export function listActiveReminders(): Reminder[] {
  const db = getDb();
  const now = Math.floor(Date.now() / 1000);
  const rows = db
    .prepare(
      `SELECT id, programme_id, channel_tvg_id, title, start_time, end_time,
              lead_seconds, fire_at, fired, created_at
       FROM reminders
       WHERE fired = 0 AND start_time > ?
       ORDER BY start_time ASC`,
    )
    .all(now) as ReminderRow[];
  return rows.map(rowToReminder);
}

export function getReminderForProgramme(programmeId: string): Reminder | null {
  const db = getDb();
  const row = db
    .prepare(
      `SELECT id, programme_id, channel_tvg_id, title, start_time, end_time,
              lead_seconds, fire_at, fired, created_at
       FROM reminders
       WHERE programme_id = ?
       LIMIT 1`,
    )
    .get(programmeId) as ReminderRow | undefined;
  return row ? rowToReminder(row) : null;
}

export function setReminder(input: SetReminderInput): Reminder {
  const db = getDb();
  const leadSeconds = input.leadSeconds ?? DEFAULT_LEAD_SECONDS;
  const fireAt = input.startTime - leadSeconds;
  const existing = getReminderForProgramme(input.programmeId);

  if (existing) {
    // Update an existing reminder — lets the user change lead time without
    // leaving the overlay.
    db.prepare(
      `UPDATE reminders
       SET channel_tvg_id = ?, title = ?, start_time = ?, end_time = ?,
           lead_seconds = ?, fire_at = ?, fired = 0
       WHERE id = ?`,
    ).run(
      input.channelTvgId,
      input.title,
      input.startTime,
      input.endTime,
      leadSeconds,
      fireAt,
      existing.id,
    );
    rescheduleNextFire();
    return {
      ...existing,
      channelTvgId: input.channelTvgId,
      title: input.title,
      startTime: input.startTime,
      endTime: input.endTime,
      leadSeconds,
      fireAt,
      fired: false,
    };
  }

  const reminder: Reminder = {
    id: randomUUID(),
    programmeId: input.programmeId,
    channelTvgId: input.channelTvgId,
    title: input.title,
    startTime: input.startTime,
    endTime: input.endTime,
    leadSeconds,
    fireAt,
    fired: false,
    createdAt: Math.floor(Date.now() / 1000),
  };

  db.prepare(
    `INSERT INTO reminders
     (id, programme_id, channel_tvg_id, title, start_time, end_time,
      lead_seconds, fire_at, fired, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)`,
  ).run(
    reminder.id,
    reminder.programmeId,
    reminder.channelTvgId,
    reminder.title,
    reminder.startTime,
    reminder.endTime,
    reminder.leadSeconds,
    reminder.fireAt,
    reminder.createdAt,
  );

  rescheduleNextFire();
  return reminder;
}

export function removeReminder(id: string): boolean {
  const db = getDb();
  const res = db.prepare('DELETE FROM reminders WHERE id = ?').run(id);
  rescheduleNextFire();
  return res.changes > 0;
}

export function removeReminderForProgramme(programmeId: string): boolean {
  const db = getDb();
  const res = db.prepare('DELETE FROM reminders WHERE programme_id = ?').run(programmeId);
  rescheduleNextFire();
  return res.changes > 0;
}

/**
 * Fire all reminders whose fire_at is within the next SCAN_WINDOW_MS. Each
 * fired reminder emits a push event to the renderer and gets marked as fired
 * so it doesn't repeat.
 */
function scanAndFire(): void {
  const db = getDb();
  const now = Math.floor(Date.now() / 1000);
  const upperBound = now + SCAN_WINDOW_MS / 1000;

  const due = db
    .prepare(
      `SELECT id, programme_id, channel_tvg_id, title, start_time, end_time,
              lead_seconds, fire_at, fired, created_at
       FROM reminders
       WHERE fired = 0 AND fire_at <= ?`,
    )
    .all(upperBound) as ReminderRow[];

  if (due.length === 0) return;

  const markFired = db.prepare('UPDATE reminders SET fired = 1 WHERE id = ?');

  for (const row of due) {
    const reminder = rowToReminder(row);
    const delayMs = Math.max(0, (reminder.fireAt - now) * 1000);

    if (delayMs === 0) {
      // Already due — fire immediately.
      markFired.run(reminder.id);
      emitToRenderer(IpcChannels.REMINDERS_FIRED, reminder);
    } else {
      // Due within the scan window — schedule a short-lived setTimeout so
      // we fire close to the actual target second.
      setTimeout(() => {
        const still = db
          .prepare('SELECT fired FROM reminders WHERE id = ?')
          .get(reminder.id) as { fired: number } | undefined;
        if (!still || still.fired === 1) return;
        markFired.run(reminder.id);
        emitToRenderer(IpcChannels.REMINDERS_FIRED, reminder);
      }, delayMs);
    }
  }
}

/**
 * Clear any scheduled near-term setTimeout and re-arm based on the earliest
 * unfired reminder. Called on service start and whenever reminders are added
 * or removed.
 */
function rescheduleNextFire(): void {
  if (nextFireTimeout) {
    clearTimeout(nextFireTimeout);
    nextFireTimeout = null;
  }

  const db = getDb();
  const now = Math.floor(Date.now() / 1000);

  const next = db
    .prepare(
      `SELECT fire_at FROM reminders
       WHERE fired = 0 AND fire_at > ?
       ORDER BY fire_at ASC
       LIMIT 1`,
    )
    .get(now) as { fire_at: number } | undefined;

  if (!next) return;

  const delayMs = Math.min((next.fire_at - now) * 1000, SCAN_WINDOW_MS);
  if (delayMs <= 0) {
    scanAndFire();
    return;
  }

  nextFireTimeout = setTimeout(() => {
    scanAndFire();
    rescheduleNextFire();
  }, delayMs);
}

export function startReminderService(): void {
  stopReminderService();
  log.info('Reminder service starting');

  // Fire any reminders that came due while the app was closed/asleep so the
  // user still gets a late-but-accurate toast instead of silently missing it.
  scanAndFire();
  rescheduleNextFire();

  scanTimer = setInterval(() => {
    scanAndFire();
    rescheduleNextFire();
  }, SCAN_INTERVAL_MS);
}

export function stopReminderService(): void {
  if (scanTimer) {
    clearInterval(scanTimer);
    scanTimer = null;
  }
  if (nextFireTimeout) {
    clearTimeout(nextFireTimeout);
    nextFireTimeout = null;
  }
}
