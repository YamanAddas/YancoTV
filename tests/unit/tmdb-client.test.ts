import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('electron-log', () => ({
  default: { warn: vi.fn(), info: vi.fn(), error: vi.fn() },
}));

import {
  searchMovie,
  searchTv,
  getMovieDetails,
  getTvDetails,
  verifyApiKey,
  posterUrl,
  backdropUrl,
} from '../../src/main/services/tmdb-client';

// Helper — build a Response-shaped object that satisfies what tmdb-client uses.
function okResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: async () => body,
  } as unknown as Response;
}

function errResponse(status: number): Response {
  return {
    ok: false,
    status,
    json: async () => ({}),
  } as unknown as Response;
}

const originalFetch = globalThis.fetch;
let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe('TMDb client — image URL helpers', () => {
  it('posterUrl returns undefined for null/undefined', () => {
    expect(posterUrl(null)).toBeUndefined();
    expect(posterUrl(undefined)).toBeUndefined();
  });

  it('posterUrl defaults to w500 size', () => {
    expect(posterUrl('/abc.jpg')).toBe('https://image.tmdb.org/t/p/w500/abc.jpg');
  });

  it('posterUrl honours an explicit size', () => {
    expect(posterUrl('/abc.jpg', 'w185')).toBe('https://image.tmdb.org/t/p/w185/abc.jpg');
    expect(posterUrl('/abc.jpg', 'original')).toBe('https://image.tmdb.org/t/p/original/abc.jpg');
  });

  it('backdropUrl defaults to w1280 size', () => {
    expect(backdropUrl('/hd.jpg')).toBe('https://image.tmdb.org/t/p/w1280/hd.jpg');
  });

  it('backdropUrl returns undefined for empty paths', () => {
    expect(backdropUrl(null)).toBeUndefined();
    expect(backdropUrl(undefined)).toBeUndefined();
  });
});

describe('TMDb client — request construction', () => {
  it('searchMovie sends api_key and language; returns first result', async () => {
    fetchMock.mockResolvedValueOnce(
      okResponse({ results: [{ id: 42, title: 'Test' }, { id: 99 }] }),
    );
    const result = await searchMovie('Test', null, 'MY_KEY', 'en-US');
    expect(result).toEqual({ id: 42, title: 'Test' });

    const calledUrl = fetchMock.mock.calls[0][0] as string;
    expect(calledUrl).toContain('/search/movie');
    expect(calledUrl).toContain('api_key=MY_KEY');
    expect(calledUrl).toContain('language=en-US');
    expect(calledUrl).toContain('query=Test');
  });

  it('searchMovie includes year when provided', async () => {
    fetchMock.mockResolvedValueOnce(okResponse({ results: [] }));
    await searchMovie('Inception', 2010, 'K', 'en-US');
    const calledUrl = fetchMock.mock.calls[0][0] as string;
    expect(calledUrl).toContain('year=2010');
  });

  it('searchTv uses first_air_date_year param', async () => {
    fetchMock.mockResolvedValueOnce(okResponse({ results: [{ id: 7, name: 'X' }] }));
    await searchTv('X', 2020, 'K', 'en-US');
    const calledUrl = fetchMock.mock.calls[0][0] as string;
    expect(calledUrl).toContain('/search/tv');
    expect(calledUrl).toContain('first_air_date_year=2020');
  });

  it('getMovieDetails appends credits', async () => {
    fetchMock.mockResolvedValueOnce(okResponse({ id: 1, title: 'Movie' }));
    await getMovieDetails(1, 'K', 'en-US');
    const calledUrl = fetchMock.mock.calls[0][0] as string;
    expect(calledUrl).toContain('/movie/1');
    expect(calledUrl).toContain('append_to_response=credits');
  });

  it('getTvDetails appends credits', async () => {
    fetchMock.mockResolvedValueOnce(okResponse({ id: 1, name: 'Show' }));
    await getTvDetails(1, 'K', 'en-US');
    const calledUrl = fetchMock.mock.calls[0][0] as string;
    expect(calledUrl).toContain('/tv/1');
    expect(calledUrl).toContain('append_to_response=credits');
  });
});

describe('TMDb client — error handling', () => {
  it('returns null when fetch throws', async () => {
    fetchMock.mockRejectedValueOnce(new Error('network'));
    const result = await searchMovie('X', null, 'K', 'en-US');
    expect(result).toBeNull();
  });

  it('returns null on 404', async () => {
    fetchMock.mockResolvedValueOnce(errResponse(404));
    const result = await searchMovie('X', null, 'K', 'en-US');
    expect(result).toBeNull();
  });

  it('returns null on 401 (bad key)', async () => {
    fetchMock.mockResolvedValueOnce(errResponse(401));
    const result = await getMovieDetails(1, 'bad', 'en-US');
    expect(result).toBeNull();
  });

  it('short-circuits when no API key is supplied', async () => {
    const result = await searchMovie('X', null, '', 'en-US');
    expect(result).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('returns null when results array is empty', async () => {
    fetchMock.mockResolvedValueOnce(okResponse({ results: [] }));
    expect(await searchMovie('Nothing', null, 'K', 'en-US')).toBeNull();
  });
});

describe('TMDb client — verifyApiKey', () => {
  it('returns true on a 200 response', async () => {
    fetchMock.mockResolvedValueOnce(okResponse({ success: true }));
    expect(await verifyApiKey('GOOD')).toBe(true);
  });

  it('returns false on a 401 response', async () => {
    fetchMock.mockResolvedValueOnce(errResponse(401));
    expect(await verifyApiKey('BAD')).toBe(false);
  });

  it('returns false for empty key without hitting the network', async () => {
    expect(await verifyApiKey('')).toBe(false);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
