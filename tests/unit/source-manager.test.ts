import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createTestDb } from './helpers/test-db';
import type Database from 'better-sqlite3';

let testDb: Database.Database;

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}));
vi.mock('../../src/main/services/db', () => ({
  getDb: () => testDb,
}));
vi.mock('../../src/main/services/credential-store', () => ({
  encryptCredential: (val: string) => Buffer.from(`enc:${val}`),
  decryptCredential: (buf: Buffer) => buf.toString().replace('enc:', ''),
}));

let uuidCounter = 0;
vi.mock('uuid', () => ({
  v4: () => `src-${++uuidCounter}`,
}));

import {
  getAllSources,
  getSourceById,
  addSource,
  removeSource,
  updateSourceSyncTime,
  updateSourceEpgUrl,
  getSourceCredentials,
} from '../../src/main/services/source-manager';

describe('Source Manager', () => {
  beforeEach(() => {
    testDb = createTestDb();
    uuidCounter = 0;
  });

  describe('addSource', () => {
    it('adds an M3U URL source', () => {
      const result = addSource({
        name: 'My Playlist',
        type: 'm3u_url',
        url: 'http://example.com/playlist.m3u',
      });

      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value.name).toBe('My Playlist');
        expect(result.value.type).toBe('m3u_url');
        expect(result.value.url).toBe('http://example.com/playlist.m3u');
        expect(result.value.isActive).toBe(true);
      }
    });

    it('adds an M3U file source', () => {
      const result = addSource({
        name: 'Local File',
        type: 'm3u_file',
        filePath: 'C:\\playlists\\local.m3u',
      });

      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value.type).toBe('m3u_file');
        expect(result.value.filePath).toBe('C:\\playlists\\local.m3u');
      }
    });

    it('adds an Xtream source with encrypted credentials', () => {
      const result = addSource({
        name: 'Xtream Provider',
        type: 'xtream',
        url: 'http://xtream.example.com',
        username: 'user1',
        password: 'pass1',
      });

      expect(result.ok).toBe(true);

      // Verify credentials were stored encrypted
      const creds = getSourceCredentials('src-1');
      expect(creds).not.toBeNull();
      expect(creds!.username).toBe('user1');
      expect(creds!.password).toBe('pass1');
    });
  });

  describe('getAllSources', () => {
    it('returns empty array when no sources', () => {
      expect(getAllSources()).toEqual([]);
    });

    it('returns all sources ordered by created_at DESC', () => {
      // Manually insert with explicit timestamps to guarantee ordering
      const now = Date.now();
      testDb.prepare(
        `INSERT INTO sources (id, name, type, url, is_active, created_at, updated_at)
         VALUES ('s-old', 'First', 'm3u_url', 'http://a.com', 1, ?, ?)`,
      ).run(now - 1000, now - 1000);
      testDb.prepare(
        `INSERT INTO sources (id, name, type, url, is_active, created_at, updated_at)
         VALUES ('s-new', 'Second', 'm3u_url', 'http://b.com', 1, ?, ?)`,
      ).run(now, now);

      const sources = getAllSources();
      expect(sources).toHaveLength(2);
      // Most recent first
      expect(sources[0].name).toBe('Second');
      expect(sources[1].name).toBe('First');
    });
  });

  describe('getSourceById', () => {
    it('returns a source by ID', () => {
      addSource({ name: 'Test', type: 'm3u_url', url: 'http://test.com' });
      const source = getSourceById('src-1');
      expect(source).not.toBeNull();
      expect(source!.name).toBe('Test');
    });

    it('returns null for non-existent ID', () => {
      expect(getSourceById('nonexistent')).toBeNull();
    });
  });

  describe('removeSource', () => {
    it('removes an existing source', () => {
      addSource({ name: 'ToDelete', type: 'm3u_url', url: 'http://x.com' });
      const result = removeSource('src-1');
      expect(result.ok).toBe(true);
      expect(getSourceById('src-1')).toBeNull();
    });

    it('returns error for non-existent source', () => {
      const result = removeSource('nonexistent');
      expect(result.ok).toBe(false);
    });
  });

  describe('updateSourceSyncTime', () => {
    it('updates the last_synced timestamp', () => {
      addSource({ name: 'Test', type: 'm3u_url', url: 'http://test.com' });
      const before = getSourceById('src-1')!;
      expect(before.lastSynced).toBeUndefined();

      updateSourceSyncTime('src-1');
      const after = getSourceById('src-1')!;
      expect(after.lastSynced).toBeGreaterThan(0);
    });
  });

  describe('updateSourceEpgUrl', () => {
    it('sets EPG URL when currently empty', () => {
      addSource({ name: 'Test', type: 'm3u_url', url: 'http://test.com' });
      updateSourceEpgUrl('src-1', 'http://epg.example.com/guide.xml');
      const source = getSourceById('src-1')!;
      expect(source.epgUrl).toBe('http://epg.example.com/guide.xml');
    });

    it('does not overwrite an existing EPG URL', () => {
      addSource({
        name: 'Test',
        type: 'm3u_url',
        url: 'http://test.com',
        epgUrl: 'http://original-epg.com/guide.xml',
      });
      updateSourceEpgUrl('src-1', 'http://new-epg.com/guide.xml');
      const source = getSourceById('src-1')!;
      expect(source.epgUrl).toBe('http://original-epg.com/guide.xml');
    });
  });

  describe('getSourceCredentials', () => {
    it('returns null when source has no credentials', () => {
      addSource({ name: 'M3U', type: 'm3u_url', url: 'http://m3u.com' });
      expect(getSourceCredentials('src-1')).toBeNull();
    });
  });
});
