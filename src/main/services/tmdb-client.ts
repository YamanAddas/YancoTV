/**
 * TMDb v3 API client.
 *
 * Lightweight wrapper around TMDb's public API (https://developer.themoviedb.org).
 * Users provide their own API key in Settings — we store it via safeStorage
 * and only send it on outbound requests. We never embed a key in the app.
 *
 * Rate limiting: TMDb's public limit is ~50 req/s; we self-throttle to a small
 * concurrent window to avoid bursting.
 */

import log from 'electron-log';

const TMDB_BASE = 'https://api.themoviedb.org/3';
const IMAGE_BASE = 'https://image.tmdb.org/t/p';
const REQUEST_TIMEOUT_MS = 10_000;
const MAX_CONCURRENT = 4;

// ---------------------------------------------------------------------------
// Types (narrow — only the fields we actually consume)
// ---------------------------------------------------------------------------

export interface TmdbSearchResult {
  id: number;
  title?: string;         // movie
  name?: string;          // tv
  original_title?: string;
  original_name?: string;
  release_date?: string;  // movie
  first_air_date?: string; // tv
  overview?: string;
  poster_path?: string | null;
  backdrop_path?: string | null;
  vote_average?: number;
  popularity?: number;
}

export interface TmdbCreditsPerson {
  name: string;
  character?: string;
  job?: string;
  profile_path?: string | null;
}

export interface TmdbMovieDetails {
  id: number;
  title: string;
  original_title?: string;
  overview?: string;
  tagline?: string;
  poster_path?: string | null;
  backdrop_path?: string | null;
  release_date?: string;
  runtime?: number;
  vote_average?: number;
  genres?: { id: number; name: string }[];
  credits?: {
    cast?: TmdbCreditsPerson[];
    crew?: TmdbCreditsPerson[];
  };
}

export interface TmdbTvDetails {
  id: number;
  name: string;
  original_name?: string;
  overview?: string;
  tagline?: string;
  poster_path?: string | null;
  backdrop_path?: string | null;
  first_air_date?: string;
  number_of_seasons?: number;
  number_of_episodes?: number;
  episode_run_time?: number[];
  vote_average?: number;
  genres?: { id: number; name: string }[];
  credits?: {
    cast?: TmdbCreditsPerson[];
    crew?: TmdbCreditsPerson[];
  };
}

// ---------------------------------------------------------------------------
// Concurrency gate — simple semaphore
// ---------------------------------------------------------------------------

let inFlight = 0;
const waiters: Array<() => void> = [];

async function acquire(): Promise<void> {
  if (inFlight < MAX_CONCURRENT) {
    inFlight++;
    return;
  }
  await new Promise<void>((resolve) => waiters.push(resolve));
  inFlight++;
}

function release(): void {
  inFlight--;
  const next = waiters.shift();
  if (next) next();
}

// ---------------------------------------------------------------------------
// Internal request helper
// ---------------------------------------------------------------------------

async function tmdbFetch<T>(
  path: string,
  params: Record<string, string>,
  apiKey: string,
  language: string,
): Promise<T | null> {
  if (!apiKey) return null;

  const qs = new URLSearchParams({ api_key: apiKey, language, ...params });
  const url = `${TMDB_BASE}${path}?${qs.toString()}`;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  await acquire();
  try {
    const res = await fetch(url, {
      signal: controller.signal,
      headers: { Accept: 'application/json' },
    });
    if (!res.ok) {
      // 404 is expected for missing records; do not log loudly.
      if (res.status !== 404) {
        log.warn(`TMDb ${path} returned ${res.status}`);
      }
      return null;
    }
    return (await res.json()) as T;
  } catch (err) {
    if ((err as { name?: string }).name === 'AbortError') {
      log.warn(`TMDb ${path} timed out`);
    } else {
      log.warn(`TMDb ${path} failed:`, err);
    }
    return null;
  } finally {
    clearTimeout(timer);
    release();
  }
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

export async function verifyApiKey(apiKey: string): Promise<boolean> {
  if (!apiKey) return false;
  const res = await tmdbFetch<{ success: boolean }>(
    '/authentication',
    {},
    apiKey,
    'en-US',
  );
  return !!res;
}

export async function searchMovie(
  title: string,
  year: number | null,
  apiKey: string,
  language: string,
): Promise<TmdbSearchResult | null> {
  const params: Record<string, string> = {
    query: title,
    include_adult: 'false',
  };
  if (year) params.year = String(year);
  const data = await tmdbFetch<{ results?: TmdbSearchResult[] }>(
    '/search/movie',
    params,
    apiKey,
    language,
  );
  const first = data?.results?.[0];
  return first ?? null;
}

export async function searchTv(
  name: string,
  year: number | null,
  apiKey: string,
  language: string,
): Promise<TmdbSearchResult | null> {
  const params: Record<string, string> = {
    query: name,
    include_adult: 'false',
  };
  if (year) params.first_air_date_year = String(year);
  const data = await tmdbFetch<{ results?: TmdbSearchResult[] }>(
    '/search/tv',
    params,
    apiKey,
    language,
  );
  const first = data?.results?.[0];
  return first ?? null;
}

export async function getMovieDetails(
  tmdbId: number,
  apiKey: string,
  language: string,
): Promise<TmdbMovieDetails | null> {
  return tmdbFetch<TmdbMovieDetails>(
    `/movie/${tmdbId}`,
    { append_to_response: 'credits' },
    apiKey,
    language,
  );
}

export async function getTvDetails(
  tmdbId: number,
  apiKey: string,
  language: string,
): Promise<TmdbTvDetails | null> {
  return tmdbFetch<TmdbTvDetails>(
    `/tv/${tmdbId}`,
    { append_to_response: 'credits' },
    apiKey,
    language,
  );
}

// ---------------------------------------------------------------------------
// Image URL helpers — TMDb image paths are relative; prefix with IMAGE_BASE.
// ---------------------------------------------------------------------------

export function posterUrl(path: string | null | undefined, size: 'w185' | 'w342' | 'w500' | 'original' = 'w500'): string | undefined {
  if (!path) return undefined;
  return `${IMAGE_BASE}/${size}${path}`;
}

export function backdropUrl(path: string | null | undefined, size: 'w780' | 'w1280' | 'original' = 'w1280'): string | undefined {
  if (!path) return undefined;
  return `${IMAGE_BASE}/${size}${path}`;
}
