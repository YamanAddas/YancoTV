import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import path from 'path';
import os from 'os';
import fs from 'fs';

// --- Mocks ---

const settings = new Map<string, string>();

vi.mock('../../src/main/services/settings-service', () => ({
  getSetting: (key: string) => settings.get(key),
}));

vi.mock('../../src/main/services/credential-store', () => ({
  decryptCredential: (buf: Buffer) => buf.toString('utf-8'),
}));

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}));

const userDataDir = path.join(os.tmpdir(), `yancotv-os-test-${Date.now()}`);
fs.mkdirSync(userDataDir, { recursive: true });

vi.mock('electron', () => ({
  app: { getPath: () => userDataDir },
}));

import {
  searchSubtitles,
  downloadSubtitle,
  invalidateToken,
} from '../../src/main/services/opensubtitles-client';

const fetchMock = vi.fn();
const originalFetch = globalThis.fetch;

beforeEach(() => {
  fetchMock.mockReset();
  settings.clear();
  invalidateToken();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

describe('opensubtitles-client — searchSubtitles', () => {
  it('builds query params from search input and returns parsed data', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({
        data: [{ id: 'sub-1', type: 'subtitle', attributes: { subtitle_id: 'sub-1', language: 'en' } }],
        total_pages: 1,
        total_count: 1,
        per_page: 50,
        page: 1,
      }),
    );

    const result = await searchSubtitles({
      query: 'The Matrix',
      imdb_id: 'tt0133093',
      season: 1,
      episode: 2,
      languages: 'en,tr',
      type: 'episode',
    });

    expect(result).toHaveLength(1);
    expect(result[0].id).toBe('sub-1');

    const [url] = fetchMock.mock.calls[0];
    expect(url).toContain('/subtitles?');
    expect(url).toContain('query=The+Matrix');
    expect(url).toContain('imdb_id=tt0133093');
    expect(url).toContain('season_number=1');
    expect(url).toContain('episode_number=2');
    expect(url).toContain('languages=en%2Ctr');
    expect(url).toContain('type=episode');
  });

  it('omits the type param when "all" is requested', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ data: [], total_pages: 0, total_count: 0, per_page: 50, page: 1 }));
    await searchSubtitles({ query: 'x', type: 'all' });
    expect(fetchMock.mock.calls[0][0]).not.toContain('type=');
  });

  it('wires the signed-in user token into the Authorization header', async () => {
    settings.set('opensubtitles.username', 'alice');
    // decryptCredential mock returns the UTF-8 of the Buffer
    settings.set('opensubtitles.password_enc', Buffer.from('pw', 'utf-8').toString('base64'));

    // First fetch = login, second = the search call
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ token: 'jwt-xyz', user: { allowed_downloads: 100, level: 'VIP' }, status: 200 }))
      .mockResolvedValueOnce(jsonResponse({ data: [], total_pages: 0, total_count: 0, per_page: 50, page: 1 }));

    await searchSubtitles({ query: 'heist' });

    const [, searchInit] = fetchMock.mock.calls[1];
    const headers = (searchInit.headers ?? {}) as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer jwt-xyz');
  });

  it('throws OpenSubtitlesError with status on a non-ok response', async () => {
    fetchMock.mockResolvedValueOnce(new Response('nope', { status: 503, statusText: 'Service Unavailable' }));
    await expect(searchSubtitles({ query: 'x' })).rejects.toThrow(/503/);
  });

  it('maps an AbortError into a timeout-flavored OpenSubtitlesError', async () => {
    fetchMock.mockImplementationOnce(() => {
      const err = new Error('The operation was aborted');
      (err as { name?: string }).name = 'AbortError';
      return Promise.reject(err);
    });
    await expect(searchSubtitles({ query: 'x' })).rejects.toThrow(/timed out/i);
  });

  it('passes an AbortSignal on every request', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ data: [], total_pages: 0, total_count: 0, per_page: 50, page: 1 }));
    await searchSubtitles({ query: 'x' });
    const [, init] = fetchMock.mock.calls[0];
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });
});

describe('opensubtitles-client — downloadSubtitle', () => {
  it('requests a download link, fetches the file, and writes it to the cache dir', async () => {
    fetchMock
      .mockResolvedValueOnce(
        jsonResponse({ link: 'https://dl.example/sub.srt', file_name: 'Movie.srt', requests: 1, remaining: 99, message: '', reset_time: '' }),
      )
      .mockResolvedValueOnce(new Response(Buffer.from('1\n00:00:01,000 --> 00:00:02,000\nHi\n')));

    const { path: outPath, remaining } = await downloadSubtitle(42);
    expect(remaining).toBe(99);
    expect(outPath).toContain('subtitles-cache');
    expect(outPath).toMatch(/42-Movie\.srt$/);
    expect(fs.existsSync(outPath)).toBe(true);
    fs.unlinkSync(outPath);
  });

  it('throws when the server omits a download link', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ file_name: 'x.srt', requests: 0, remaining: 0, message: '', reset_time: '' }));
    await expect(downloadSubtitle(1)).rejects.toThrow(/missing link/i);
  });

  it('throws when the link-request call returns non-ok', async () => {
    fetchMock.mockResolvedValueOnce(new Response('quota', { status: 429, statusText: 'Too Many Requests' }));
    await expect(downloadSubtitle(1)).rejects.toThrow(/429/);
  });

  it('sanitizes suspicious filenames before writing to disk', async () => {
    fetchMock
      .mockResolvedValueOnce(
        jsonResponse({ link: 'https://dl.example/a', file_name: '../../etc/passwd.srt', requests: 1, remaining: 1, message: '', reset_time: '' }),
      )
      .mockResolvedValueOnce(new Response(Buffer.from('ok')));

    const { path: outPath } = await downloadSubtitle(7);
    // The file must land inside the cache dir — the traversal segments in
    // the server-supplied filename should have been neutralized.
    expect(path.dirname(outPath)).toBe(path.join(userDataDir, 'subtitles-cache'));
    const basename = path.basename(outPath);
    expect(basename).not.toMatch(/[/\\]/);
    expect(basename.startsWith('7-')).toBe(true);
    fs.unlinkSync(outPath);
  });
});
