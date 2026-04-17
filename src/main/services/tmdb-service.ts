/**
 * TMDb enrichment service.
 *
 * Sits between the `content:getDetail` IPC handler and `tmdb-client`:
 *  - Reads/writes a small SQLite cache keyed by content_id so we only hit
 *    the API once per title (plus periodic refresh).
 *  - Stores the user's API key encrypted via Electron safeStorage.
 *  - Merges TMDb results onto the existing ContentMetadata shape so the
 *    renderer UI gets filled-in plot/cast/genre/poster/backdrop without
 *    needing to know where each field came from.
 */

import log from 'electron-log/main';
import { getDb } from './db';
import { getSetting, setSetting, deleteSetting } from './settings-service';
import { encryptCredential, decryptCredential } from './credential-store';
import { cleanTitle, extractYear } from './title-cleaner';
import {
  searchMovie,
  searchTv,
  getMovieDetails,
  getTvDetails,
  posterUrl,
  backdropUrl,
  verifyApiKey,
  type TmdbMovieDetails,
  type TmdbTvDetails,
} from './tmdb-client';
import type { ContentItem, ContentMetadata } from '../../shared/types';

// ---------------------------------------------------------------------------
// Settings keys and constants
// ---------------------------------------------------------------------------

const SETTING_KEY_ENABLED = 'tmdb_enabled';
const SETTING_KEY_LANGUAGE = 'tmdb_language';
const SETTING_KEY_API_KEY_ENC = 'tmdb_api_key_enc';

const DEFAULT_LANGUAGE = 'en-US';

// Hits cache for 30 days, misses for 7 days (so the API is retried eventually).
const HIT_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MISS_TTL_MS = 7 * 24 * 60 * 60 * 1000;

// ---------------------------------------------------------------------------
// Settings helpers
// ---------------------------------------------------------------------------

export function isTmdbEnabled(): boolean {
  return getSetting(SETTING_KEY_ENABLED) === '1';
}

export function getTmdbLanguage(): string {
  return getSetting(SETTING_KEY_LANGUAGE) || DEFAULT_LANGUAGE;
}

/** True if an API key is configured — does not reveal the key itself. */
export function hasTmdbApiKey(): boolean {
  return !!getSetting(SETTING_KEY_API_KEY_ENC);
}

function readApiKey(): string | null {
  const enc = getSetting(SETTING_KEY_API_KEY_ENC);
  if (!enc) return null;
  try {
    return decryptCredential(Buffer.from(enc, 'base64'));
  } catch (err) {
    log.error('Failed to decrypt TMDb API key:', err);
    return null;
  }
}

export function setTmdbApiKey(apiKey: string): void {
  if (!apiKey) {
    deleteSetting(SETTING_KEY_API_KEY_ENC);
    return;
  }
  const buf = encryptCredential(apiKey.trim());
  setSetting(SETTING_KEY_API_KEY_ENC, buf.toString('base64'));
}

export function clearTmdbApiKey(): void {
  deleteSetting(SETTING_KEY_API_KEY_ENC);
}

export async function testTmdbApiKey(apiKey: string): Promise<boolean> {
  return verifyApiKey(apiKey.trim());
}

// ---------------------------------------------------------------------------
// Cache
// ---------------------------------------------------------------------------

interface CachedPayload {
  tmdbId: number;
  tmdbType: 'movie' | 'tv';
  poster?: string;
  backdrop?: string;
  tagline?: string;
  plot?: string;
  cast?: string;
  director?: string;
  genre?: string;
  rating?: string;
  releaseDate?: string;
}

interface TmdbCacheRow {
  tmdb_id: number | null;
  tmdb_type: 'movie' | 'tv' | null;
  payload_json: string | null;
  miss: number;
  fetched_at: number;
}

function readCache(contentId: string): TmdbCacheRow | undefined {
  const db = getDb();
  return db
    .prepare('SELECT tmdb_id, tmdb_type, payload_json, miss, fetched_at FROM tmdb_cache WHERE content_id = ?')
    .get(contentId) as TmdbCacheRow | undefined;
}

function writeCacheHit(contentId: string, payload: CachedPayload): void {
  const db = getDb();
  db.prepare(
    `INSERT OR REPLACE INTO tmdb_cache
       (content_id, tmdb_id, tmdb_type, payload_json, miss, fetched_at)
     VALUES (?, ?, ?, ?, 0, ?)`,
  ).run(contentId, payload.tmdbId, payload.tmdbType, JSON.stringify(payload), Date.now());
}

function writeCacheMiss(contentId: string): void {
  const db = getDb();
  db.prepare(
    `INSERT OR REPLACE INTO tmdb_cache
       (content_id, tmdb_id, tmdb_type, payload_json, miss, fetched_at)
     VALUES (?, NULL, NULL, NULL, 1, ?)`,
  ).run(contentId, Date.now());
}

function isFresh(row: TmdbCacheRow): boolean {
  const ttl = row.miss ? MISS_TTL_MS : HIT_TTL_MS;
  return Date.now() - row.fetched_at < ttl;
}

export function clearTmdbCache(): void {
  const db = getDb();
  db.prepare('DELETE FROM tmdb_cache').run();
}

