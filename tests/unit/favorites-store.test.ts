import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createTestDb, insertTestSource, insertTestContent } from './helpers/test-db';
import type Database from 'better-sqlite3';

let testDb: Database.Database;

vi.mock('../../src/main/services/db', () => ({
  getDb: () => testDb,
}));

// Mock uuid to return predictable IDs
let uuidCounter = 0;
vi.mock('uuid', () => ({
  v4: () => `fav-${++uuidCounter}`,
}));

import {
  getFavorites,
  getFavoriteIds,
  isFavorite,
  addFavorite,
  removeFavorite,
} from '../../src/main/services/favorites-store';

describe('Favorites Store (main process)', () => {
  beforeEach(() => {
    testDb = createTestDb();
    uuidCounter = 0;
    // Seed a source and some content
    insertTestSource(testDb);
    insertTestContent(testDb, { id: 'ch-1', title: 'Channel One', type: 'live' });
    insertTestContent(testDb, { id: 'ch-2', title: 'Channel Two', type: 'live' });
    insertTestContent(testDb, { id: 'mov-1', title: 'Movie One', type: 'movie' });
  });

  it('starts with no favorites', () => {
    expect(getFavoriteIds()).toEqual([]);
    expect(getFavorites()).toEqual([]);
  });

  it('adds a favorite', () => {
    const result = addFavorite('ch-1');
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.id).toBe('fav-1');
    }
    expect(isFavorite('ch-1')).toBe(true);
  });

  it('prevents duplicate favorites', () => {
    addFavorite('ch-1');
    const result = addFavorite('ch-1');
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error).toBe('Already a favorite');
    }
  });

  it('removes a favorite', () => {
    addFavorite('ch-1');
    const result = removeFavorite('ch-1');
    expect(result.ok).toBe(true);
    expect(isFavorite('ch-1')).toBe(false);
  });

  it('returns all favorite IDs', () => {
    addFavorite('ch-1');
    addFavorite('mov-1');
    const ids = getFavoriteIds();
    expect(ids).toContain('ch-1');
    expect(ids).toContain('mov-1');
    expect(ids).toHaveLength(2);
  });

  it('returns full favorite entries with content', () => {
    addFavorite('ch-1');
    const entries = getFavorites();
    expect(entries).toHaveLength(1);
    expect(entries[0].content.id).toBe('ch-1');
    expect(entries[0].content.title).toBe('Channel One');
    expect(entries[0].content.type).toBe('live');
    expect(entries[0].addedAt).toBeGreaterThan(0);
  });

  it('isFavorite returns false for non-favorite', () => {
    expect(isFavorite('ch-2')).toBe(false);
  });

  it('favorites are ordered by most recently added', () => {
    // Manually insert with explicit timestamps to guarantee ordering
    testDb.prepare('INSERT INTO favorites (id, content_id, added_at) VALUES (?, ?, ?)').run('f1', 'ch-1', 1000);
    testDb.prepare('INSERT INTO favorites (id, content_id, added_at) VALUES (?, ?, ?)').run('f2', 'ch-2', 2000);
    testDb.prepare('INSERT INTO favorites (id, content_id, added_at) VALUES (?, ?, ?)').run('f3', 'mov-1', 3000);
    const entries = getFavorites();
    // Most recent first
    expect(entries[0].content.id).toBe('mov-1');
    expect(entries[2].content.id).toBe('ch-1');
  });
});
