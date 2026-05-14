import path from 'path';
import fs from 'fs';
import dns from 'node:dns/promises';
import net from 'node:net';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import log from 'electron-log/main';
import { getDb } from './db';
import { getSetting } from './settings-service';
import {
  buildMovieNfo,
  buildEpisodeNfo,
  buildTvShowNfo,
} from './nfo-writer';
import { extractEmbeddedSubtitles } from './subtitle-extractor';
import { confinePath, tryConfinePath } from '../utils/safe-path';
import type {
  ContentItem,
  ContentMetadata,
  Episode,
} from '../../shared/types';

/**
 * Fetches the non-video "package" for a completed download:
 *   • poster → {base}-poster.jpg
 *   • backdrop → {base}-fanart.jpg
 *   • .nfo (Kodi/Jellyfin compatible)
 *   • provider-supplied subtitle URLs (Xtream `get_vod_info` sometimes returns
 *     a `subtitles` array)
 *   • embedded subtitles extracted from the video via ffmpeg
 *
 * Everything is best-effort: a fetch failure is logged and skipped, never
 * propagated. The main download is already marked `completed` by the time we
 * start — this runs as a follow-on.
 */

const SETTING_KEY_FETCH_ASSETS = 'download_fetch_assets';
const SETTING_KEY_EXTRACT_SUBS = 'download_extract_subtitles';
const SETTING_KEY_ALLOW_PRIVATE_IPS = 'download_allow_private_ips';
const SETTING_KEY_UA = 'network_user_agent';

function fetchAssetsEnabled(): boolean {
  // Default on; stored as '0' to opt out.
  return getSetting(SETTING_KEY_FETCH_ASSETS) !== '0';
}

function extractSubsEnabled(): boolean {
  return getSetting(SETTING_KEY_EXTRACT_SUBS) !== '0';
}

function getUserAgent(): string {
  const raw = getSetting(SETTING_KEY_UA);
  return raw && raw.trim() ? raw.trim() : 'YancoTV/1.0';
}

// ---------------------------------------------------------------------------
// SSRF guards (mirror download-service — kept in sync by intent, not import,
// to avoid a circular dependency)
// ---------------------------------------------------------------------------

function isBlockedIp(ip: string): boolean {
  if (net.isIPv4(ip)) {
    const [a, b] = ip.split('.').map((p) => parseInt(p, 10));
    if (a === 10) return true;
    if (a === 127) return true;
    if (a === 169 && b === 254) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 0) return true;
    if (a >= 224) return true;
    return false;
  }
  const lower = ip.toLowerCase();
  if (lower === '::1' || lower === '::') return true;
  if (lower.startsWith('fe80:')) return true;
  if (lower.startsWith('fc') || lower.startsWith('fd')) return true;
  if (lower.startsWith('ff')) return true;
  return false;
}

async function assertHostAllowed(url: URL): Promise<void> {
  if (getSetting(SETTING_KEY_ALLOW_PRIVATE_IPS) === '1') return;
  const host = url.hostname;
  if (net.isIP(host)) {
    if (isBlockedIp(host)) throw new Error(`Blocked IP: ${host}`);
    return;
  }
  const addrs = await dns.lookup(host, { all: true });
  for (const a of addrs) {
    if (isBlockedIp(a.address)) throw new Error(`Blocked IP for ${host}: ${a.address}`);
  }
}

function validateUrl(raw: string): URL | null {
  try {
    const u = new URL(raw);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    return u;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Small HTTP helper for asset files (size-capped, not resumable)
// ---------------------------------------------------------------------------

const ASSET_MAX_BYTES = 50 * 1024 * 1024; // 50 MiB — plenty for posters/subs
const ASSET_TIMEOUT_MS = 20_000;

async function downloadToFile(urlString: string, outPath: string): Promise<void> {
  const url = validateUrl(urlString);
  if (!url) throw new Error(`Invalid URL: ${urlString}`);
  await assertHostAllowed(url);

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error('asset timeout')), ASSET_TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      headers: {
        'User-Agent': getUserAgent(),
        Accept: '*/*',
      },
      signal: controller.signal,
      redirect: 'follow',
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const cl = response.headers.get('content-length');
    if (cl && Number(cl) > ASSET_MAX_BYTES) {
      response.body?.cancel().catch(() => undefined);
      throw new Error(`Asset too large: ${cl} bytes`);
    }
    if (!response.body) throw new Error('No body');
    const body = Readable.fromWeb(
      response.body as unknown as Parameters<typeof Readable.fromWeb>[0],
    );
    const tmp = `${outPath}.part`;
    const writer = fs.createWriteStream(tmp);
    let written = 0;
    body.on('data', (chunk: Buffer) => {
      written += chunk.length;
      if (written > ASSET_MAX_BYTES) {
        controller.abort(new Error('asset exceeded cap'));
      }
    });
    await pipeline(body, writer);
    fs.renameSync(tmp, outPath);
  } finally {
    clearTimeout(timer);
  }
}