// ---------------------------------------------------------------------------
// Extraction helpers
// ---------------------------------------------------------------------------

const MAX_CAST_ENTRIES = 8;
const DIRECTOR_JOB = 'Director';

function formatCast(details: TmdbMovieDetails | TmdbTvDetails): string | undefined {
  const list = details.credits?.cast
    ?.slice(0, MAX_CAST_ENTRIES)
    .map((p) => p.name)
    .filter(Boolean);
  if (!list || list.length === 0) return undefined;
  return list.join(', ');
}

function findDirector(details: TmdbMovieDetails): string | undefined {
  const dir = details.credits?.crew?.find((p) => p.job === DIRECTOR_JOB);
  return dir?.name;
}

function formatGenre(details: TmdbMovieDetails | TmdbTvDetails): string | undefined {
  const g = details.genres?.map((x) => x.name).filter(Boolean);
  if (!g || g.length === 0) return undefined;
  return g.join(', ');
}

function formatRating(score: number | undefined): string | undefined {
  if (typeof score !== 'number' || score <= 0) return undefined;
  return score.toFixed(1);
}

// ---------------------------------------------------------------------------
// Core lookup
// ---------------------------------------------------------------------------

async function performLookup(
  item: ContentItem,
  apiKey: string,
  language: string,
): Promise<CachedPayload | null> {
  const rawTitle = item.cleanTitle || item.title;
  const query = cleanTitle(rawTitle);
  const year = extractYear(rawTitle) ?? extractYear(query);

  if (item.type === 'movie') {
    const hit = await searchMovie(query, year, apiKey, language);
    if (!hit) return null;
    const details = await getMovieDetails(hit.id, apiKey, language);
    if (!details) return null;
    return {
      tmdbId: details.id,
      tmdbType: 'movie',
      poster: posterUrl(details.poster_path, 'w500'),
      backdrop: backdropUrl(details.backdrop_path, 'w1280'),
      tagline: details.tagline || undefined,
      plot: details.overview || undefined,
      cast: formatCast(details),
      director: findDirector(details),
      genre: formatGenre(details),
      rating: formatRating(details.vote_average),
      releaseDate: details.release_date || undefined,
    };
  }

  if (item.type === 'series') {
    const hit = await searchTv(query, year, apiKey, language);
    if (!hit) return null;
    const details = await getTvDetails(hit.id, apiKey, language);
    if (!details) return null;
    return {
      tmdbId: details.id,
      tmdbType: 'tv',
      poster: posterUrl(details.poster_path, 'w500'),
      backdrop: backdropUrl(details.backdrop_path, 'w1280'),
      tagline: details.tagline || undefined,
      plot: details.overview || undefined,
      cast: formatCast(details),
      // TV shows don't have a single director in TMDb credits; leave empty.
      genre: formatGenre(details),
      rating: formatRating(details.vote_average),
      releaseDate: details.first_air_date || undefined,
    };
  }

  return null;
}

// ---------------------------------------------------------------------------
// Public API — overlay onto existing ContentMetadata
// ---------------------------------------------------------------------------

/**
 * Enrich metadata for a single content item. No-op if TMDb is disabled, no
 * API key is configured, or the content type isn't movie/series. The cache
 * absorbs repeat calls. Existing metadata fields take priority — we only
 * fill gaps and layer on TMDb-specific image/tagline fields.
 */
export async function enrichMetadata(
  item: ContentItem,
  currentMetadata: ContentMetadata,
): Promise<ContentMetadata> {
  if (!isTmdbEnabled()) return currentMetadata;
  if (item.type !== 'movie' && item.type !== 'series') return currentMetadata;

  const apiKey = readApiKey();
  if (!apiKey) return currentMetadata;

  const language = getTmdbLanguage();
  const cached = readCache(item.id);

  let payload: CachedPayload | null = null;

  if (cached && isFresh(cached)) {
    if (cached.miss) return currentMetadata;
    if (cached.payload_json) {
      try {
        payload = JSON.parse(cached.payload_json) as CachedPayload;
      } catch {
        payload = null;
      }
    }
  }

  if (!payload) {
    try {
      payload = await performLookup(item, apiKey, language);
    } catch (err) {
      log.warn('TMDb lookup failed:', err);
      payload = null;
    }
    if (payload) {
      writeCacheHit(item.id, payload);
    } else {
      writeCacheMiss(item.id);
      return currentMetadata;
    }
  }

  // Existing metadata always wins for textual fields; only fill gaps.
  return {
    ...currentMetadata,
    plot: currentMetadata.plot || payload.plot,
    cast: currentMetadata.cast || payload.cast,
    director: currentMetadata.director || payload.director,
    genre: currentMetadata.genre || payload.genre,
    rating: currentMetadata.rating || payload.rating,
    releaseDate: currentMetadata.releaseDate || payload.releaseDate,
    tmdbId: payload.tmdbId,
    tmdbType: payload.tmdbType,
    tmdbPosterUrl: payload.poster,
    tmdbBackdropUrl: payload.backdrop,
    tmdbTagline: payload.tagline,
    tmdbEnrichedAt: Date.now(),
  };
}
