import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// ---------------------------------------------------------------------------
// Mocks — declared before importing the service.
// ---------------------------------------------------------------------------

vi.mock('electron', () => ({
  safeStorage: {
    isEncryptionAvailable: () => true,
    encryptString: (s: string) => Buffer.from(`ENC:${s}`, 'utf-8'),
    decryptString: (b: Buffer) => b.toString('utf-8').replace(/^ENC:/, ''),
  },
}));

vi.mock('electron-log', () => ({
  default: { warn: vi.fn(), info: vi.fn(), error: vi.fn() },
}));

vi.mock('electron-log/main', () => ({
  default: { warn: vi.fn(), info: vi.fn(), error: vi.fn() },
}));

let currentDb: import('better-sqlite3').Database | null = null;
vi.mock('../../src/main/services/db', () => ({
  getDb: () => currentDb,
}));

// Mock the TMDb client calls so we control what the service "sees".
const searchMovieMock = vi.fn();
const searchTvMock = vi.fn();
const getMovieDetailsMock = vi.fn();
const getTvDetailsMock = vi.fn();
const verifyApiKeyMock = vi.fn();

vi.mock('../../src/main/services/tmdb-client', () => ({
  searchMovie: (...args: unknown[]) => searchMovieMock(...args),
  searchTv: (...args: unknown[]) => searchTvMock(...args),
  getMovieDetails: (...args: unknown[]) => getMovieDetailsMock(...args),
  getTvDetails: (...args: unknown[]) => getTvDetailsMock(...args),
  verifyApiKey: (...args: unknown[]) => verifyApiKeyMock(...args),
  posterUrl: (path: string | null | undefined) =>
    path ? `https://image.tmdb.org/t/p/w500${path}` : undefined,
  backdropUrl: (path: string | null | undefined) =>
    path ? `https://image.tmdb.org/t/p/w1280${path}` : undefined,
}));

import { createTestDb } from './helpers/test-db';
import {
  setSetting,
  getSetting,
} from '../../src/main/services/settings-service';

import {
  isTmdbEnabled,
  hasTmdbApiKey,
  getTmdbLanguage,
  setTmdbApiKey,
  clearTmdbApiKey,
  testTmdbApiKey,
  clearTmdbCache,
  enrichMetadata,
} from '../../src/main/services/tmdb-service';
import type { ContentItem, ContentMetadata } from '../../src/shared/types';

const MOVIE_ITEM: ContentItem = {
  id: 'c1',
  sourceId: 's1',
  type: 'movie',
  title: 'Inception (2010)',
  streamUrl: 'http://x/y.mp4',
  sortOrder: 0,
  createdAt: 0,
};

const SERIES_ITEM: ContentItem = {
  id: 'c2',
  sourceId: 's1',
  type: 'series',
  title: 'Breaking Bad',
  streamUrl: 'http://x/s.ts',
  sortOrder: 0,
  createdAt: 0,
};

const LIVE_ITEM: ContentItem = {
  ...MOVIE_ITEM,
  id: 'c3',
  type: 'live',
};

beforeEach(() => {
  currentDb = createTestDb();
  searchMovieMock.mockReset();
  searchTvMock.mockReset();
  getMovieDetailsMock.mockReset();
  getTvDetailsMock.mockReset();
  verifyApiKeyMock.mockReset();
});

afterEach(() => {
  currentDb?.close();
  currentDb = null;
});

