import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createTestDb, insertTestSource, insertTestContent } from './helpers/test-db';
import type Database from 'better-sqlite3';

let testDb: Database.Database;

vi.mock('../../src/main/services/db', () => ({
  getDb: () => testDb,
}));

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}));

vi.mock('electron', () => ({
  BrowserWindow: { getAllWindows: () => [] },
}));

vi.mock('../../src/main/services/xmltv-parser', () => ({
  parseXmltv: vi.fn(),
}));

import {
  getNowNext,
  getNowNextBatch,
  getProgrammesForChannel,
  getGuideData,
  getEpgStats,
} from '../../src/main/services/epg-service';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

let idCounter = 0;

function insertProgramme(
  db: Database.Database,
  tvgId: string,
  title: string,
  startTime: number,
  endTime: number,
): void {
  idCounter++;
  db.prepare(
    'INSERT INTO epg_programmes (id, channel_tvg_id, title, start_time, end_time) VALUES (?, ?, ?, ?, ?)',
  ).run(`prog-${idCounter}`, tvgId, title, startTime, endTime);
}

// Fixed "now" — 2026-04-16 12:00:00 UTC in seconds
const NOW_SEC = Math.floor(new Date('2026-04-16T12:00:00Z').getTime() / 1000);
const NOW_MS = NOW_SEC * 1000;

