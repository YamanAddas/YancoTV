import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import path from 'path';
import os from 'os';
import fs from 'fs';

// ---------------------------------------------------------------------------
// Mocks must be declared before importing the service under test.
// ---------------------------------------------------------------------------

vi.mock('electron', () => ({
  app: {
    getPath: (name: string) =>
      name === 'downloads' ? path.join(os.tmpdir(), 'yancotv-test-downloads') : os.tmpdir(),
  },
  BrowserWindow: { getAllWindows: () => [] },
  shell: { openPath: vi.fn() },
}));

vi.mock('electron-log/main', () => ({
  default: { warn: vi.fn(), info: vi.fn(), error: vi.fn() },
}));

let currentDb: import('better-sqlite3').Database | null = null;
vi.mock('../../src/main/services/db', () => ({
  getDb: () => currentDb,
}));

// Mock settings-service — in-memory KV so tests can flip flags.
const settingsStore: Record<string, string> = {};
vi.mock('../../src/main/services/settings-service', () => ({
  getSetting: (key: string) => settingsStore[key] ?? null,
  setSetting: (key: string, value: string) => {
    settingsStore[key] = value;
  },
  deleteSetting: (key: string) => {
    delete settingsStore[key];
  },
}));

import { createTestDb } from './helpers/test-db';

// Prevent any real network traffic if the worker starts — always reject fetch.
const originalFetch = globalThis.fetch;
beforeEach(() => {
  globalThis.fetch = vi.fn().mockRejectedValue(new Error('fetch disabled in tests'));
});
afterEach(() => {
  globalThis.fetch = originalFetch;
});

import {
  __testing,
  enqueueDownload,
  listDownloads,
  pauseDownload,
  cancelDownload,
  removeDownload,
  reconcileOnStartup,
} from '../../src/main/services/download-service';

