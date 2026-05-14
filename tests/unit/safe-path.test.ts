/**
 * Tests for confinePath / tryConfinePath. These are the only thing
 * keeping the asset-fetcher, subtitle-extractor, opensubtitles-client,
 * and download-service from writing outside their intended dirs when
 * a filename component comes from external input.
 */

import { describe, it, expect } from 'vitest';
import path from 'path';
import { confinePath, tryConfinePath } from '../../src/main/utils/safe-path';

const BASE = path.resolve('C:/data/downloads');

describe('confinePath', () => {
  it('returns a resolved path under the base for ordinary filenames', () => {
    const out = confinePath(BASE, 'movie.mp4');
    expect(out).toBe(path.resolve(BASE, 'movie.mp4'));
  });

  it('allows nested filenames as long as they stay under the base', () => {
    const out = confinePath(BASE, 'subdir/show.mkv');
    expect(out).toBe(path.resolve(BASE, 'subdir/show.mkv'));
  });

  it('rejects parent-directory traversal via ".."', () => {
    expect(() => confinePath(BASE, '../escape.mp4')).toThrow(/resolves outside/);
  });

  it('rejects deeply nested traversal via "../../"', () => {
    expect(() => confinePath(BASE, 'a/b/../../../escape.mp4')).toThrow(/resolves outside/);
  });

  it('rejects POSIX absolute path overrides', () => {
    // path.resolve(BASE, '/etc/passwd') resolves to the absolute target,
    // which is outside BASE. confinePath should refuse.
    expect(() => confinePath(BASE, '/etc/passwd')).toThrow(/resolves outside/);
  });

  it('rejects Windows absolute path overrides on win32', () => {
    if (process.platform !== 'win32') return;
    expect(() => confinePath(BASE, 'C:\\Windows\\System32\\drivers\\etc\\hosts')).toThrow(
      /resolves outside/,
    );
  });

  it('accepts a base with a trailing separator', () => {
    const out = confinePath(`${BASE}${path.sep}`, 'movie.mp4');
    expect(out).toBe(path.resolve(BASE, 'movie.mp4'));
  });

  it('strips repeated separators inside the filename', () => {
    const out = confinePath(BASE, 'sub///nested.mkv');
    expect(out).toBe(path.resolve(BASE, 'sub/nested.mkv'));
  });

  it('error message includes both filename and baseDir for debuggability', () => {
    try {
      confinePath(BASE, '../oops');
      throw new Error('should have thrown');
    } catch (err) {
      const msg = (err as Error).message;
      expect(msg).toContain('../oops');
      expect(msg).toContain(BASE);
    }
  });
});

describe('tryConfinePath', () => {
  it('returns the resolved path on success (matches confinePath)', () => {
    expect(tryConfinePath(BASE, 'movie.mp4')).toBe(confinePath(BASE, 'movie.mp4'));
  });

  it('returns null instead of throwing on traversal', () => {
    expect(tryConfinePath(BASE, '../escape.mp4')).toBeNull();
    expect(tryConfinePath(BASE, '/etc/passwd')).toBeNull();
  });
});
