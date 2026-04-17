import { describe, it, expect, vi, beforeEach } from 'vitest';
import { EventEmitter } from 'events';
import path from 'path';
import os from 'os';

// ---------------------------------------------------------------------------
// Mocks must be declared before importing the service under test.
// ---------------------------------------------------------------------------

vi.mock('electron', () => ({
  app: {
    getPath: (name: string) =>
      name === 'videos' ? path.join(os.tmpdir(), 'yancotv-test-videos') : os.tmpdir(),
  },
  BrowserWindow: { getAllWindows: () => [] },
  shell: { openPath: vi.fn() },
}));

vi.mock('electron-log/main', () => ({
  default: { warn: vi.fn(), info: vi.fn(), error: vi.fn() },
}));

let currentDb: import('better-sqlite3').Database | null = null;
vi.mock('../../src/main/services/db', () => ({
  getDb: () => currentDb,
}));

const settingsStore: Record<string, string> = {};
vi.mock('../../src/main/services/settings-service', () => ({
  getSetting: (key: string) => settingsStore[key] ?? null,
  setSetting: (key: string, value: string) => {
    settingsStore[key] = value;
  },
  deleteSetting: (key: string) => {
    delete settingsStore[key];
  },
}));

// Always report ffmpeg available.
vi.mock('../../src/main/services/ffmpeg-path', () => ({
  findFfmpegPath: () => '/fake/ffmpeg',
}));

// Fake spawn: returns a ChildProcess-like EventEmitter with stdin/stderr streams,
// and stays "running" until we emit 'exit'.
class FakeChild extends EventEmitter {
  stdin = { write: vi.fn(), end: vi.fn() };
  stderr = new EventEmitter();
  kill = vi.fn();
}
const spawnedChildren: FakeChild[] = [];
vi.mock('child_process', () => ({
  spawn: vi.fn(() => {
    const child = new FakeChild();
    spawnedChildren.push(child);
    return child as unknown as import('child_process').ChildProcessWithoutNullStreams;
  }),
}));

import Database from 'better-sqlite3';
import {
  startRecording,
  getActiveRecordingCount,
} from '../../src/main/services/recording-service';

function makeDb(): Database.Database {
  const db = new Database(':memory:');
  db.exec(`
    CREATE TABLE recordings (
      id TEXT PRIMARY KEY,
      content_id TEXT,
      title TEXT NOT NULL,
      stream_url TEXT NOT NULL,
      file_path TEXT NOT NULL,
      status TEXT NOT NULL CHECK(status IN ('recording', 'completed', 'failed', 'cancelled')),
      started_at INTEGER NOT NULL,
      ended_at INTEGER,
      duration_seconds INTEGER,
      file_size_bytes INTEGER,
      error TEXT
    );
  `);
  return db;
}

describe('recording-service — concurrent limit', () => {
  beforeEach(() => {
    // Drain any active children from the previous test so activeProcesses
    // (module-level state in the service) goes back to zero.
    for (const child of spawnedChildren) child.emit('exit', 0, null);
    spawnedChildren.length = 0;
    for (const k of Object.keys(settingsStore)) delete settingsStore[k];
    currentDb = makeDb();
  });

  it('blocks a new recording once active count reaches the configured limit', () => {
    settingsStore['recording_max_concurrent'] = '2';

    const r1 = startRecording({ title: 'one', streamUrl: 'http://x/1' });
    const r2 = startRecording({ title: 'two', streamUrl: 'http://x/2' });
    expect(r1.ok).toBe(true);
    expect(r2.ok).toBe(true);
    expect(getActiveRecordingCount()).toBe(2);

    const r3 = startRecording({ title: 'three', streamUrl: 'http://x/3' });
    expect(r3.ok).toBe(false);
    if (!r3.ok) {
      expect(r3.error).toMatch(/Already recording 2\/2/);
    }
    // No row inserted for the rejected attempt.
    const rows = currentDb!.prepare('SELECT COUNT(*) as n FROM recordings').get() as { n: number };
    expect(rows.n).toBe(2);
  });

  it('defaults to a max of 3 when setting is absent', () => {
    const results = [
      startRecording({ title: 'a', streamUrl: 'http://x/a' }),
      startRecording({ title: 'b', streamUrl: 'http://x/b' }),
      startRecording({ title: 'c', streamUrl: 'http://x/c' }),
    ];
    expect(results.every((r) => r.ok)).toBe(true);
    expect(getActiveRecordingCount()).toBe(3);

    const r4 = startRecording({ title: 'd', streamUrl: 'http://x/d' });
    expect(r4.ok).toBe(false);
  });

  it('clamps an out-of-range setting into [1, 10]', () => {
    settingsStore['recording_max_concurrent'] = '9999';
    for (let i = 0; i < 10; i++) {
      const r = startRecording({ title: `t${i}`, streamUrl: `http://x/${i}` });
      expect(r.ok).toBe(true);
    }
    const r11 = startRecording({ title: 't11', streamUrl: 'http://x/11' });
    expect(r11.ok).toBe(false);
    expect(getActiveRecordingCount()).toBe(10);
  });

  it('frees a slot when a recording exits', () => {
    settingsStore['recording_max_concurrent'] = '1';
    const r1 = startRecording({ title: 'one', streamUrl: 'http://x/1' });
    expect(r1.ok).toBe(true);
    expect(getActiveRecordingCount()).toBe(1);

    const r2 = startRecording({ title: 'two', streamUrl: 'http://x/2' });
    expect(r2.ok).toBe(false);

    // Simulate ffmpeg exiting.
    spawnedChildren[0].emit('exit', 0, null);
    expect(getActiveRecordingCount()).toBe(0);

    const r3 = startRecording({ title: 'three', streamUrl: 'http://x/3' });
    expect(r3.ok).toBe(true);
  });
});