describe('Download Service — security helpers', () => {
  beforeEach(() => {
    for (const k of Object.keys(settingsStore)) delete settingsStore[k];
  });

  describe('validateUrl', () => {
    it('accepts http and https URLs', () => {
      expect(__testing.validateUrl('http://example.com/video.mp4').protocol).toBe('http:');
      expect(__testing.validateUrl('https://example.com/video.mp4').protocol).toBe('https:');
    });

    it('rejects file:// URLs', () => {
      expect(() => __testing.validateUrl('file:///etc/passwd')).toThrow(/http\/https/);
    });

    it('rejects ftp:// URLs', () => {
      expect(() => __testing.validateUrl('ftp://example.com/video.mp4')).toThrow(/http\/https/);
    });

    it('rejects data: URIs', () => {
      expect(() => __testing.validateUrl('data:text/plain;base64,aGVsbG8=')).toThrow(/http\/https/);
    });

    it('rejects malformed URLs', () => {
      expect(() => __testing.validateUrl('not a url')).toThrow(/Invalid URL/);
    });
  });

  describe('isBlockedIp — IPv4', () => {
    it.each([
      ['127.0.0.1', true],
      ['127.255.255.254', true],
      ['10.0.0.1', true],
      ['10.255.255.255', true],
      ['192.168.1.1', true],
      ['172.16.0.1', true],
      ['172.31.255.255', true],
      ['169.254.169.254', true], // AWS metadata
      ['0.0.0.0', true],
      ['224.0.0.1', true], // multicast
      ['255.255.255.255', true],
      // Public IPs must pass through
      ['8.8.8.8', false],
      ['1.1.1.1', false],
      ['172.15.0.1', false], // just below private range
      ['172.32.0.1', false], // just above private range
      ['126.255.255.255', false],
    ])('ip %s → blocked=%s', (ip, blocked) => {
      expect(__testing.isBlockedIp(ip)).toBe(blocked);
    });
  });

  describe('isBlockedIp — IPv6', () => {
    it.each([
      ['::1', true],
      ['::', true],
      ['fe80::1', true],
      ['fc00::1', true],
      ['fd00::1', true],
      ['ff00::1', true],
      // Public IPv6 passes
      ['2001:4860:4860::8888', false],
      ['2606:4700:4700::1111', false],
    ])('ip %s → blocked=%s', (ip, blocked) => {
      expect(__testing.isBlockedIp(ip)).toBe(blocked);
    });
  });

  describe('assertHostAllowed', () => {
    it('blocks literal loopback URL', async () => {
      await expect(
        __testing.assertHostAllowed(new URL('http://127.0.0.1/video.mp4')),
      ).rejects.toThrow(/private\/loopback/);
    });

    it('blocks literal RFC1918 URL', async () => {
      await expect(
        __testing.assertHostAllowed(new URL('http://192.168.1.5/video.mp4')),
      ).rejects.toThrow(/private\/loopback/);
    });

    it('blocks AWS metadata IP', async () => {
      await expect(
        __testing.assertHostAllowed(new URL('http://169.254.169.254/latest/')),
      ).rejects.toThrow(/private\/loopback/);
    });

    it('honors the allow-private-ips setting override', async () => {
      settingsStore.download_allow_private_ips = '1';
      await expect(
        __testing.assertHostAllowed(new URL('http://127.0.0.1/video.mp4')),
      ).resolves.toBeUndefined();
    });
  });

  describe('sanitizeFilename', () => {
    it('strips path separators', () => {
      expect(__testing.sanitizeFilename('../etc/passwd')).not.toContain('/');
      expect(__testing.sanitizeFilename('..\\etc\\passwd')).not.toContain('\\');
    });

    it('strips Windows-reserved chars', () => {
      const clean = __testing.sanitizeFilename('file<>:"|?*.mp4');
      expect(clean).not.toMatch(/[<>:"|?*]/);
    });

    it('strips control characters', () => {
      const clean = __testing.sanitizeFilename('hello\x00world\x1fend');
      expect(clean).not.toMatch(/[\x00-\x1f]/);
    });

    it('escapes Windows-reserved device names', () => {
      expect(__testing.sanitizeFilename('CON')).toBe('_CON');
      expect(__testing.sanitizeFilename('nul.mp4')).toBe('_nul.mp4');
      expect(__testing.sanitizeFilename('COM1.txt')).toBe('_COM1.txt');
    });

    it('trims trailing dots and spaces', () => {
      expect(__testing.sanitizeFilename('video.  ')).toBe('video');
      expect(__testing.sanitizeFilename('video...')).toBe('video');
    });

    it('falls back to "download" when everything was stripped', () => {
      expect(__testing.sanitizeFilename('   ')).toBe('download');
      expect(__testing.sanitizeFilename('')).toBe('download');
    });

    it('replaces path separators with underscores (does not empty the name)', () => {
      expect(__testing.sanitizeFilename('///')).toBe('___');
    });

    it('caps overly long filenames', () => {
      const long = 'a'.repeat(500);
      expect(__testing.sanitizeFilename(long).length).toBeLessThanOrEqual(180);
    });
  });

  describe('confinePath', () => {
    it('resolves plain filenames under the base dir', () => {
      const base = path.resolve(os.tmpdir(), 'dl');
      const result = __testing.confinePath(base, 'movie.mp4');
      expect(result.startsWith(base)).toBe(true);
    });

    it('rejects parent-directory traversal', () => {
      const base = path.resolve(os.tmpdir(), 'dl');
      expect(() => __testing.confinePath(base, '../../../etc/passwd')).toThrow(/outside/);
    });

    it('rejects absolute path override', () => {
      const base = path.resolve(os.tmpdir(), 'dl');
      // Windows and POSIX have different absolute path shapes; use a platform-appropriate one.
      const abs = process.platform === 'win32' ? 'C:\\Windows\\System32\\evil' : '/etc/passwd';
      expect(() => __testing.confinePath(base, abs)).toThrow(/outside/);
    });
  });

  describe('extensionFromUrl', () => {
    it('returns the URL path extension when reasonable', () => {
      expect(__testing.extensionFromUrl(new URL('https://x.com/movie.mp4'))).toBe('.mp4');
      expect(__testing.extensionFromUrl(new URL('https://x.com/series.mkv'))).toBe('.mkv');
      expect(__testing.extensionFromUrl(new URL('https://x.com/show.ts'))).toBe('.ts');
    });

    it('falls back to .mp4 when the URL has no useful extension', () => {
      expect(__testing.extensionFromUrl(new URL('https://x.com/stream'))).toBe('.mp4');
      expect(__testing.extensionFromUrl(new URL('https://x.com/a/b/'))).toBe('.mp4');
    });

    it('does not leak absurd path segments as extensions', () => {
      const weird = new URL('https://x.com/path.reallyverylong');
      expect(__testing.extensionFromUrl(weird)).toBe('.mp4');
    });
  });
});

// ---------------------------------------------------------------------------
// Public API smoke tests — through the DB.
// ---------------------------------------------------------------------------

describe('Download Service — public API', () => {
  let tmpDir: string;

  beforeEach(() => {
    currentDb = createTestDb();
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'yanco-dl-test-'));
    for (const k of Object.keys(settingsStore)) delete settingsStore[k];
    settingsStore.download_directory = tmpDir;
  });

  afterEach(() => {
    currentDb?.close();
    currentDb = null;
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch {
      /* best-effort */
    }
  });

  describe('enqueueDownload', () => {
    it('rejects invalid URLs before touching the DB', () => {
      const res = enqueueDownload({
        title: 'Bad',
        streamUrl: 'file:///etc/passwd',
      });
      expect(res.ok).toBe(false);
      expect(listDownloads()).toHaveLength(0);
    });

    it('creates a queued row for a valid public URL', () => {
      const res = enqueueDownload({
        title: 'Good Movie',
        streamUrl: 'http://8.8.8.8/movie.mp4',
      });
      expect(res.ok).toBe(true);
      const rows = listDownloads();
      expect(rows).toHaveLength(1);
      expect(rows[0].title).toBe('Good Movie');
      // Status may advance via processQueue; any non-completed state is acceptable here.
      expect(['queued', 'downloading', 'paused', 'failed']).toContain(rows[0].status);

      // Cancel so the worker abort does not leak across tests.
      if (rows[0].id) cancelDownload(rows[0].id);
    });

    it('still enqueues when a URL points at private IPs (the worker rejects later)', () => {
      // assertHostAllowed is enforced inside the worker, not at enqueue time —
      // documenting that behavior here so the defense-in-depth design stays
      // coupled to tests. The row is created but will never complete.
      const res = enqueueDownload({
        title: 'LAN',
        streamUrl: 'http://192.168.1.10/movie.mp4',
      });
      expect(res.ok).toBe(true);
      const rows = listDownloads();
      expect(rows).toHaveLength(1);
      if (rows[0].id) cancelDownload(rows[0].id);
    });
  });

  describe('pause / cancel / remove', () => {
    it('pause returns an error when the id does not exist', () => {
      const res = pauseDownload('does-not-exist');
      expect(res.ok).toBe(false);
    });

    it('cancel returns an error when the id does not exist', () => {
      const res = cancelDownload('does-not-exist');
      expect(res.ok).toBe(false);
    });

    it('remove returns an error when the id does not exist', () => {
      const res = removeDownload('does-not-exist', false);
      expect(res.ok).toBe(false);
    });

    it('remove with deleteFile=true does nothing dangerous when the file is absent', () => {
      const res = enqueueDownload({
        title: 'Nope',
        streamUrl: 'http://8.8.8.8/bad.mp4',
      });
      expect(res.ok).toBe(true);
      const id = listDownloads()[0].id;
      cancelDownload(id);
      const out = removeDownload(id, true);
      expect(out.ok).toBe(true);
      expect(listDownloads()).toHaveLength(0);
    });
  });

  describe('reconcileOnStartup', () => {
    it('flips stuck "downloading" rows to "paused"', () => {
      // Seed a stuck row directly.
      currentDb!
        .prepare(
          `INSERT INTO downloads (id, title, stream_url, file_path, status, queued_at, bytes_downloaded, resumable)
           VALUES (?, ?, ?, ?, 'downloading', ?, 0, 1)`,
        )
        .run('stuck-1', 'Stuck', 'http://x/y.mp4', path.join(tmpDir, 'y.mp4'), Date.now());

      reconcileOnStartup();
      const row = currentDb!
        .prepare('SELECT status FROM downloads WHERE id = ?')
        .get('stuck-1') as { status: string };
      expect(row.status).toBe('paused');
    });
  });
});