describe('EPG Service — Query Functions', () => {
  beforeEach(() => {
    testDb = createTestDb();
    idCounter = 0;
    vi.spyOn(Date, 'now').mockReturnValue(NOW_MS);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // -------------------------------------------------------------------------
  // getNowNext
  // -------------------------------------------------------------------------

  describe('getNowNext', () => {
    it('returns now + next for a channel with matching programmes', () => {
      // "now" programme: 11:00 - 12:30 (contains NOW_SEC)
      insertProgramme(testDb, 'ch1', 'Morning Show', NOW_SEC - 3600, NOW_SEC + 1800);
      // "next" programme: 12:30 - 14:00
      insertProgramme(testDb, 'ch1', 'Afternoon Show', NOW_SEC + 1800, NOW_SEC + 7200);

      const result = getNowNext('ch1');

      expect(result.channelTvgId).toBe('ch1');
      expect(result.now).toBeDefined();
      expect(result.now!.title).toBe('Morning Show');
      expect(result.now!.startTime).toBe(NOW_SEC - 3600);
      expect(result.now!.endTime).toBe(NOW_SEC + 1800);
      expect(result.next).toBeDefined();
      expect(result.next!.title).toBe('Afternoon Show');
    });

    it('returns only next when no programme is currently airing', () => {
      // Future programme: starts in 30 minutes
      insertProgramme(testDb, 'ch1', 'Future Show', NOW_SEC + 1800, NOW_SEC + 5400);

      const result = getNowNext('ch1');

      expect(result.channelTvgId).toBe('ch1');
      expect(result.now).toBeUndefined();
      expect(result.next).toBeDefined();
      expect(result.next!.title).toBe('Future Show');
    });

    it('returns empty when no programmes exist', () => {
      const result = getNowNext('ch-nonexistent');

      expect(result.channelTvgId).toBe('ch-nonexistent');
      expect(result.now).toBeUndefined();
      expect(result.next).toBeUndefined();
    });
  });

  // -------------------------------------------------------------------------
  // getNowNextBatch
  // -------------------------------------------------------------------------

  describe('getNowNextBatch', () => {
    it('returns map of now/next for multiple channels', () => {
      // Channel A — currently airing + next
      insertProgramme(testDb, 'chA', 'News A', NOW_SEC - 1800, NOW_SEC + 1800);
      insertProgramme(testDb, 'chA', 'Sports A', NOW_SEC + 1800, NOW_SEC + 5400);
      // Channel B — only future
      insertProgramme(testDb, 'chB', 'Movie B', NOW_SEC + 600, NOW_SEC + 7200);

      const result = getNowNextBatch(['chA', 'chB']);

      expect(result['chA']).toBeDefined();
      expect(result['chA'].now).toBeDefined();
      expect(result['chA'].now!.title).toBe('News A');
      expect(result['chA'].next).toBeDefined();
      expect(result['chA'].next!.title).toBe('Sports A');

      expect(result['chB']).toBeDefined();
      expect(result['chB'].now).toBeUndefined();
      expect(result['chB'].next).toBeDefined();
      expect(result['chB'].next!.title).toBe('Movie B');
    });

    it('handles empty input', () => {
      const result = getNowNextBatch([]);
      expect(result).toEqual({});
    });
  });

  // -------------------------------------------------------------------------
  // getProgrammesForChannel
  // -------------------------------------------------------------------------

  describe('getProgrammesForChannel', () => {
    it('returns programmes within the given time range', () => {
      const rangeStart = NOW_SEC;
      const rangeEnd = NOW_SEC + 7200; // 2 hours

      // Overlaps range (starts before, ends inside)
      insertProgramme(testDb, 'ch1', 'Show 1', NOW_SEC - 1800, NOW_SEC + 1800);
      // Fully inside range
      insertProgramme(testDb, 'ch1', 'Show 2', NOW_SEC + 1800, NOW_SEC + 3600);
      // Overlaps range (starts inside, ends after)
      insertProgramme(testDb, 'ch1', 'Show 3', NOW_SEC + 5400, NOW_SEC + 9000);

      const result = getProgrammesForChannel('ch1', rangeStart, rangeEnd);

      expect(result).toHaveLength(3);
      expect(result[0].title).toBe('Show 1');
      expect(result[1].title).toBe('Show 2');
      expect(result[2].title).toBe('Show 3');
    });

    it('excludes programmes outside the range', () => {
      const rangeStart = NOW_SEC;
      const rangeEnd = NOW_SEC + 3600;

      // Entirely before range (ended before rangeStart)
      insertProgramme(testDb, 'ch1', 'Old Show', NOW_SEC - 7200, NOW_SEC - 3600);
      // Entirely after range (starts after rangeEnd)
      insertProgramme(testDb, 'ch1', 'Future Show', NOW_SEC + 7200, NOW_SEC + 10800);
      // Programme that ends exactly at rangeStart (end_time > rangeStart fails for equal)
      insertProgramme(testDb, 'ch1', 'Edge Show', NOW_SEC - 1800, NOW_SEC);

      const result = getProgrammesForChannel('ch1', rangeStart, rangeEnd);

      expect(result).toHaveLength(0);
    });
  });

  // -------------------------------------------------------------------------
  // getGuideData
  // -------------------------------------------------------------------------

  describe('getGuideData', () => {
    it('returns channels with their programmes', () => {
      const sourceId = insertTestSource(testDb);
      insertTestContent(testDb, {
        id: 'live-1',
        sourceId,
        type: 'live',
        title: 'BBC One',
        tvgId: 'bbc1',
        streamUrl: 'http://stream.com/bbc1',
      });

      const rangeStart = NOW_SEC;
      const rangeEnd = NOW_SEC + 7200;

      insertProgramme(testDb, 'bbc1', 'EastEnders', NOW_SEC, NOW_SEC + 1800);
      insertProgramme(testDb, 'bbc1', 'News at Ten', NOW_SEC + 1800, NOW_SEC + 3600);

      const result = getGuideData(rangeStart, rangeEnd);

      expect(result).toHaveLength(1);
      expect(result[0].tvgId).toBe('bbc1');
      expect(result[0].name).toBe('BBC One');
      expect(result[0].streamUrl).toBe('http://stream.com/bbc1');
      expect(result[0].programmes).toHaveLength(2);
      expect(result[0].programmes[0].title).toBe('EastEnders');
      expect(result[0].programmes[1].title).toBe('News at Ten');
    });

    it('filters by sourceId', () => {
      const src1 = insertTestSource(testDb, { id: 'src-a', name: 'Source A' });
      const src2 = insertTestSource(testDb, { id: 'src-b', name: 'Source B' });

      insertTestContent(testDb, {
        id: 'live-a',
        sourceId: src1,
        type: 'live',
        title: 'Channel A',
        tvgId: 'tvg-a',
        streamUrl: 'http://stream.com/a',
      });
      insertTestContent(testDb, {
        id: 'live-b',
        sourceId: src2,
        type: 'live',
        title: 'Channel B',
        tvgId: 'tvg-b',
        streamUrl: 'http://stream.com/b',
      });

      const rangeStart = NOW_SEC;
      const rangeEnd = NOW_SEC + 3600;

      insertProgramme(testDb, 'tvg-a', 'Show A', NOW_SEC, NOW_SEC + 1800);
      insertProgramme(testDb, 'tvg-b', 'Show B', NOW_SEC, NOW_SEC + 1800);

      const resultFiltered = getGuideData(rangeStart, rangeEnd, 'src-a');

      expect(resultFiltered).toHaveLength(1);
      expect(resultFiltered[0].tvgId).toBe('tvg-a');
      expect(resultFiltered[0].programmes[0].title).toBe('Show A');

      // Without filter returns both
      const resultAll = getGuideData(rangeStart, rangeEnd);
      expect(resultAll).toHaveLength(2);
    });
  });

  // -------------------------------------------------------------------------
  // getEpgStats
  // -------------------------------------------------------------------------

  describe('getEpgStats', () => {
    it('counts programmes and channels', () => {
      insertProgramme(testDb, 'ch1', 'Show 1', NOW_SEC, NOW_SEC + 1800);
      insertProgramme(testDb, 'ch1', 'Show 2', NOW_SEC + 1800, NOW_SEC + 3600);
      insertProgramme(testDb, 'ch2', 'Show 3', NOW_SEC, NOW_SEC + 1800);

      // Set last refreshed timestamp
      testDb
        .prepare("INSERT INTO settings (key, value) VALUES ('epg_last_refreshed', ?)")
        .run(String(NOW_MS));

      const stats = getEpgStats();

      expect(stats.programmeCount).toBe(3);
      expect(stats.channelCount).toBe(2);
      expect(stats.lastRefreshedAt).toBe(NOW_MS);
    });

    it('returns null lastRefreshedAt when no refresh has occurred', () => {
      const stats = getEpgStats();

      expect(stats.programmeCount).toBe(0);
      expect(stats.channelCount).toBe(0);
      expect(stats.lastRefreshedAt).toBeNull();
    });
  });
});