// ---------------------------------------------------------------------------
// DB lookups — fetch the ContentItem / Episode for a download row.
// ---------------------------------------------------------------------------

interface ContentRow {
  id: string;
  source_id: string;
  type: string;
  title: string;
  clean_title: string | null;
  group_name: string | null;
  stream_url: string;
  logo_url: string | null;
  tvg_id: string | null;
  metadata_json: string | null;
  sort_order: number;
  created_at: number;
}

function contentRowToItem(row: ContentRow): ContentItem {
  return {
    id: row.id,
    sourceId: row.source_id,
    type: row.type as ContentItem['type'],
    title: row.title,
    cleanTitle: row.clean_title ?? undefined,
    groupName: row.group_name ?? undefined,
    streamUrl: row.stream_url,
    logoUrl: row.logo_url ?? undefined,
    tvgId: row.tvg_id ?? undefined,
    metadataJson: row.metadata_json ?? undefined,
    sortOrder: row.sort_order,
    createdAt: row.created_at,
  };
}

function parseMetadata(json: string | null | undefined): ContentMetadata {
  if (!json) return {};
  try {
    return JSON.parse(json) as ContentMetadata;
  } catch {
    return {};
  }
}

interface EpisodeRow {
  id: string;
  content_id: string;
  season_number: number | null;
  episode_number: number | null;
  title: string | null;
  stream_url: string;
  duration: number | null;
}

function episodeRowToEpisode(row: EpisodeRow): Episode {
  return {
    id: row.id,
    contentId: row.content_id,
    seasonNumber: row.season_number ?? undefined,
    episodeNumber: row.episode_number ?? undefined,
    title: row.title ?? undefined,
    streamUrl: row.stream_url,
    duration: row.duration ?? undefined,
  };
}

/**
 * Pull URLs out of a ContentMetadata that look like subtitle files. Xtream
 * puts them in a top-level `subtitles` array (not in our typed interface —
 * we read it opportunistically).
 */
function extractProviderSubtitles(metadata: ContentMetadata): Array<{ url: string; lang?: string }> {
  const raw = (metadata as unknown as { subtitles?: unknown }).subtitles;
  if (!Array.isArray(raw)) return [];
  const out: Array<{ url: string; lang?: string }> = [];
  for (const entry of raw) {
    if (typeof entry === 'string') {
      out.push({ url: entry });
    } else if (entry && typeof entry === 'object') {
      const e = entry as { url?: unknown; href?: unknown; src?: unknown; language?: unknown; lang?: unknown };
      const url = (typeof e.url === 'string' && e.url)
        || (typeof e.href === 'string' && e.href)
        || (typeof e.src === 'string' && e.src)
        || '';
      if (!url) continue;
      const lang = typeof e.language === 'string' ? e.language : typeof e.lang === 'string' ? e.lang : undefined;
      out.push({ url, lang });
    }
  }
  return out;
}

