import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createHash } from 'crypto';
import { createTestDb, insertTestSource, insertTestContent } from './helpers/test-db';
import type Database from 'better-sqlite3';

let testDb: Database.Database;

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}));
vi.mock('../../src/main/services/db', () => ({
  getDb: () => testDb,
}));

import {
  setPin,
  verifyPin,
  removePin,
  getParentalSettings,
  updateParentalSetting,
  lockChannel,
  unlockChannel,
  isChannelLocked,
  getLockedChannelIds,
  hideChannel,
  unhideChannel,
  getHiddenChannelIds,
  setChannelOverride,
  removeChannelOverride,
  getAllChannelOverrides,
  getPinLockoutMs,
  resetPinAttempts,
} from '../../src/main/services/parental-service';

describe('Parental Service', () => {
  beforeEach(() => {
    testDb = createTestDb();
    resetPinAttempts();
  });

  describe('PIN management', () => {
    it('starts with no PIN set', () => {
      const settings = getParentalSettings();
      expect(settings.pinSet).toBe(false);
      expect(settings.pinEnabled).toBe(false);
    });

    it('sets a PIN and enables parental controls', () => {
      setPin('1234');
      const settings = getParentalSettings();
      expect(settings.pinSet).toBe(true);
      expect(settings.pinEnabled).toBe(true);
    });

    it('verifies correct PIN', () => {
      setPin('5678');
      expect(verifyPin('5678')).toBe(true);
    });

    it('rejects incorrect PIN', () => {
      setPin('5678');
      expect(verifyPin('0000')).toBe(false);
      expect(verifyPin('')).toBe(false);
    });

    it('returns false when no PIN is set', () => {
      expect(verifyPin('1234')).toBe(false);
    });

    it('removes PIN and disables controls', () => {
      setPin('1234');
      removePin();
      const settings = getParentalSettings();
      expect(settings.pinSet).toBe(false);
      expect(settings.pinEnabled).toBe(false);
    });

    it('can change PIN', () => {
      setPin('1234');
      setPin('9999');
      expect(verifyPin('1234')).toBe(false);
      expect(verifyPin('9999')).toBe(true);
    });

    it('stores PIN as a salted scrypt hash (not raw SHA-256)', () => {
      setPin('1234');
      const row = testDb
        .prepare("SELECT value FROM settings WHERE key = 'parental_pin_hash'")
        .get() as { value: string };
      expect(row.value.startsWith('scrypt:')).toBe(true);
      // Two calls with the same PIN produce different stored hashes due to the
      // per-PIN random salt.
      setPin('1234');
      const row2 = testDb
        .prepare("SELECT value FROM settings WHERE key = 'parental_pin_hash'")
        .get() as { value: string };
      expect(row2.value).not.toBe(row.value);
    });

    it('verifies legacy unsalted SHA-256 hashes and upgrades them in place', () => {
      // Simulate a PIN stored by a pre-21.9 build: bare SHA-256 hex.
      const legacy = createHash('sha256').update('4242').digest('hex');
      testDb
        .prepare("INSERT INTO settings (key, value) VALUES ('parental_pin_hash', ?)")
        .run(legacy);
      testDb
        .prepare("INSERT INTO settings (key, value) VALUES ('parental_pin_enabled', '1')")
        .run();

      expect(verifyPin('wrong')).toBe(false);
      expect(verifyPin('4242')).toBe(true);

      const row = testDb
        .prepare("SELECT value FROM settings WHERE key = 'parental_pin_hash'")
        .get() as { value: string };
      expect(row.value.startsWith('scrypt:')).toBe(true);
      // The freshly-upgraded hash still verifies.
      expect(verifyPin('4242')).toBe(true);
    });
  });

  describe('PIN rate limiting', () => {
    afterEach(() => {
      vi.useRealTimers();
    });

    it('locks verification after 5 consecutive failures', () => {
      setPin('1234');
      for (let i = 0; i < 5; i++) {
        expect(verifyPin('0000')).toBe(false);
      }
      expect(getPinLockoutMs()).toBeGreaterThan(0);
      // Even the correct PIN is rejected during the cooldown.
      expect(verifyPin('1234')).toBe(false);
    });

    it('clears the cooldown after enough time has elapsed', () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));

      setPin('1234');
      for (let i = 0; i < 5; i++) verifyPin('0000');
      expect(getPinLockoutMs()).toBeGreaterThan(0);

      // Advance past the 30s base cooldown.
      vi.advanceTimersByTime(31_000);
      expect(getPinLockoutMs()).toBe(0);
      expect(verifyPin('1234')).toBe(true);
    });

    it('resets the failure counter on a successful verify', () => {
      setPin('1234');
      verifyPin('0000');
      verifyPin('0000');
      expect(verifyPin('1234')).toBe(true);
      // Four more misses should NOT trip the lockout (counter was reset).
      for (let i = 0; i < 4; i++) verifyPin('0000');
      expect(getPinLockoutMs()).toBe(0);
    });
  });

  describe('parental settings', () => {
    it('updates boolean settings', () => {
      updateParentalSetting('hide_adult', true);
      const settings = getParentalSettings();
      expect(settings.hideAdultContent).toBe(true);
    });

    it('toggles requirePinForSettings', () => {
      updateParentalSetting('require_pin_settings', true);
      expect(getParentalSettings().requirePinForSettings).toBe(true);

      updateParentalSetting('require_pin_settings', false);
      expect(getParentalSettings().requirePinForSettings).toBe(false);
    });
  });

  describe('channel locking', () => {
    beforeEach(() => {
      insertTestSource(testDb);
      insertTestContent(testDb, { id: 'ch-1' });
      insertTestContent(testDb, { id: 'ch-2' });
    });

    it('locks a channel', () => {
      lockChannel('ch-1');
      expect(isChannelLocked('ch-1')).toBe(true);
      expect(isChannelLocked('ch-2')).toBe(false);
    });

    it('unlocks a channel', () => {
      lockChannel('ch-1');
      unlockChannel('ch-1');
      expect(isChannelLocked('ch-1')).toBe(false);
    });

    it('returns all locked channel IDs', () => {
      lockChannel('ch-1');
      lockChannel('ch-2');
      const ids = getLockedChannelIds();
      expect(ids).toContain('ch-1');
      expect(ids).toContain('ch-2');
      expect(ids).toHaveLength(2);
    });

    it('does not duplicate locks', () => {
      lockChannel('ch-1');
      lockChannel('ch-1'); // INSERT OR IGNORE
      expect(getLockedChannelIds()).toHaveLength(1);
    });
  });

  describe('channel hiding', () => {
    beforeEach(() => {
      insertTestSource(testDb);
      insertTestContent(testDb, { id: 'ch-1' });
    });

    it('hides and unhides a channel', () => {
      hideChannel('ch-1');
      expect(getHiddenChannelIds()).toContain('ch-1');

      unhideChannel('ch-1');
      expect(getHiddenChannelIds()).not.toContain('ch-1');
    });

    it('does not duplicate hides', () => {
      hideChannel('ch-1');
      hideChannel('ch-1');
      expect(getHiddenChannelIds()).toHaveLength(1);
    });
  });

  describe('channel overrides', () => {
    beforeEach(() => {
      insertTestSource(testDb);
      insertTestContent(testDb, { id: 'ch-1' });
    });

    it('sets a channel override', () => {
      setChannelOverride({
        contentId: 'ch-1',
        customName: 'My Channel',
        customNumber: 42,
      });

      const overrides = getAllChannelOverrides();
      expect(overrides['ch-1']).toBeDefined();
      expect(overrides['ch-1'].customName).toBe('My Channel');
      expect(overrides['ch-1'].customNumber).toBe(42);
    });

    it('partially updates an override (keeps existing fields)', () => {
      setChannelOverride({
        contentId: 'ch-1',
        customName: 'Original',
        customNumber: 1,
      });

      setChannelOverride({
        contentId: 'ch-1',
        customName: 'Updated',
      });

      const overrides = getAllChannelOverrides();
      expect(overrides['ch-1'].customName).toBe('Updated');
      expect(overrides['ch-1'].customNumber).toBe(1); // preserved
    });

    it('removes an override', () => {
      setChannelOverride({ contentId: 'ch-1', customName: 'Test' });
      removeChannelOverride('ch-1');
      expect(getAllChannelOverrides()).toEqual({});
    });
  });
});