describe('TMDb service — settings helpers', () => {
  it('isTmdbEnabled reflects the setting', () => {
    expect(isTmdbEnabled()).toBe(false);
    setSetting('tmdb_enabled', '1');
    expect(isTmdbEnabled()).toBe(true);
    setSetting('tmdb_enabled', '0');
    expect(isTmdbEnabled()).toBe(false);
  });

  it('getTmdbLanguage defaults to en-US', () => {
    expect(getTmdbLanguage()).toBe('en-US');
    setSetting('tmdb_language', 'es-ES');
    expect(getTmdbLanguage()).toBe('es-ES');
  });

  it('hasTmdbApiKey reports false before anything is stored', () => {
    expect(hasTmdbApiKey()).toBe(false);
  });

  it('setTmdbApiKey stores an encrypted key and hasTmdbApiKey turns true', () => {
    setTmdbApiKey('my-secret-key');
    expect(hasTmdbApiKey()).toBe(true);

    const stored = getSetting('tmdb_api_key_enc');
    expect(stored).toBeTruthy();
    // Stored value is base64 of ENC:my-secret-key under our fake safeStorage.
    const decoded = Buffer.from(stored!, 'base64').toString('utf-8');
    expect(decoded).toBe('ENC:my-secret-key');
  });

  it('clearTmdbApiKey removes the stored key', () => {
    setTmdbApiKey('my-key');
    expect(hasTmdbApiKey()).toBe(true);
    clearTmdbApiKey();
    expect(hasTmdbApiKey()).toBe(false);
  });

  it('setTmdbApiKey with empty string clears the key', () => {
    setTmdbApiKey('something');
    setTmdbApiKey('');
    expect(hasTmdbApiKey()).toBe(false);
  });

  it('testTmdbApiKey delegates to verifyApiKey', async () => {
    verifyApiKeyMock.mockResolvedValueOnce(true);
    expect(await testTmdbApiKey('good')).toBe(true);
    verifyApiKeyMock.mockResolvedValueOnce(false);
    expect(await testTmdbApiKey('bad')).toBe(false);
  });
});

describe('TMDb service — enrichMetadata short-circuits', () => {
  it('returns the original metadata when TMDb is disabled', async () => {
    setSetting('tmdb_enabled', '0');
    setTmdbApiKey('KEY');
    const input: ContentMetadata = { plot: 'original' };
    const out = await enrichMetadata(MOVIE_ITEM, input);
    expect(out).toEqual(input);
    expect(searchMovieMock).not.toHaveBeenCalled();
  });

  it('returns the original metadata when no API key is set', async () => {
    setSetting('tmdb_enabled', '1');
    const input: ContentMetadata = { plot: 'original' };
    const out = await enrichMetadata(MOVIE_ITEM, input);
    expect(out).toEqual(input);
    expect(searchMovieMock).not.toHaveBeenCalled();
  });

  it('skips live channels entirely', async () => {
    setSetting('tmdb_enabled', '1');
    setTmdbApiKey('KEY');
    const out = await enrichMetadata(LIVE_ITEM, {});
    expect(out).toEqual({});
    expect(searchMovieMock).not.toHaveBeenCalled();
    expect(searchTvMock).not.toHaveBeenCalled();
  });
});

