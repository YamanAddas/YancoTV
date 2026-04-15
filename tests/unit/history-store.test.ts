import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createTestDb, insertTestSource, insertTestContent } from './helpers/test-db';
import type Database from 'better-sqlite3';

let testDb: Database.Database;

vi.mock('../../src/main/services/db', () => ({
  getDb: () => testDb,
}));

let uuidCounter = 0;
vi.mock('uuid', () => ({
  v4: () => `hist-${++uuidCounter}`,
}));

import {
  getRecentlyWatched,
  getLastPosition,
  recordWatch,
  updatePosition,
  removeHistoryEntry,
  clearHistory,
} from '../../src/main/services/history-store';

describe('History Store', () => {
  beforeEach(() => {
    testDb = createTestDb();
    uuidCounter = 0;
    insertTestSource(testDb);
    insertTestContent(testDb, { id: 'ch-1', title: 'Channel One', type: 'live' });
    insertTestContent(testDb, { id: 'mov-1', title: 'Movie One', type: 'movie' });
  });

  it('starts with empty history', () => {
    expect(getRecentlyWatched()).toEqual([]);
  });

  it('records a watch entry', () => {
    const id = recordWatch('ch-1');
    expect(id).toBe('hist-1');
    const history = getRecentlyWatched();
    expect(history).toHaveLength(1);
    expect(history[0].contentId).toBe('ch-1');
    expect(history[0].positionSeconds).toBe(0);
  });

  it('records watch with episode ID', () => {
    // Insert an episode first
    testDb.prepare(
      `INSERT INTO episodes (id, content_id, season_number, episode_number, title, stream_url)
       VALUES ('ep-1', 'ch-1', 1, 1, 'Pilot', 'http://stream.com/ep1')`,
    ).run();

    const id = recordWatch('ch-1', 'ep-1');
    const history = getRecentlyWatched();
    expect(history[0].episodeId).toBe('ep-1');
    expect(id).toBeDefined();
  });

  it('updates playback position', () => {
    const id = recordWatch('mov-1');
    updatePosition(id, 1200, 7200);

    const pos = getLastPosition('mov-1');
    expect(pos).not.toBeNull();
    expect(pos!.positionSeconds).toBe(1200);
    expect(pos!.durationSeconds).toBe(7200);
  });

  it('getLastPosition returns null for unwatched content', () => {
    expect(getLastPosition('ch-1')).toBeNull();
  });

  it('getLastPosition returns latest position when multiple entries exist', () => {
    // Manually insert with explicit timestamps to guarantee ordering
    testDb.prepare(
      `INSERT INTO watch_history (id, content_id, position_seconds, watched_at)
       VALUES ('h-old', 'mov-1', 100, 1000)`,
    ).run();
    testDb.prepare(
      `INSERT INTO watch_history (id, content_id, position_seconds, watched_at)
       VALUES ('h-new', 'mov-1', 500, 2000)`,
    ).run();

    const pos = getLastPosition('mov-1');
    expect(pos!.positionSeconds).toBe(500);
  });

  it('removes a history entry', () => {
    const id = recordWatch('ch-1');
    removeHistoryEntry(id);
    expect(getRecentlyWatched()).toHaveLength(0);
  });

  it('clears all history', () => {
    recordWatch('ch-1');
    recordWatch('mov-1');
    clearHistory();
    expect(getRecentlyWatched()).toHaveLength(0);
  });

  it('getRecentlyWatched returns most recent first', () => {
    // Manually insert with explicit timestamps to guarantee ordering
    testDb.prepare(
      `INSERT INTO watch_history (id, content_id, position_seconds, watched_at)
       VALUES ('h1', 'ch-1', 0, 1000)`,
    ).run();
    testDb.prepare(
      `INSERT INTO watch_history (id, content_id, position_seconds, watched_at)
       VALUES ('h2', 'mov-1', 0, 2000)`,
    ).run();
    const history = getRecentlyWatched();
    expect(history[0].contentId).toBe('mov-1');
    expect(history[1].contentId).toBe('ch-1');
  });

  it('getRecentlyWatched respects limit', () => {
    recordWatch('ch-1');
    recordWatch('mov-1');
    const history = getRecentlyWatched(1);
    expect(history).toHaveLength(1);
  });

  it('returns content details in history entries', () => {
    recordWatch('ch-1');
    const entry = getRecentlyWatched()[0];
    expect(entry.content.title).toBe('Channel One');
    expect(entry.content.type).toBe('live');
    expect(entry.content.sourceId).toBe('src-1');
  });
});
