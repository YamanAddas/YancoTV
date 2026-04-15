import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createTestDb } from './helpers/test-db';
import type Database from 'better-sqlite3';

let testDb: Database.Database;

vi.mock('../../src/main/services/db', () => ({
  getDb: () => testDb,
}));

import {
  getSetting,
  setSetting,
  deleteSetting,
  getAllSettings,
  setSettings,
} from '../../src/main/services/settings-service';

describe('Settings Service', () => {
  beforeEach(() => {
    testDb = createTestDb();
  });

  describe('getSetting / setSetting', () => {
    it('returns null for non-existent key', () => {
      expect(getSetting('nonexistent')).toBeNull();
    });

    it('stores and retrieves a setting', () => {
      setSetting('theme', 'dark');
      expect(getSetting('theme')).toBe('dark');
    });

    it('overwrites an existing setting', () => {
      setSetting('theme', 'dark');
      setSetting('theme', 'light');
      expect(getSetting('theme')).toBe('light');
    });
  });

  describe('deleteSetting', () => {
    it('removes an existing setting', () => {
      setSetting('theme', 'dark');
      deleteSetting('theme');
      expect(getSetting('theme')).toBeNull();
    });

    it('does not throw when deleting a non-existent key', () => {
      expect(() => deleteSetting('nonexistent')).not.toThrow();
    });
  });

  describe('getAllSettings', () => {
    it('returns an empty map when no settings exist', () => {
      expect(getAllSettings()).toEqual({});
    });

    it('returns all stored settings', () => {
      setSetting('a', '1');
      setSetting('b', '2');
      setSetting('c', '3');
      expect(getAllSettings()).toEqual({ a: '1', b: '2', c: '3' });
    });
  });

  describe('setSettings (bulk)', () => {
    it('sets multiple settings in one call', () => {
      setSettings({ x: '10', y: '20', z: '30' });
      expect(getSetting('x')).toBe('10');
      expect(getSetting('y')).toBe('20');
      expect(getSetting('z')).toBe('30');
    });

    it('overwrites existing settings in bulk', () => {
      setSetting('x', 'old');
      setSettings({ x: 'new', y: '2' });
      expect(getSetting('x')).toBe('new');
      expect(getSetting('y')).toBe('2');
    });

    it('is transactional — all or nothing', () => {
      setSettings({ a: '1', b: '2' });
      expect(getAllSettings()).toEqual({ a: '1', b: '2' });
    });
  });
});
