import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type Database from 'better-sqlite3';
import { createTestDb } from './helpers/test-db';

let testDb: Database.Database;
const sentEvents: Array<{ channel: string; data: unknown }> = [];

vi.mock('../../src/main/services/db', () => ({
  getDb: () => testDb,
}));

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}));

vi.mock('electron', () => ({
  BrowserWindow: {
    getAllWindows: () => [
      {
        isDestroyed: () => false,
        webContents: {
          send: (channel: string, data: unknown) => {
            sentEvents.push({ channel, data });
          },
        },
      },
    ],
  },
}));

import {
  setReminder,
  getReminderForProgramme,
  listReminders,
  listActiveReminders,
  removeReminder,
  removeReminderForProgramme,
  startReminderService,
  stopReminderService,
} from '../../src/main/services/reminder-service';

const NOW_SEC = 1_800_000_000; // 2027-01-15ish — arbitrary fixed epoch
const NOW_MS = NOW_SEC * 1000;

function baseInput(overrides: Partial<Parameters<typeof setReminder>[0]> = {}) {
  return {
    programmeId: 'prog-1',
    channelTvgId: 'ch.test.tv',
    title: 'Test Show',
    startTime: NOW_SEC + 600, // 10 min in future
    endTime: NOW_SEC + 1800,
    ...overrides,
  };
}

beforeEach(() => {
  testDb = createTestDb();
  sentEvents.length = 0;
  vi.useFakeTimers();
  vi.setSystemTime(NOW_MS);
});

afterEach(() => {
  stopReminderService();
  vi.useRealTimers();
});

describe('reminder-service — CRUD', () => {
  it('inserts a new reminder with default lead time (60s)', () => {
    const r = setReminder(baseInput());
    expect(r.id).toBeTruthy();
    expect(r.programmeId).toBe('prog-1');
    expect(r.leadSeconds).toBe(60);
    expect(r.fireAt).toBe(r.startTime - 60);
    expect(r.fired).toBe(false);
  });

  it('honors a custom lead time', () => {
    const r = setReminder(baseInput({ leadSeconds: 300 }));
    expect(r.leadSeconds).toBe(300);
    expect(r.fireAt).toBe(r.startTime - 300);
  });

  it('updates an existing reminder for the same programme_id instead of inserting', () => {
    const first = setReminder(baseInput({ leadSeconds: 60 }));
    const second = setReminder(baseInput({ leadSeconds: 600, title: 'Renamed' }));

    expect(second.id).toBe(first.id);
    expect(second.leadSeconds).toBe(600);
    expect(second.title).toBe('Renamed');
    expect(listReminders()).toHaveLength(1);
  });

  it('resets fired=false when updating an already-fired reminder', () => {
    const r = setReminder(baseInput());
    testDb.prepare('UPDATE reminders SET fired = 1 WHERE id = ?').run(r.id);
    setReminder(baseInput({ leadSeconds: 120 }));
    expect(getReminderForProgramme('prog-1')?.fired).toBe(false);
  });

  it('getReminderForProgramme returns null for unknown programme', () => {
    expect(getReminderForProgramme('nope')).toBeNull();
  });

  it('removeReminder returns true only when a row was deleted', () => {
    const r = setReminder(baseInput());
    expect(removeReminder(r.id)).toBe(true);
    expect(removeReminder(r.id)).toBe(false);
    expect(listReminders()).toHaveLength(0);
  });

  it('removeReminderForProgramme removes by programme id', () => {
    setReminder(baseInput());
    expect(removeReminderForProgramme('prog-1')).toBe(true);
    expect(removeReminderForProgramme('prog-1')).toBe(false);
  });

  it('listReminders returns every row ordered by start_time', () => {
    setReminder(baseInput({ programmeId: 'p-late', startTime: NOW_SEC + 7200 }));
    setReminder(baseInput({ programmeId: 'p-early', startTime: NOW_SEC + 600 }));
    const all = listReminders();
    expect(all.map((r) => r.programmeId)).toEqual(['p-early', 'p-late']);
  });

  it('listActiveReminders excludes fired and past-start rows', () => {
    setReminder(baseInput({ programmeId: 'p-upcoming', startTime: NOW_SEC + 600 }));
    const past = setReminder(baseInput({ programmeId: 'p-past', startTime: NOW_SEC - 600 }));
    const upcomingFired = setReminder(baseInput({ programmeId: 'p-fired', startTime: NOW_SEC + 1200 }));
    testDb.prepare('UPDATE reminders SET fired = 1 WHERE id = ?').run(upcomingFired.id);

    const active = listActiveReminders();
    expect(active.map((r) => r.programmeId)).toEqual(['p-upcoming']);
    // The past row is still present in listReminders().
    expect(listReminders().map((r) => r.programmeId)).toContain(past.programmeId);
  });
});

describe('reminder-service — scan/fire', () => {
  it('startReminderService fires reminders that are already overdue', () => {
    // fire_at is in the past (startTime - 60 == NOW_SEC - 60)
    setReminder(baseInput({ startTime: NOW_SEC, leadSeconds: 60 }));
    startReminderService();
    const fired = sentEvents.filter((e) => e.channel === 'reminders:fired');
    expect(fired).toHaveLength(1);
    expect((fired[0].data as { programmeId: string }).programmeId).toBe('prog-1');
  });

  it('schedules near-term reminders to fire at the right time', () => {
    // 30s in the future — inside the scan window
    setReminder(baseInput({ startTime: NOW_SEC + 90, leadSeconds: 60 }));
    startReminderService();
    expect(sentEvents.filter((e) => e.channel === 'reminders:fired')).toHaveLength(0);
    vi.advanceTimersByTime(30 * 1000);
    expect(sentEvents.filter((e) => e.channel === 'reminders:fired')).toHaveLength(1);
  });

  it('marks reminders as fired so they do not repeat', () => {
    setReminder(baseInput({ startTime: NOW_SEC, leadSeconds: 60 }));
    startReminderService();
    // Drain any timers from rescheduleNextFire
    vi.advanceTimersByTime(60 * 1000);
    // Re-scanning should find nothing new
    const count = sentEvents.filter((e) => e.channel === 'reminders:fired').length;
    vi.advanceTimersByTime(30 * 1000); // trigger interval scan
    expect(sentEvents.filter((e) => e.channel === 'reminders:fired').length).toBe(count);
    expect(getReminderForProgramme('prog-1')?.fired).toBe(true);
  });

  it('does not fire a reminder that was deleted before its scheduled time', () => {
    const r = setReminder(baseInput({ startTime: NOW_SEC + 90, leadSeconds: 60 }));
    startReminderService();
    removeReminder(r.id);
    vi.advanceTimersByTime(60 * 1000);
    expect(sentEvents.filter((e) => e.channel === 'reminders:fired')).toHaveLength(0);
  });

  it('stopReminderService clears timers so no further fires happen', () => {
    setReminder(baseInput({ startTime: NOW_SEC + 600, leadSeconds: 60 }));
    startReminderService();
    stopReminderService();
    vi.advanceTimersByTime(5 * 60 * 1000);
    expect(sentEvents.filter((e) => e.channel === 'reminders:fired')).toHaveLength(0);
  });
});
