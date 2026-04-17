import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createTestDb } from './helpers/test-db';

vi.mock('../../src/main/services/db', () => {
  let db: ReturnType<typeof createTestDb> | null = null;
  return {
    getDb: () => {
      if (!db) db = createTestDb();
      return db;
    },
  };
});

// fs.accessSync is used to validate cached file paths still exist on disk.
// We mock it to control whether a "file exists" or not.
vi.mock('fs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('fs')>();
  return {
    ...actual,
    default: {
      ...actual,
      accessSync: vi.fn(), // by default: does not throw → file exists
      constants: actual.constants,
    },
  };
});

import fs from 'fs';
import {
  getCachedSubtitle,
  cacheSubtitle,
  evictSubtitleCache,
  getSubtitleCacheStats,
  clearSubtitleCache,
} from '../../src/main/services/subtitle-cache-service';

const accessSyncMock = vi.mocked(fs.accessSync);

beforeEach(() => {
  clearSubtitleCache();
  accessSyncMock.mockReset();
  // Default: file exists
  accessSyncMock.mockImplementation(() => undefined);
});

describe('cacheSubtitle + getCachedSubtitle', () => {
  it('returns null when nothing cached', () => {
    expect(getCachedSubtitle('c1', 'en')).toBeNull();
  });

  it('returns cached subtitle after insert', () => {
    cacheSubtitle({ contentId: 'c1', language: 'en', filePath: '/tmp/sub.srt', fileId: 99 });
    const result = getCachedSubtitle('c1', 'en');
    expect(result).not.toBeNull();
    expect(result!.filePath).toBe('/tmp/sub.srt');
    expect(result!.fileId).toBe(99);
    expect(result!.language).toBe('en');
  });

  it('returns null and purges row when file no longer exists', () => {
    cacheSubtitle({ contentId: 'c2', language: 'fr', filePath: '/tmp/gone.srt' });
    accessSyncMock.mockImplementation(() => {
      throw new Error('ENOENT');
    });
    expect(getCachedSubtitle('c2', 'fr')).toBeNull();
    // Row was purged — re-enable file and it should still be gone
    accessSyncMock.mockImplementation(() => undefined);
    expect(getCachedSubtitle('c2', 'fr')).toBeNull();
  });

  it('differentiates by language', () => {
    cacheSubtitle({ contentId: 'c3', language: 'en', filePath: '/en.srt' });
    cacheSubtitle({ contentId: 'c3', language: 'ar', filePath: '/ar.srt' });
    expect(getCachedSubtitle('c3', 'en')!.filePath).toBe('/en.srt');
    expect(getCachedSubtitle('c3', 'ar')!.filePath).toBe('/ar.srt');
    expect(getCachedSubtitle('c3', 'fr')).toBeNull();
  });

  it('differentiates by episodeId', () => {
    cacheSubtitle({ contentId: 'series1', language: 'en', filePath: '/ep1.srt', episodeId: 'ep-1' });
    cacheSubtitle({ contentId: 'series1', language: 'en', filePath: '/ep2.srt', episodeId: 'ep-2' });
    expect(getCachedSubtitle('series1', 'en', 'ep-1')!.filePath).toBe('/ep1.srt');
    expect(getCachedSubtitle('series1', 'en', 'ep-2')!.filePath).toBe('/ep2.srt');
    // Without episodeId — should miss (no movie-level entry)
    expect(getCachedSubtitle('series1', 'en')).toBeNull();
  });

  it('upserts — second write with same key replaces file_path', () => {
    cacheSubtitle({ contentId: 'c4', language: 'en', filePath: '/old.srt', fileId: 1 });
    cacheSubtitle({ contentId: 'c4', language: 'en', filePath: '/new.srt', fileId: 2 });
    const result = getCachedSubtitle('c4', 'en');
    expect(result!.filePath).toBe('/new.srt');
    expect(result!.fileId).toBe(2);
  });

  it('stores null fileId when not provided', () => {
    cacheSubtitle({ contentId: 'c5', language: 'de', filePath: '/de.srt' });
    const result = getCachedSubtitle('c5', 'de');
    expect(result!.fileId).toBeNull();
  });
});

describe('evictSubtitleCache', () => {
  it('removes all entries for a content_id when no episodeId given', () => {
    cacheSubtitle({ contentId: 'movie1', language: 'en', filePath: '/en.srt' });
    cacheSubtitle({ contentId: 'movie1', language: 'fr', filePath: '/fr.srt' });
    evictSubtitleCache('movie1');
    expect(getCachedSubtitle('movie1', 'en')).toBeNull();
    expect(getCachedSubtitle('movie1', 'fr')).toBeNull();
  });

  it('removes only matching episode entry', () => {
    cacheSubtitle({ contentId: 's1', episodeId: 'e1', language: 'en', filePath: '/e1.srt' });
    cacheSubtitle({ contentId: 's1', episodeId: 'e2', language: 'en', filePath: '/e2.srt' });
    evictSubtitleCache('s1', 'e1');
    expect(getCachedSubtitle('s1', 'en', 'e1')).toBeNull();
    expect(getCachedSubtitle('s1', 'en', 'e2')!.filePath).toBe('/e2.srt');
  });
});

describe('getSubtitleCacheStats + clearSubtitleCache', () => {
  it('counts cached entries', () => {
    expect(getSubtitleCacheStats().count).toBe(0);
    cacheSubtitle({ contentId: 'a', language: 'en', filePath: '/a.srt' });
    cacheSubtitle({ contentId: 'b', language: 'en', filePath: '/b.srt' });
    expect(getSubtitleCacheStats().count).toBe(2);
  });

  it('clearSubtitleCache removes all rows', () => {
    cacheSubtitle({ contentId: 'a', language: 'en', filePath: '/a.srt' });
    clearSubtitleCache();
    expect(getSubtitleCacheStats().count).toBe(0);
  });
});
