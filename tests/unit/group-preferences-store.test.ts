import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createTestDb } from './helpers/test-db';
import type Database from 'better-sqlite3';

let testDb: Database.Database;

vi.mock('../../src/main/services/db', () => ({
  getDb: () => testDb,
}));

let uuidCounter = 0;
vi.mock('uuid', () => ({
  v4: () => `gp-${++uuidCounter}`,
}));

import {
  getGroupPreferences,
  setGroupPreference,
  reorderGroups,
  removeGroupPreference,
} from '../../src/main/services/group-preferences-store';

describe('Group Preferences Store', () => {
  beforeEach(() => {
    testDb = createTestDb();
    uuidCounter = 0;
  });

  // -------------------------------------------------------------------------
  // getGroupPreferences
  // -------------------------------------------------------------------------

  it('returns empty array when no preferences exist', () => {
    expect(getGroupPreferences('live')).toEqual([]);
  });

  it('returns preferences for the given content type only', () => {
    setGroupPreference({ contentType: 'live', groupKey: 'Sports' });
    setGroupPreference({ contentType: 'movie', groupKey: 'Action' });

    const livePrefs = getGroupPreferences('live');
    expect(livePrefs).toHaveLength(1);
    expect(livePrefs[0].groupKey).toBe('Sports');

    const moviePrefs = getGroupPreferences('movie');
    expect(moviePrefs).toHaveLength(1);
    expect(moviePrefs[0].groupKey).toBe('Action');
  });

  it('returns preferences ordered by sort_order ascending', () => {
    setGroupPreference({ contentType: 'live', groupKey: 'Z-Group', sortOrder: 2 });
    setGroupPreference({ contentType: 'live', groupKey: 'A-Group', sortOrder: 0 });
    setGroupPreference({ contentType: 'live', groupKey: 'M-Group', sortOrder: 1 });

    const prefs = getGroupPreferences('live');
    expect(prefs.map((p) => p.groupKey)).toEqual(['A-Group', 'M-Group', 'Z-Group']);
  });

  // -------------------------------------------------------------------------
  // setGroupPreference — insert
  // -------------------------------------------------------------------------

  it('inserts a new preference with defaults', () => {
    const pref = setGroupPreference({ contentType: 'live', groupKey: 'News' });

    expect(pref.id).toBe('gp-1');
    expect(pref.contentType).toBe('live');
    expect(pref.groupKey).toBe('News');
    expect(pref.sortOrder).toBe(0);
    expect(pref.isHidden).toBe(false);
    expect(pref.isPinned).toBe(false);
    expect(pref.customName).toBeNull();
    expect(pref.createdAt).toBeGreaterThan(0);
  });

  it('inserts a new preference with all fields set', () => {
    const pref = setGroupPreference({
      contentType: 'movie',
      groupKey: 'Comedy',
      sortOrder: 5,
      isHidden: true,
      isPinned: true,
      customName: 'My Comedies',
    });

    expect(pref.sortOrder).toBe(5);
    expect(pref.isHidden).toBe(true);
    expect(pref.isPinned).toBe(true);
    expect(pref.customName).toBe('My Comedies');
  });

  // -------------------------------------------------------------------------
  // setGroupPreference — update existing (individual fields)
  // -------------------------------------------------------------------------

  it('updates sortOrder on existing preference', () => {
    setGroupPreference({ contentType: 'live', groupKey: 'Sports', sortOrder: 0 });
    const updated = setGroupPreference({ contentType: 'live', groupKey: 'Sports', sortOrder: 10 });

    expect(updated.sortOrder).toBe(10);
    // id should remain the same
    expect(updated.id).toBe('gp-1');
  });

  it('updates isHidden on existing preference', () => {
    setGroupPreference({ contentType: 'live', groupKey: 'Sports' });
    const updated = setGroupPreference({ contentType: 'live', groupKey: 'Sports', isHidden: true });

    expect(updated.isHidden).toBe(true);
    expect(updated.isPinned).toBe(false); // unchanged
  });

  it('updates isPinned on existing preference', () => {
    setGroupPreference({ contentType: 'live', groupKey: 'Sports' });
    const updated = setGroupPreference({ contentType: 'live', groupKey: 'Sports', isPinned: true });

    expect(updated.isPinned).toBe(true);
    expect(updated.isHidden).toBe(false); // unchanged
  });

  it('updates customName on existing preference', () => {
    setGroupPreference({ contentType: 'live', groupKey: 'Sports' });
    const updated = setGroupPreference({
      contentType: 'live',
      groupKey: 'Sports',
      customName: 'My Sports',
    });

    expect(updated.customName).toBe('My Sports');
  });

  it('clears customName by setting it to null', () => {
    setGroupPreference({ contentType: 'live', groupKey: 'Sports', customName: 'Old Name' });
    const updated = setGroupPreference({
      contentType: 'live',
      groupKey: 'Sports',
      customName: null,
    });

    expect(updated.customName).toBeNull();
  });

  // -------------------------------------------------------------------------
  // reorderGroups
  // -------------------------------------------------------------------------

  it('assigns sort_order based on array index', () => {
    reorderGroups('live', ['News', 'Sports', 'Music']);

    const prefs = getGroupPreferences('live');
    expect(prefs).toHaveLength(3);
    expect(prefs[0].groupKey).toBe('News');
    expect(prefs[0].sortOrder).toBe(0);
    expect(prefs[1].groupKey).toBe('Sports');
    expect(prefs[1].sortOrder).toBe(1);
    expect(prefs[2].groupKey).toBe('Music');
    expect(prefs[2].sortOrder).toBe(2);
  });

  it('updates sort_order for existing preferences via upsert', () => {
    setGroupPreference({ contentType: 'live', groupKey: 'Sports', sortOrder: 0, isPinned: true });
    setGroupPreference({ contentType: 'live', groupKey: 'News', sortOrder: 1 });

    // Reverse the order
    reorderGroups('live', ['News', 'Sports']);

    const prefs = getGroupPreferences('live');
    expect(prefs[0].groupKey).toBe('News');
    expect(prefs[0].sortOrder).toBe(0);
    expect(prefs[1].groupKey).toBe('Sports');
    expect(prefs[1].sortOrder).toBe(1);
  });

  it('does not affect other content types when reordering', () => {
    setGroupPreference({ contentType: 'movie', groupKey: 'Action', sortOrder: 0 });
    reorderGroups('live', ['Sports', 'News']);

    const moviePrefs = getGroupPreferences('movie');
    expect(moviePrefs).toHaveLength(1);
    expect(moviePrefs[0].groupKey).toBe('Action');
    expect(moviePrefs[0].sortOrder).toBe(0);
  });

  // -------------------------------------------------------------------------
  // removeGroupPreference
  // -------------------------------------------------------------------------

  it('removes a preference by contentType and groupKey', () => {
    setGroupPreference({ contentType: 'live', groupKey: 'Sports' });
    setGroupPreference({ contentType: 'live', groupKey: 'News' });

    removeGroupPreference('live', 'Sports');

    const prefs = getGroupPreferences('live');
    expect(prefs).toHaveLength(1);
    expect(prefs[0].groupKey).toBe('News');
  });

  it('does nothing when removing a non-existent preference', () => {
    // Should not throw
    removeGroupPreference('live', 'NonExistent');
    expect(getGroupPreferences('live')).toEqual([]);
  });

  it('only removes the matching contentType+groupKey pair', () => {
    setGroupPreference({ contentType: 'live', groupKey: 'Sports' });
    setGroupPreference({ contentType: 'movie', groupKey: 'Sports' });

    removeGroupPreference('live', 'Sports');

    expect(getGroupPreferences('live')).toHaveLength(0);
    expect(getGroupPreferences('movie')).toHaveLength(1);
  });

  // -------------------------------------------------------------------------
  // Edge cases
  // -------------------------------------------------------------------------

  it('upsert is idempotent — calling set with no changes returns same data', () => {
    const first = setGroupPreference({ contentType: 'live', groupKey: 'Sports', sortOrder: 3 });
    const second = setGroupPreference({ contentType: 'live', groupKey: 'Sports' });

    // No fields were specified in the second call (all undefined), so nothing changes
    expect(second.id).toBe(first.id);
    expect(second.sortOrder).toBe(3);
    expect(second.isHidden).toBe(first.isHidden);
    expect(second.isPinned).toBe(first.isPinned);
  });

  it('boolean is_hidden maps correctly between 0/1 and false/true', () => {
    const hidden = setGroupPreference({ contentType: 'live', groupKey: 'A', isHidden: true });
    expect(hidden.isHidden).toBe(true);

    const visible = setGroupPreference({ contentType: 'live', groupKey: 'A', isHidden: false });
    expect(visible.isHidden).toBe(false);
  });

  it('boolean is_pinned maps correctly between 0/1 and false/true', () => {
    const pinned = setGroupPreference({ contentType: 'series', groupKey: 'Drama', isPinned: true });
    expect(pinned.isPinned).toBe(true);

    const unpinned = setGroupPreference({
      contentType: 'series',
      groupKey: 'Drama',
      isPinned: false,
    });
    expect(unpinned.isPinned).toBe(false);
  });
});
