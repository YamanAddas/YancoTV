import { app } from 'electron';
import fs from 'fs';
import path from 'path';
import log from 'electron-log/main';
import { APP_NAME, APP_VERSION } from '../../shared/constants';
import { getSetting } from './settings-service';
import { decryptCredential } from './credential-store';

/**
 * OpenSubtitles REST API v1 client — https://opensubtitles.stoplight.io/
 *
 * Anonymous downloads are capped at 5/day per consumer; authenticated users
 * get their personal account quota. We ship our consumer API key; users can
 * optionally add their own username/password in Settings to raise their limit.
 */

const API_BASE = 'https://api.opensubtitles.com/api/v1';
const DEFAULT_API_KEY = 'sC5mcEdjFTLY5lWPr14tYsk69Bc7QkXh';
const REQUEST_TIMEOUT_MS = 15_000;
const DOWNLOAD_TIMEOUT_MS = 45_000;

async function fetchWithTimeout(
  url: string,
  init: RequestInit & { timeoutMs?: number } = {},
): Promise<Response> {
  const { timeoutMs = REQUEST_TIMEOUT_MS, ...rest } = init;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...rest, signal: controller.signal });
  } catch (err) {
    if ((err as { name?: string })?.name === 'AbortError') {
      throw new OpenSubtitlesError(`Request timed out after ${timeoutMs}ms`);
    }
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

export interface OsSearchParams {
  query?: string;
  imdb_id?: string;
  tmdb_id?: number;
  season?: number;
  episode?: number;
  languages?: string; // comma-separated ISO 639-1 codes, e.g. 'en,tr'
  moviehash?: string;
  type?: 'movie' | 'episode' | 'all';
}

export interface OsSubtitleAttrs {
  subtitle_id: string;
  language: string;
  release: string;
  download_count: number;
  hearing_impaired: boolean;
  from_trusted: boolean | null;
  foreign_parts_only: boolean;
  ai_translated: boolean;
  machine_translated: boolean;
  upload_date: string;
  feature_details?: {
    feature_type: string;
    year: number;
    title: string;
    movie_name: string;
    imdb_id?: number;
  };
  files: Array<{
    file_id: number;
    cd_number: number;
    file_name: string;
  }>;
}

export interface OsSubtitleResult {
  id: string;
  type: string;
  attributes: OsSubtitleAttrs;
}

export interface OsSearchResponse {
  data: OsSubtitleResult[];
  total_pages: number;
  total_count: number;
  per_page: number;
  page: number;
}

interface OsDownloadResponse {
  link: string;
  file_name: string;
  requests: number;
  remaining: number;
  message: string;
  reset_time: string;
}

interface OsLoginResponse {
  user: { allowed_downloads: number; level: string };
  token: string;
  status: number;
}

class OpenSubtitlesError extends Error {
  constructor(message: string, public readonly status?: number) {
    super(message);
    this.name = 'OpenSubtitlesError';
  }
}

let cachedToken: string | null = null;
let cachedTokenExpiry = 0;

function getApiKey(): string {
  const override = getSetting('opensubtitles.apiKey');
  return override?.trim() || DEFAULT_API_KEY;
}

function baseHeaders(): Record<string, string> {
  return {
    'Api-Key': getApiKey(),
    'Content-Type': 'application/json',
    Accept: 'application/json',
    // OpenSubtitles requires a unique User-Agent identifying the app + version.
    'User-Agent': `${APP_NAME} v${APP_VERSION}`,
  };
}

async function authHeaders(): Promise<Record<string, string>> {
  const token = await getUserToken();
  const headers = baseHeaders();
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

/**
 * If the user configured OpenSubtitles credentials in Settings, return a JWT.
 * Token is cached in-memory and renewed lazily (server gives ~24h lifetime).
 */
async function getUserToken(): Promise<string | null> {
  const username = getSetting('opensubtitles.username');
  const encPw = getSetting('opensubtitles.password_enc');
  if (!username || !encPw) return null;
  let password: string;
  try {
    password = decryptCredential(Buffer.from(encPw, 'base64'));
  } catch {
    return null;
  }

  if (cachedToken && Date.now() < cachedTokenExpiry) {
    return cachedToken;
  }

  try {
    const res = await fetchWithTimeout(`${API_BASE}/login`, {
      method: 'POST',
      headers: baseHeaders(),
      body: JSON.stringify({ username, password }),
    });
    if (!res.ok) {
      log.warn(`OpenSubtitles login failed: ${res.status}`);
      cachedToken = null;
      return null;
    }
    const data = (await res.json()) as OsLoginResponse;
    cachedToken = data.token;
    // Assume 23h validity, refresh before actual 24h expiry
    cachedTokenExpiry = Date.now() + 23 * 60 * 60 * 1000;
    return cachedToken;
  } catch (err) {
    log.warn('OpenSubtitles login error:', err);
    return null;
  }
}

export async function searchSubtitles(params: OsSearchParams): Promise<OsSubtitleResult[]> {
  const qs = new URLSearchParams();
  if (params.query) qs.set('query', params.query);
  if (params.imdb_id) qs.set('imdb_id', params.imdb_id);
  if (typeof params.tmdb_id === 'number') qs.set('tmdb_id', String(params.tmdb_id));
  if (typeof params.season === 'number') qs.set('season_number', String(params.season));
  if (typeof params.episode === 'number') qs.set('episode_number', String(params.episode));
  if (params.languages) qs.set('languages', params.languages);
  if (params.moviehash) qs.set('moviehash', params.moviehash);
  if (params.type && params.type !== 'all') qs.set('type', params.type);

  const url = `${API_BASE}/subtitles?${qs.toString()}`;
  const res = await fetchWithTimeout(url, { headers: await authHeaders() });
  if (!res.ok) {
    throw new OpenSubtitlesError(
      `Search failed: ${res.status} ${res.statusText}`,
      res.status,
    );
  }
  const data = (await res.json()) as OsSearchResponse;
  return data.data ?? [];
}

/**
 * Request the download link for a file and stream it to a temp path.
 * Returns the local file path that can be passed to `mpv sub-add`.
 */
export async function downloadSubtitle(fileId: number): Promise<{ path: string; remaining: number }> {
  const dlRes = await fetchWithTimeout(`${API_BASE}/download`, {
    method: 'POST',
    headers: await authHeaders(),
    body: JSON.stringify({ file_id: fileId }),
  });
  if (!dlRes.ok) {
    const text = await dlRes.text().catch(() => '');
    throw new OpenSubtitlesError(
      `Download request failed: ${dlRes.status} ${text.slice(0, 200)}`,
      dlRes.status,
    );
  }
  const dl = (await dlRes.json()) as OsDownloadResponse;
  if (!dl.link) {
    throw new OpenSubtitlesError('Download response missing link');
  }

  const fileRes = await fetchWithTimeout(dl.link, { timeoutMs: DOWNLOAD_TIMEOUT_MS });
  if (!fileRes.ok) {
    throw new OpenSubtitlesError(
      `Subtitle file download failed: ${fileRes.status}`,
      fileRes.status,
    );
  }
  const buf = Buffer.from(await fileRes.arrayBuffer());

  const cacheDir = path.join(app.getPath('userData'), 'subtitles-cache');
  await fs.promises.mkdir(cacheDir, { recursive: true });

  // Preserve the server-suggested filename, sanitized, falling back to a
  // stable one based on file_id.
  const safeName = (dl.file_name || `${fileId}.srt`).replace(/[^\w.\- ]+/g, '_');
  const outPath = path.join(cacheDir, `${fileId}-${safeName}`);
  await fs.promises.writeFile(outPath, buf);

  return { path: outPath, remaining: dl.remaining };
}

/** Clear the cached user token (e.g. after credentials change). */
export function invalidateToken(): void {
  cachedToken = null;
  cachedTokenExpiry = 0;
}