describe('TMDb service — enrichMetadata (movie)', () => {
  beforeEach(() => {
    setSetting('tmdb_enabled', '1');
    setTmdbApiKey('KEY');
  });

  it('fills gaps from TMDb without overwriting existing fields', async () => {
    searchMovieMock.mockResolvedValueOnce({ id: 27205, title: 'Inception' });
    getMovieDetailsMock.mockResolvedValueOnce({
      id: 27205,
      title: 'Inception',
      overview: 'Dreams within dreams.',
      tagline: 'Your mind is the scene of the crime.',
      poster_path: '/poster.jpg',
      backdrop_path: '/backdrop.jpg',
      release_date: '2010-07-16',
      vote_average: 8.4,
      genres: [{ id: 28, name: 'Action' }, { id: 878, name: 'Sci-Fi' }],
      credits: {
        cast: [{ name: 'Leonardo DiCaprio' }, { name: 'Joseph Gordon-Levitt' }],
        crew: [{ name: 'Christopher Nolan', job: 'Director' }],
      },
    });

    const input: ContentMetadata = { plot: 'existing-plot' };
    const out = await enrichMetadata(MOVIE_ITEM, input);

    // Existing field survives.
    expect(out.plot).toBe('existing-plot');
    // Gaps are filled.
    expect(out.cast).toContain('Leonardo DiCaprio');
    expect(out.director).toBe('Christopher Nolan');
    expect(out.genre).toBe('Action, Sci-Fi');
    expect(out.rating).toBe('8.4');
    expect(out.releaseDate).toBe('2010-07-16');
    // TMDb-specific fields always overlay.
    expect(out.tmdbId).toBe(27205);
    expect(out.tmdbType).toBe('movie');
    expect(out.tmdbPosterUrl).toContain('/poster.jpg');
    expect(out.tmdbBackdropUrl).toContain('/backdrop.jpg');
    expect(out.tmdbTagline).toBe('Your mind is the scene of the crime.');
    expect(out.tmdbEnrichedAt).toBeTypeOf('number');
  });

  it('writes a hit to the cache and returns cached data on second call', async () => {
    searchMovieMock.mockResolvedValueOnce({ id: 1, title: 'Inception' });
    getMovieDetailsMock.mockResolvedValueOnce({
      id: 1,
      title: 'Inception',
      overview: 'overview',
      poster_path: '/p.jpg',
      vote_average: 7,
    });

    await enrichMetadata(MOVIE_ITEM, {});
    const firstSearchCount = searchMovieMock.mock.calls.length;
    const firstDetailsCount = getMovieDetailsMock.mock.calls.length;

    // Second call — nothing should hit the client.
    await enrichMetadata(MOVIE_ITEM, {});
    expect(searchMovieMock.mock.calls.length).toBe(firstSearchCount);
    expect(getMovieDetailsMock.mock.calls.length).toBe(firstDetailsCount);

    // The cache should have one hit row for this content.
    const row = currentDb!
      .prepare('SELECT tmdb_id, miss FROM tmdb_cache WHERE content_id = ?')
      .get(MOVIE_ITEM.id) as { tmdb_id: number; miss: number };
    expect(row.tmdb_id).toBe(1);
    expect(row.miss).toBe(0);
  });

  it('writes a miss to the cache when TMDb returns nothing', async () => {
    searchMovieMock.mockResolvedValueOnce(null);
    const out = await enrichMetadata(MOVIE_ITEM, { plot: 'only-this' });
    // Returned metadata is unchanged aside from the miss having no effect.
    expect(out).toEqual({ plot: 'only-this' });

    const row = currentDb!
      .prepare('SELECT tmdb_id, miss FROM tmdb_cache WHERE content_id = ?')
      .get(MOVIE_ITEM.id) as { tmdb_id: number | null; miss: number };
    expect(row.miss).toBe(1);
    expect(row.tmdb_id).toBeNull();
  });

  it('does not re-query TMDb while a miss is still fresh', async () => {
    searchMovieMock.mockResolvedValueOnce(null);
    await enrichMetadata(MOVIE_ITEM, {});
    searchMovieMock.mockClear();

    await enrichMetadata(MOVIE_ITEM, {});
    expect(searchMovieMock).not.toHaveBeenCalled();
  });

  it('clearTmdbCache wipes both hits and misses', async () => {
    searchMovieMock.mockResolvedValueOnce(null);
    await enrichMetadata(MOVIE_ITEM, {});
    expect(
      currentDb!.prepare('SELECT COUNT(*) AS n FROM tmdb_cache').get() as { n: number },
    ).toEqual({ n: 1 });

    clearTmdbCache();
    expect(
      currentDb!.prepare('SELECT COUNT(*) AS n FROM tmdb_cache').get() as { n: number },
    ).toEqual({ n: 0 });
  });
});

describe('TMDb service — enrichMetadata (series)', () => {
  beforeEach(() => {
    setSetting('tmdb_enabled', '1');
    setTmdbApiKey('KEY');
  });

  it('uses the TV endpoints for series and fills TMDb fields', async () => {
    searchTvMock.mockResolvedValueOnce({ id: 1396, name: 'Breaking Bad' });
    getTvDetailsMock.mockResolvedValueOnce({
      id: 1396,
      name: 'Breaking Bad',
      overview: 'A chemistry teacher…',
      tagline: 'Remember my name.',
      poster_path: '/bb.jpg',
      backdrop_path: '/bb-bd.jpg',
      first_air_date: '2008-01-20',
      vote_average: 9.5,
      genres: [{ id: 18, name: 'Drama' }],
      credits: {
        cast: [{ name: 'Bryan Cranston' }, { name: 'Aaron Paul' }],
        crew: [{ name: 'Vince Gilligan', job: 'Creator' }],
      },
    });

    const out = await enrichMetadata(SERIES_ITEM, {});
    expect(searchMovieMock).not.toHaveBeenCalled();
    expect(out.tmdbType).toBe('tv');
    expect(out.tmdbId).toBe(1396);
    expect(out.plot).toBe('A chemistry teacher…');
    expect(out.cast).toContain('Bryan Cranston');
    // TV details don't carry a single director; the service leaves it unset.
    expect(out.director).toBeUndefined();
    expect(out.genre).toBe('Drama');
    expect(out.rating).toBe('9.5');
    expect(out.releaseDate).toBe('2008-01-20');
  });
});
