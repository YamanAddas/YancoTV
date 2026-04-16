import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createTestDb, insertTestSource, insertTestContent } from './helpers/test-db';
import type Database from 'better-sqlite3';

let testDb: Database.Database;

vi.mock('../../src/main/services/db', () => ({
  getDb: () => testDb,
  dropFtsTriggers: () => {},
  restoreFtsTriggers: () => {},
  rebuildFtsIndex: () => {},
}));

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}));

let uuidCounter = 0;
vi.mock('crypto', () => ({
  randomUUID: () => `uuid-${++uuidCounter}`,
}));

import {
  getContentByType,
  getContentByTypeMerged,
  getCategories,
  searchContent,
  getContentCountByType,
  getEpisodes,
  getContentById,
  getRelatedContent,
} from '../../src/main/services/content-store';

describe('Content Store', () => {
  beforeEach(() => {
    testDb = createTestDb();
    uuidCounter = 0;
    insertTestSource(testDb);
  });

  // ---- getContentByType ----

  describe('getContentByType', () => {
    it('returns empty array when no content exists', () => {
      const result = getContentByType('live');
      expect(result).toEqual([]);
    });

    it('returns content of the requested type only', () => {
      insertTestContent(testDb, { id: 'ch-1', title: 'Live Channel', type: 'live' });
      insertTestContent(testDb, { id: 'mov-1', title: 'A Movie', type: 'movie' });
      insertTestContent(testDb, { id: 'ch-2', title: 'Another Channel', type: 'live' });

      const live = getContentByType('live');
      expect(live).toHaveLength(2);
      expect(live.every((c) => c.type === 'live')).toBe(true);

      const movies = getContentByType('movie');
      expect(movies).toHaveLength(1);
      expect(movies[0].id).toBe('mov-1');
    });

    it('filters by sourceId when provided', () => {
      insertTestSource(testDb, { id: 'src-2', name: 'Source Two' });
      insertTestContent(testDb, { id: 'ch-1', title: 'Channel Src1', type: 'live', sourceId: 'src-1' });
      insertTestContent(testDb, { id: 'ch-2', title: 'Channel Src2', type: 'live', sourceId: 'src-2' });

      const result = getContentByType('live', 'src-1');
      expect(result).toHaveLength(1);
      expect(result[0].id).toBe('ch-1');

      const result2 = getContentByType('live', 'src-2');
      expect(result2).toHaveLength(1);
      expect(result2[0].id).toBe('ch-2');
    });

    it('sorts by provider order (sort_order ASC) by default', () => {
      // Insert with explicit sort_order values
      testDb.prepare(
        `INSERT INTO content (id, source_id, type, title, stream_url, sort_order, created_at)
         VALUES ('ch-a', 'src-1', 'live', 'Zebra', 'http://s/1', 2, 1000)`,
      ).run();
      testDb.prepare(
        `INSERT INTO content (id, source_id, type, title, stream_url, sort_order, created_at)
         VALUES ('ch-b', 'src-1', 'live', 'Alpha', 'http://s/2', 1, 2000)`,
      ).run();

      const result = getContentByType('live', 'src-1', 'provider');
      expect(result[0].title).toBe('Alpha'); // sort_order 1
      expect(result[1].title).toBe('Zebra'); // sort_order 2
    });

    it('sorts by name ascending', () => {
      insertTestContent(testDb, { id: 'c-1', title: 'Zebra Channel', type: 'live' });
      insertTestContent(testDb, { id: 'c-2', title: 'Alpha Channel', type: 'live' });
      insertTestContent(testDb, { id: 'c-3', title: 'Middle Channel', type: 'live' });

      const result = getContentByType('live', 'src-1', 'name-asc');
      expect(result[0].title).toBe('Alpha Channel');
      expect(result[2].title).toBe('Zebra Channel');
    });

    it('sorts by name descending', () => {
      insertTestContent(testDb, { id: 'c-1', title: 'Zebra Channel', type: 'live' });
      insertTestContent(testDb, { id: 'c-2', title: 'Alpha Channel', type: 'live' });

      const result = getContentByType('live', 'src-1', 'name-desc');
      expect(result[0].title).toBe('Zebra Channel');
      expect(result[1].title).toBe('Alpha Channel');
    });

    it('sorts by recent (created_at DESC)', () => {
      testDb.prepare(
        `INSERT INTO content (id, source_id, type, title, stream_url, sort_order, created_at)
         VALUES ('old', 'src-1', 'movie', 'Old Movie', 'http://s/1', 0, 1000)`,
      ).run();
      testDb.prepare(
        `INSERT INTO content (id, source_id, type, title, stream_url, sort_order, created_at)
         VALUES ('new', 'src-1', 'movie', 'New Movie', 'http://s/2', 1, 9000)`,
      ).run();

      const result = getContentByType('movie', 'src-1', 'recent');
      expect(result[0].title).toBe('New Movie');
      expect(result[1].title).toBe('Old Movie');
    });

    it('sorts by group then name', () => {
      insertTestContent(testDb, { id: 'c-1', title: 'Zebra', type: 'live', groupName: 'Sports' });
      insertTestContent(testDb, { id: 'c-2', title: 'Alpha', type: 'live', groupName: 'News' });
      insertTestContent(testDb, { id: 'c-3', title: 'Beta', type: 'live', groupName: 'News' });

      const result = getContentByType('live', 'src-1', 'group');
      // News comes before Sports alphabetically
      expect(result[0].title).toBe('Alpha');
      expect(result[1].title).toBe('Beta');
      expect(result[2].title).toBe('Zebra');
    });
  });

  // ---- getContentByTypeMerged ----

  describe('getContentByTypeMerged', () => {
    beforeEach(() => {
      // Set up two sources with different priorities
      insertTestSource(testDb, { id: 'src-2', name: 'Source Two' });
      // src-1 has priority 0 (default), set src-2 to priority 1
      testDb.prepare('UPDATE sources SET priority = 0 WHERE id = ?').run('src-1');
      testDb.prepare('UPDATE sources SET priority = 1 WHERE id = ?').run('src-2');
    });

    it('returns content from multiple sources', () => {
      insertTestContent(testDb, { id: 'ch-1', title: 'Channel A', type: 'live', sourceId: 'src-1', streamUrl: 'http://a' });
      insertTestContent(testDb, { id: 'ch-2', title: 'Channel B', type: 'live', sourceId: 'src-2', streamUrl: 'http://b' });

      const result = getContentByTypeMerged('live');
      expect(result).toHaveLength(2);
    });

    it('deduplicates by stream_url keeping highest priority source (lowest number)', () => {
      // Same stream URL in both sources
      insertTestContent(testDb, { id: 'ch-1', title: 'From Priority Source', type: 'live', sourceId: 'src-1', streamUrl: 'http://shared' });
      insertTestContent(testDb, { id: 'ch-2', title: 'From Lower Priority', type: 'live', sourceId: 'src-2', streamUrl: 'http://shared' });

      const result = getContentByTypeMerged('live');
      expect(result).toHaveLength(1);
      expect(result[0].title).toBe('From Priority Source');
      expect(result[0].sourceId).toBe('src-1');
    });

    it('does not dedup empty stream URLs (series parents)', () => {
      insertTestContent(testDb, { id: 's-1', title: 'Series A', type: 'series', sourceId: 'src-1', streamUrl: '' });
      insertTestContent(testDb, { id: 's-2', title: 'Series B', type: 'series', sourceId: 'src-2', streamUrl: '' });

      const result = getContentByTypeMerged('series');
      expect(result).toHaveLength(2);
    });

    it('applies sort with table alias correctly', () => {
      insertTestContent(testDb, { id: 'ch-1', title: 'Zebra', type: 'live', sourceId: 'src-1', streamUrl: 'http://z' });
      insertTestContent(testDb, { id: 'ch-2', title: 'Alpha', type: 'live', sourceId: 'src-1', streamUrl: 'http://a' });

      const result = getContentByTypeMerged('live', 'name-asc');
      expect(result[0].title).toBe('Alpha');
      expect(result[1].title).toBe('Zebra');
    });

    it('applies name-desc sort with table alias', () => {
      insertTestContent(testDb, { id: 'ch-1', title: 'Zebra', type: 'live', sourceId: 'src-1', streamUrl: 'http://z' });
      insertTestContent(testDb, { id: 'ch-2', title: 'Alpha', type: 'live', sourceId: 'src-1', streamUrl: 'http://a' });

      const result = getContentByTypeMerged('live', 'name-desc');
      expect(result[0].title).toBe('Zebra');
      expect(result[1].title).toBe('Alpha');
    });

    it('applies group sort with table alias', () => {
      insertTestContent(testDb, { id: 'ch-1', title: 'Zebra', type: 'live', sourceId: 'src-1', streamUrl: 'http://z', groupName: 'Sports' });
      insertTestContent(testDb, { id: 'ch-2', title: 'Alpha', type: 'live', sourceId: 'src-1', streamUrl: 'http://a', groupName: 'News' });

      const result = getContentByTypeMerged('live', 'group');
      expect(result[0].groupName).toBe('News');
      expect(result[1].groupName).toBe('Sports');
    });
  });

  // ---- getCategories ----

  describe('getCategories', () => {
    it('returns empty array when no content', () => {
      expect(getCategories('live')).toEqual([]);
    });

    it('returns distinct group names for a type', () => {
      insertTestContent(testDb, { id: 'c-1', type: 'live', groupName: 'Sports' });
      insertTestContent(testDb, { id: 'c-2', type: 'live', groupName: 'News' });
      insertTestContent(testDb, { id: 'c-3', type: 'live', groupName: 'Sports' }); // duplicate

      const cats = getCategories('live');
      expect(cats).toHaveLength(2);
      expect(cats).toContain('Sports');
      expect(cats).toContain('News');
    });

    it('excludes null group names', () => {
      insertTestContent(testDb, { id: 'c-1', type: 'movie', groupName: 'Action' });
      insertTestContent(testDb, { id: 'c-2', type: 'movie' }); // no group

      const cats = getCategories('movie');
      expect(cats).toHaveLength(1);
      expect(cats[0]).toBe('Action');
    });

    it('only returns categories for the requested type', () => {
      insertTestContent(testDb, { id: 'c-1', type: 'live', groupName: 'Sports' });
      insertTestContent(testDb, { id: 'c-2', type: 'movie', groupName: 'Action' });

      expect(getCategories('live')).toEqual(['Sports']);
      expect(getCategories('movie')).toEqual(['Action']);
    });

    it('returns categories in alphabetical order', () => {
      insertTestContent(testDb, { id: 'c-1', type: 'live', groupName: 'Zebra' });
      insertTestContent(testDb, { id: 'c-2', type: 'live', groupName: 'Alpha' });
      insertTestContent(testDb, { id: 'c-3', type: 'live', groupName: 'Middle' });

      const cats = getCategories('live');
      expect(cats).toEqual(['Alpha', 'Middle', 'Zebra']);
    });
  });

  // ---- searchContent ----

  describe('searchContent', () => {
    beforeEach(() => {
      // FTS5 is not available in test DB, so search will fall back to LIKE
      insertTestContent(testDb, { id: 'ch-1', title: 'BBC News Live', type: 'live', groupName: 'News' });
      insertTestContent(testDb, { id: 'ch-2', title: 'CNN International', type: 'live', groupName: 'News' });
      insertTestContent(testDb, { id: 'mov-1', title: 'The Matrix', type: 'movie', groupName: 'Sci-Fi' });
      insertTestContent(testDb, { id: 'ser-1', title: 'Breaking Bad', type: 'series', groupName: 'Drama' });
    });

    it('finds content by title (LIKE fallback)', () => {
      const results = searchContent('Matrix');
      expect(results.length).toBeGreaterThanOrEqual(1);
      expect(results.some((r) => r.id === 'mov-1')).toBe(true);
    });

    it('finds content across multiple types', () => {
      const results = searchContent('BBC');
      expect(results.some((r) => r.type === 'live')).toBe(true);
    });

    it('finds content by group name (LIKE fallback)', () => {
      const results = searchContent('Drama');
      expect(results.some((r) => r.id === 'ser-1')).toBe(true);
    });

    it('returns empty for non-matching query', () => {
      const results = searchContent('xyznonexistent');
      expect(results).toHaveLength(0);
    });

    it('handles multi-word queries (AND logic via LIKE)', () => {
      // LIKE fallback searches with the full query string as a pattern
      const results = searchContent('BBC News');
      expect(results.some((r) => r.id === 'ch-1')).toBe(true);
    });
  });

  // ---- getContentCountByType ----

  describe('getContentCountByType', () => {
    it('returns zero counts when no content', () => {
      const counts = getContentCountByType();
      expect(counts).toEqual({ live: 0, movie: 0, series: 0 });
    });

    it('returns correct counts per type', () => {
      insertTestContent(testDb, { id: 'ch-1', type: 'live' });
      insertTestContent(testDb, { id: 'ch-2', type: 'live' });
      insertTestContent(testDb, { id: 'mov-1', type: 'movie' });
      insertTestContent(testDb, { id: 'ser-1', type: 'series' });
      insertTestContent(testDb, { id: 'ser-2', type: 'series' });
      insertTestContent(testDb, { id: 'ser-3', type: 'series' });

      const counts = getContentCountByType();
      expect(counts.live).toBe(2);
      expect(counts.movie).toBe(1);
      expect(counts.series).toBe(3);
    });
  });

  // ---- getEpisodes ----

  describe('getEpisodes', () => {
    it('returns empty array when no episodes exist', () => {
      insertTestContent(testDb, { id: 'ser-1', type: 'series' });
      expect(getEpisodes('ser-1')).toEqual([]);
    });

    it('returns episodes for a content item', () => {
      insertTestContent(testDb, { id: 'ser-1', type: 'series' });
      testDb.prepare(
        `INSERT INTO episodes (id, content_id, season_number, episode_number, title, stream_url, duration)
         VALUES ('ep-1', 'ser-1', 1, 1, 'Pilot', 'http://s/ep1', 3600)`,
      ).run();
      testDb.prepare(
        `INSERT INTO episodes (id, content_id, season_number, episode_number, title, stream_url, duration)
         VALUES ('ep-2', 'ser-1', 1, 2, 'Second', 'http://s/ep2', 3000)`,
      ).run();

      const episodes = getEpisodes('ser-1');
      expect(episodes).toHaveLength(2);
      expect(episodes[0].title).toBe('Pilot');
      expect(episodes[0].seasonNumber).toBe(1);
      expect(episodes[0].episodeNumber).toBe(1);
      expect(episodes[0].duration).toBe(3600);
      expect(episodes[1].episodeNumber).toBe(2);
    });

    it('orders episodes by season then episode number', () => {
      insertTestContent(testDb, { id: 'ser-1', type: 'series' });
      // Insert out of order
      testDb.prepare(
        `INSERT INTO episodes (id, content_id, season_number, episode_number, title, stream_url)
         VALUES ('ep-3', 'ser-1', 2, 1, 'S02E01', 'http://s/ep3')`,
      ).run();
      testDb.prepare(
        `INSERT INTO episodes (id, content_id, season_number, episode_number, title, stream_url)
         VALUES ('ep-1', 'ser-1', 1, 2, 'S01E02', 'http://s/ep1')`,
      ).run();
      testDb.prepare(
        `INSERT INTO episodes (id, content_id, season_number, episode_number, title, stream_url)
         VALUES ('ep-2', 'ser-1', 1, 1, 'S01E01', 'http://s/ep2')`,
      ).run();

      const episodes = getEpisodes('ser-1');
      expect(episodes).toHaveLength(3);
      expect(episodes[0].title).toBe('S01E01');
      expect(episodes[1].title).toBe('S01E02');
      expect(episodes[2].title).toBe('S02E01');
    });

    it('maps null optional fields to undefined', () => {
      insertTestContent(testDb, { id: 'ser-1', type: 'series' });
      testDb.prepare(
        `INSERT INTO episodes (id, content_id, season_number, episode_number, title, stream_url, duration)
         VALUES ('ep-1', 'ser-1', NULL, NULL, NULL, 'http://s/ep1', NULL)`,
      ).run();

      const episodes = getEpisodes('ser-1');
      expect(episodes[0].seasonNumber).toBeUndefined();
      expect(episodes[0].episodeNumber).toBeUndefined();
      expect(episodes[0].title).toBeUndefined();
      expect(episodes[0].duration).toBeUndefined();
    });
  });

  // ---- getContentById ----

  describe('getContentById', () => {
    it('returns content item when found', () => {
      insertTestContent(testDb, { id: 'ch-1', title: 'Test Channel', type: 'live', groupName: 'News' });

      const result = getContentById('ch-1');
      expect(result).not.toBeNull();
      expect(result!.id).toBe('ch-1');
      expect(result!.title).toBe('Test Channel');
      expect(result!.type).toBe('live');
      expect(result!.groupName).toBe('News');
      expect(result!.sourceId).toBe('src-1');
    });

    it('returns null when content not found', () => {
      const result = getContentById('nonexistent');
      expect(result).toBeNull();
    });

    it('maps null optional fields to undefined', () => {
      insertTestContent(testDb, { id: 'ch-1', title: 'Bare Channel', type: 'live' });

      const result = getContentById('ch-1');
      expect(result).not.toBeNull();
      expect(result!.groupName).toBeUndefined();
      expect(result!.logoUrl).toBeUndefined();
      expect(result!.tvgId).toBeUndefined();
      expect(result!.cleanTitle).toBeUndefined();
      expect(result!.metadataJson).toBeUndefined();
    });
  });

  // ---- getRelatedContent ----

  describe('getRelatedContent', () => {
    beforeEach(() => {
      insertTestContent(testDb, { id: 'mov-1', title: 'Target Movie', type: 'movie', groupName: 'Action', sourceId: 'src-1' });
      insertTestContent(testDb, { id: 'mov-2', title: 'Another Action', type: 'movie', groupName: 'Action', sourceId: 'src-1' });
      insertTestContent(testDb, { id: 'mov-3', title: 'Third Action', type: 'movie', groupName: 'Action', sourceId: 'src-1' });
      insertTestContent(testDb, { id: 'mov-4', title: 'Comedy Film', type: 'movie', groupName: 'Comedy', sourceId: 'src-1' });
      insertTestContent(testDb, { id: 'mov-5', title: 'Drama Film', type: 'movie', groupName: 'Drama', sourceId: 'src-1' });
    });

    it('returns same-group content excluding the target item', () => {
      const result = getRelatedContent('mov-1', 'Action', 'src-1', 'movie');
      expect(result.sameGroup).toHaveLength(2);
      expect(result.sameGroup.every((c) => c.groupName === 'Action')).toBe(true);
      expect(result.sameGroup.every((c) => c.id !== 'mov-1')).toBe(true);
    });

    it('returns same-source content from different groups', () => {
      const result = getRelatedContent('mov-1', 'Action', 'src-1', 'movie');
      // sameSource should have items from Comedy and Drama (not Action)
      expect(result.sameSource.length).toBeGreaterThanOrEqual(1);
      expect(result.sameSource.every((c) => c.groupName !== 'Action')).toBe(true);
      expect(result.sameSource.every((c) => c.id !== 'mov-1')).toBe(true);
    });

    it('returns empty sameGroup when no groupName provided', () => {
      const result = getRelatedContent('mov-1', undefined, 'src-1', 'movie');
      expect(result.sameGroup).toEqual([]);
    });

    it('returns empty sameSource when no sourceId provided', () => {
      const result = getRelatedContent('mov-1', 'Action', undefined, 'movie');
      expect(result.sameSource).toEqual([]);
    });

    it('defaults to movie type when contentType not specified', () => {
      insertTestContent(testDb, { id: 'ch-1', title: 'Action Live', type: 'live', groupName: 'Action', sourceId: 'src-1' });

      const result = getRelatedContent('mov-1', 'Action', 'src-1');
      // Should only return movie type, not live
      expect(result.sameGroup.every((c) => c.type === 'movie')).toBe(true);
    });

    it('respects contentType parameter', () => {
      insertTestContent(testDb, { id: 'ch-1', title: 'Sports Channel', type: 'live', groupName: 'Sports', sourceId: 'src-1' });
      insertTestContent(testDb, { id: 'ch-2', title: 'Another Sports', type: 'live', groupName: 'Sports', sourceId: 'src-1' });

      const result = getRelatedContent('ch-1', 'Sports', 'src-1', 'live');
      expect(result.sameGroup).toHaveLength(1);
      expect(result.sameGroup[0].type).toBe('live');
    });
  });
});