function subtitleExtFromUrl(u: URL): string {
  const ext = path.extname(u.pathname).toLowerCase();
  return /^\.(srt|vtt|ass|ssa|sub)$/.test(ext) ? ext : '.srt';
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

export interface AssetFetchContext {
  /** Final video path on disk (already renamed from .part to final). */
  videoPath: string;
  /** Our downloads DB row — used to resolve content / episode. */
  contentId?: string;
  episodeId?: string;
  /** Optional override: pass these directly to skip DB lookups (tests). */
  item?: ContentItem;
  metadata?: ContentMetadata;
  episode?: Episode;
}

export interface AssetFetchResult {
  poster?: string;
  backdrop?: string;
  nfo?: string;
  providerSubtitles: string[];
  extractedSubtitles: string[];
  errors: string[];
}

export async function fetchAssetsForDownload(ctx: AssetFetchContext): Promise<AssetFetchResult> {
  const result: AssetFetchResult = {
    providerSubtitles: [],
    extractedSubtitles: [],
    errors: [],
  };

  if (!fs.existsSync(ctx.videoPath)) {
    result.errors.push(`Video not found: ${ctx.videoPath}`);
    return result;
  }

  let item = ctx.item;
  let metadata = ctx.metadata;
  let episode = ctx.episode;

  if (!item && ctx.contentId) {
    try {
      const row = getDb()
        .prepare('SELECT * FROM content WHERE id = ?')
        .get(ctx.contentId) as ContentRow | undefined;
      if (row) {
        item = contentRowToItem(row);
        metadata = parseMetadata(row.metadata_json);
      }
    } catch (err) {
      log.warn('asset-fetcher: content lookup failed:', err);
    }
  }
  if (!episode && ctx.episodeId) {
    try {
      const row = getDb()
        .prepare('SELECT * FROM episodes WHERE id = ?')
        .get(ctx.episodeId) as EpisodeRow | undefined;
      if (row) episode = episodeRowToEpisode(row);
    } catch (err) {
      log.warn('asset-fetcher: episode lookup failed:', err);
    }
  }

  const dir = path.dirname(ctx.videoPath);
  const baseNoExt = path.basename(ctx.videoPath, path.extname(ctx.videoPath));

  const assetsOn = fetchAssetsEnabled();

  // Every output path below is funnelled through `confinePath(dir, …)`
  // before any write. `dir` is the parent of an already-confined
  // download file (uniqueFilePath uses confinePath), and `baseNoExt`
  // is `path.basename(videoPath)` which strips traversal segments —
  // but defence in depth is cheap and keeps the path-traversal
  // detectors honest.

  // ─── Poster ────────────────────────────────────────────────────────────
  if (assetsOn) {
    const posterUrl = metadata?.tmdbPosterUrl || item?.logoUrl;
    if (posterUrl) {
      try {
        const out = confinePath(dir, `${baseNoExt}-poster${posterExt(posterUrl)}`);
        await downloadToFile(posterUrl, out);
        result.poster = out;
      } catch (err) {
        result.errors.push(`poster: ${String((err as Error).message)}`);
      }
    }

    // ─── Backdrop ───────────────────────────────────────────────────────
    const backdropUrl = metadata?.tmdbBackdropUrl;
    if (backdropUrl) {
      try {
        const out = confinePath(dir, `${baseNoExt}-fanart${posterExt(backdropUrl)}`);
        await downloadToFile(backdropUrl, out);
        result.backdrop = out;
      } catch (err) {
        result.errors.push(`backdrop: ${String((err as Error).message)}`);
      }
    }

    // ─── NFO ─────────────────────────────────────────────────────────────
    try {
      const nfoPath = confinePath(dir, `${baseNoExt}.nfo`);
      let xml: string;
      if (episode && item) {
        xml = buildEpisodeNfo({
          showTitle: item.cleanTitle || item.title,
          episode,
          metadata,
        });
        // Also drop a tvshow.nfo next to it if none exists (Kodi picks it up).
        const tvshowPath = confinePath(dir, 'tvshow.nfo');
        if (!fs.existsSync(tvshowPath)) {
          fs.writeFileSync(
            tvshowPath,
            buildTvShowNfo({ title: item.cleanTitle || item.title, metadata }),
            'utf8',
          );
        }
      } else {
        const title = item?.cleanTitle || item?.title || baseNoExt;
        xml = buildMovieNfo({ title, originalTitle: item?.title, metadata });
      }
      fs.writeFileSync(nfoPath, xml, 'utf8');
      result.nfo = nfoPath;
    } catch (err) {
      result.errors.push(`nfo: ${String((err as Error).message)}`);
    }

    // ─── Provider subtitles ─────────────────────────────────────────────
    if (metadata) {
      const used = new Set<string>();
      const providerSubs = extractProviderSubtitles(metadata);
      for (const sub of providerSubs) {
        const u = validateUrl(sub.url);
        if (!u) continue;
        const lang = (sub.lang && /^[a-z]{2,3}$/i.test(sub.lang) ? sub.lang : 'und').toLowerCase();
        const ext = subtitleExtFromUrl(u);
        let candidate = `${baseNoExt}.${lang}${ext}`;
        let n = 2;
        let probe = tryConfinePath(dir, candidate);
        while (!probe || used.has(candidate.toLowerCase()) || fs.existsSync(probe)) {
          candidate = `${baseNoExt}.${lang}.${n}${ext}`;
          n++;
          probe = tryConfinePath(dir, candidate);
          if (n > 9999) break;
        }
        if (!probe) {
          result.errors.push(`provider-sub (${lang}): could not produce safe filename`);
          continue;
        }
        used.add(candidate.toLowerCase());
        try {
          await downloadToFile(sub.url, probe);
          result.providerSubtitles.push(probe);
        } catch (err) {
          result.errors.push(`provider-sub (${lang}): ${String((err as Error).message)}`);
        }
      }
    }
  }

  // ─── Embedded subtitle extraction ─────────────────────────────────────
  if (extractSubsEnabled()) {
    try {
      const extracted = await extractEmbeddedSubtitles(ctx.videoPath);
      result.extractedSubtitles = extracted.extracted;
      for (const skip of extracted.skipped) {
        result.errors.push(`sub-skip (${skip.language}/${skip.codec}): ${skip.reason}`);
      }
    } catch (err) {
      result.errors.push(`extract: ${String((err as Error).message)}`);
    }
  }

  return result;
}

function posterExt(url: string): string {
  try {
    const u = new URL(url);
    const ext = path.extname(u.pathname).toLowerCase();
    if (/^\.(jpg|jpeg|png|webp|gif)$/.test(ext)) return ext;
  } catch {
    // fall through
  }
  return '.jpg';
}

export const __testing = { extractProviderSubtitles, posterExt, subtitleExtFromUrl };
