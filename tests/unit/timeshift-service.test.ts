import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock electron dependencies
vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}));
vi.mock('electron', () => ({
  BrowserWindow: { getAllWindows: vi.fn(() => []) },
}));

import {
  getTimeshiftMpvArgs,
  activateTimeshift,
  deactivateTimeshift,
  getTimeshiftState,
  updateTimeshiftOffset,
} from '../../src/main/services/timeshift-service';

describe('Timeshift Service', () => {
  beforeEach(() => {
    // Reset state between tests
    deactivateTimeshift();
  });

  describe('getTimeshiftMpvArgs', () => {
    it('returns correct mpv flags with default buffer', () => {
      const args = getTimeshiftMpvArgs();

      expect(args).toContain('--cache=yes');
      expect(args).toContain('--cache-pause=no');

      // Default is 30 minutes = 1800s * 2MB/s = 3,774,873,600 bytes
      const expectedBytes = 1800 * 2 * 1024 * 1024;
      expect(args).toContain(`--demuxer-max-bytes=${expectedBytes}`);
      expect(args).toContain(`--demuxer-max-back-bytes=${expectedBytes}`);
    });

    it('accepts custom buffer duration', () => {
      const args = getTimeshiftMpvArgs(600); // 10 minutes

      const expectedBytes = 600 * 2 * 1024 * 1024;
      expect(args).toContain(`--demuxer-max-bytes=${expectedBytes}`);
      expect(args).toContain(`--demuxer-max-back-bytes=${expectedBytes}`);
    });

    it('returns 4 args', () => {
      expect(getTimeshiftMpvArgs()).toHaveLength(4);
    });
  });

  describe('state machine', () => {
    it('starts inactive', () => {
      const state = getTimeshiftState();
      expect(state.active).toBe(false);
      expect(state.bufferSeconds).toBe(0);
      expect(state.offsetSeconds).toBe(0);
    });

    it('activates timeshift', () => {
      activateTimeshift();
      const state = getTimeshiftState();
      expect(state.active).toBe(true);
      expect(state.bufferSeconds).toBe(1800); // 30 min default
      expect(state.offsetSeconds).toBe(0);
      expect(state.activatedAt).toBeDefined();
    });

    it('does not double-activate', () => {
      activateTimeshift();
      const first = getTimeshiftState();
      activateTimeshift(); // should be no-op
      const second = getTimeshiftState();
      expect(second.activatedAt).toBe(first.activatedAt);
    });

    it('updates offset', () => {
      activateTimeshift();
      updateTimeshiftOffset(-120);
      expect(getTimeshiftState().offsetSeconds).toBe(-120);
    });

    it('deactivates timeshift', () => {
      activateTimeshift();
      deactivateTimeshift();
      const state = getTimeshiftState();
      expect(state.active).toBe(false);
      expect(state.bufferSeconds).toBe(0);
      expect(state.offsetSeconds).toBe(0);
      expect(state.activatedAt).toBeUndefined();
    });

    it('deactivate is idempotent when already inactive', () => {
      deactivateTimeshift(); // should not throw
      expect(getTimeshiftState().active).toBe(false);
    });

    it('returns a copy of state (not the internal reference)', () => {
      activateTimeshift();
      const state1 = getTimeshiftState();
      const state2 = getTimeshiftState();
      expect(state1).toEqual(state2);
      expect(state1).not.toBe(state2); // different object references
    });
  });
});
