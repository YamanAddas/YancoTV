import { describe, it, expect, vi, beforeEach } from 'vitest';

// Set process.resourcesPath before imports (not defined outside Electron)
process.resourcesPath = 'C:\\Users\\test\\resources';

vi.mock('electron', () => ({
  app: {
    getPath: vi.fn((name: string) => {
      if (name === 'exe') return 'C:\\Users\\test\\AppData\\YancoTV\\YancoTV.exe';
      return 'C:\\Users\\test\\AppData';
    }),
  },
}));

vi.mock('fs', () => ({
  default: { existsSync: vi.fn() },
  existsSync: vi.fn(),
}));

vi.mock('child_process', () => ({
  execFileSync: vi.fn(),
}));

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn() },
}));

import fs from 'fs';
import { execFileSync } from 'child_process';
import { findMpvPath } from '../../src/main/player/mpv-path';

const mockExistsSync = vi.mocked(fs.existsSync);
const mockExecFileSync = vi.mocked(execFileSync);

describe('mpv-path', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('returns path when found in a bundled location', () => {
    mockExistsSync.mockImplementation((p) => {
      return String(p).endsWith('mpv\\mpv.exe') && String(p).includes(process.cwd());
    });

    const result = findMpvPath();

    expect(result).not.toBeNull();
    expect(result).toContain('mpv.exe');
    expect(mockExecFileSync).not.toHaveBeenCalled();
  });

  it('returns path from system PATH via where command', () => {
    // No bundled/known paths exist
    mockExistsSync.mockImplementation((p) => {
      return String(p) === 'D:\\tools\\mpv\\mpv.exe';
    });
    mockExecFileSync.mockReturnValue('D:\\tools\\mpv\\mpv.exe\n');

    const result = findMpvPath();

    expect(result).toBe('D:\\tools\\mpv\\mpv.exe');
    expect(mockExecFileSync).toHaveBeenCalledWith('where', ['mpv.exe'], expect.objectContaining({
      encoding: 'utf8',
      timeout: 5000,
    }));
  });

  it('returns null when mpv is not found anywhere', () => {
    mockExistsSync.mockReturnValue(false);
    mockExecFileSync.mockImplementation(() => {
      throw new Error('not found');
    });

    const result = findMpvPath();

    expect(result).toBeNull();
  });

  it('returns first match when multiple paths exist', () => {
    const calls: string[] = [];
    mockExistsSync.mockImplementation((p) => {
      calls.push(String(p));
      return String(p).endsWith('mpv.exe');
    });

    const result = findMpvPath();

    // Should return immediately after the first match
    expect(result).not.toBeNull();
    expect(result).toContain('mpv.exe');
    expect(calls).toHaveLength(1);
    expect(mockExecFileSync).not.toHaveBeenCalled();
  });
});
