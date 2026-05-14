import { z } from 'zod';
import type {
  XtreamAuthInfo,
  XtreamCategory,
  XtreamLiveStream,
  XtreamVodStream,
  XtreamSeriesInfo,
  XtreamSeriesEpisode,
  XtreamSeriesDetail,
  XtreamSubtitle,
  XtreamVodDetail,
} from './client.js';

/**
 * Zod schemas for the Xtream Codes JSON responses.
 *
 * Xtream Codes' `player_api.php` returns even looser JSON than Stalker:
 * numbers come back as strings, optional fields are sometimes null,
 * sometimes omitted, sometimes `""`. Vendors fork the original PHP
 * heavily, so field names drift (e.g. `releaseDate` vs `release_date`,
 * `movie_image` vs `cover_big` vs `cover`).
 *
 * The schemas mirror that reality:
 *   - `flexNumber` / `flexString` mimic `Number(x) || 0` and
 *     `String(x ?? '')` so the schema-validated value is identical to
 *     what `as any` was producing inline.
 *   - `.passthrough()` keeps unknown vendor fields around.
 *   - Item schemas `.transform()` to the camelCased `Xtream*` types,
 *     including the fallback chains the client was doing manually
 *     (e.g. `releaseDate ?? release_date ?? ''`).
 *   - `getVodInfo` deals with three subtitle shapes (bare URL, lang,
 *     language); the `xtreamVodSubtitleEntrySchema` is a `z.union` of
 *     all three.
 */

const flexNumber = z.preprocess((v) => Number(v) || 0, z.number());
const flexString = z.preprocess((v) => String(v ?? ''), z.string());

// ─── Account / auth ─────────────────────────────────────────────────────

const xtreamUserInfoSchema = z
  .object({
    username: flexString.optional(),
    status: flexString.optional(),
    exp_date: z.unknown().optional(),
    is_trial: z.unknown().optional(),
    active_cons: flexNumber.optional(),
    max_connections: flexNumber.optional(),
    auth: z.unknown().optional(),
  })
  .passthrough();

const xtreamServerInfoSchema = z
  .object({
    url: flexString.optional(),
    port: flexString.optional(),
    https_port: z.unknown().optional(),
    rtmp_port: z.unknown().optional(),
    server_protocol: flexString.optional(),
    time_now: flexString.optional(),
    timezone: flexString.optional(),
  })
  .passthrough();

export const xtreamAccountInfoResponseSchema = z
  .object({
    user_info: xtreamUserInfoSchema.optional(),
    server_info: xtreamServerInfoSchema.optional(),
  })
  .passthrough();

/**
 * Apply the auth-response transform manually rather than via
 * `.transform()` because `authenticate()` returns an error when
 * `user_info` is missing or the account is disabled — we need to
 * branch on the parsed shape before normalising.
 */
export function transformXtreamAuthInfo(
  raw: z.infer<typeof xtreamAccountInfoResponseSchema>,
): XtreamAuthInfo | null {
  const userInfo = raw.user_info;
  if (!userInfo) return null;
  if (userInfo.auth === 0 || userInfo.status === 'Disabled') return null;
  const serverInfo = raw.server_info ?? {};
  return {
    userInfo: {
      username: userInfo.username ?? '',
      status: userInfo.status || 'Unknown',
      expDate: userInfo.exp_date ? String(userInfo.exp_date) : null,
      isTrial: String(userInfo.is_trial) === '1',
      activeCons: userInfo.active_cons ?? 0,
      maxConnections: userInfo.max_connections ?? 0,
    },
    serverInfo: {
      url: serverInfo.url ?? '',
      port: serverInfo.port ?? '',
      httpsPort: serverInfo.https_port ? String(serverInfo.https_port) : null,
      rtmpPort: serverInfo.rtmp_port ? String(serverInfo.rtmp_port) : null,
      serverProtocol: serverInfo.server_protocol || 'http',
      timeNow: serverInfo.time_now ?? '',
      timezone: serverInfo.timezone ?? '',
    },
  };
}

// ─── Categories ─────────────────────────────────────────────────────────

export const xtreamCategoryItemSchema = z
  .object({
    category_id: flexString.optional(),
    category_name: flexString.optional(),
    parent_id: flexNumber.optional(),
  })
  .passthrough()
  .transform(
    (raw): XtreamCategory => ({
      categoryId: raw.category_id ?? '',
      categoryName: raw.category_name ?? '',
      parentId: raw.parent_id ?? 0,
    }),
  );

// ─── Live streams ───────────────────────────────────────────────────────

export const xtreamLiveStreamItemSchema = z
  .object({
    num: flexNumber.optional(),
    name: flexString.optional(),
    stream_type: flexString.optional(),
    stream_id: flexNumber.optional(),
    stream_icon: flexString.optional(),
    epg_channel_id: flexString.optional(),
    added: flexString.optional(),
    category_id: flexString.optional(),
    category_ids: z.array(z.unknown()).optional(),
    custom_sid: flexString.optional(),
    tv_archive: flexNumber.optional(),
    direct_source: flexString.optional(),
    tv_archive_duration: flexNumber.optional(),
  })
  .passthrough()
  .transform(
    (raw): XtreamLiveStream => ({
      num: raw.num ?? 0,
      name: raw.name ?? '',
      streamType: raw.stream_type || 'live',
      streamId: raw.stream_id ?? 0,
      streamIcon: raw.stream_icon ?? '',
      epgChannelId: raw.epg_channel_id ?? '',
      added: raw.added ?? '',
      categoryId: raw.category_id ?? '',
      categoryIds: Array.isArray(raw.category_ids)
        ? raw.category_ids.map((v) => Number(v) || 0)
        : [],
      customSid: raw.custom_sid ?? '',
      tvArchive: raw.tv_archive ?? 0,
      directSource: raw.direct_source ?? '',
      tvArchiveDuration: raw.tv_archive_duration ?? 0,
    }),
  );

// ─── VOD streams ────────────────────────────────────────────────────────

export const xtreamVodStreamItemSchema = z
  .object({
    num: flexNumber.optional(),
    name: flexString.optional(),
    stream_type: flexString.optional(),
    stream_id: flexNumber.optional(),
    stream_icon: flexString.optional(),
    rating: flexString.optional(),
    added: flexString.optional(),
    category_id: flexString.optional(),
    container_extension: flexString.optional(),
    direct_source: flexString.optional(),
  })
  .passthrough()
  .transform(
    (raw): XtreamVodStream => ({
      num: raw.num ?? 0,
      name: raw.name ?? '',
      streamType: raw.stream_type || 'movie',
      streamId: raw.stream_id ?? 0,
      streamIcon: raw.stream_icon ?? '',
      rating: raw.rating ?? '',
      added: raw.added ?? '',
      categoryId: raw.category_id ?? '',
      containerExtension: raw.container_extension || 'mp4',
      directSource: raw.direct_source ?? '',
    }),
  );

// ─── Series list ────────────────────────────────────────────────────────

export const xtreamSeriesItemSchema = z
  .object({
    num: flexNumber.optional(),
    name: flexString.optional(),
    series_id: flexNumber.optional(),
    cover: flexString.optional(),
    plot: flexString.optional(),
    cast: flexString.optional(),
    director: flexString.optional(),
    genre: flexString.optional(),
    releaseDate: flexString.optional(),
    release_date: flexString.optional(),
    rating: flexString.optional(),
    category_id: flexString.optional(),
    last_modified: flexString.optional(),
  })
  .passthrough()
  .transform(
    (raw): XtreamSeriesInfo => ({
      num: raw.num ?? 0,
      name: raw.name ?? '',
      seriesId: raw.series_id ?? 0,
      cover: raw.cover ?? '',
      plot: raw.plot ?? '',
      cast: raw.cast ?? '',
      director: raw.director ?? '',
      genre: raw.genre ?? '',
      releaseDate: raw.releaseDate || raw.release_date || '',
      rating: raw.rating ?? '',
      categoryId: raw.category_id ?? '',
      lastModified: raw.last_modified ?? '',
    }),
  );

// ─── Series detail (seasons, episodes) ──────────────────────────────────

const xtreamSeasonItemSchema = z
  .object({
    season_number: flexNumber.optional(),
    season: flexNumber.optional(),
    name: flexString.optional(),
  })
  .passthrough();

const xtreamEpisodeInfoSchema = z
  .object({
    duration: z.unknown().optional(),
    season: z.unknown().optional(),
  })
  .passthrough();

const xtreamEpisodeItemSchema = z
  .object({
    id: flexString.optional(),
    episode_num: flexNumber.optional(),
    title: flexString.optional(),
    container_extension: flexString.optional(),
    info: xtreamEpisodeInfoSchema.optional(),
  })
  .passthrough()
  .transform(
    (raw): XtreamSeriesEpisode => ({
      id: raw.id ?? '',
      episodeNum: raw.episode_num ?? 0,
      title: raw.title ?? '',
      containerExtension: raw.container_extension || 'mp4',
      info: {
        duration: raw.info?.duration ? String(raw.info.duration) : undefined,
        season: raw.info?.season ? Number(raw.info.season) || undefined : undefined,
      },
    }),
  );

const xtreamSeriesInfoBlockSchema = z
  .object({
    name: flexString.optional(),
    cover: flexString.optional(),
    plot: flexString.optional(),
    cast: flexString.optional(),
    director: flexString.optional(),
    genre: flexString.optional(),
    releaseDate: flexString.optional(),
    release_date: flexString.optional(),
    rating: flexString.optional(),
  })
  .passthrough();

export const xtreamSeriesDetailResponseSchema = z
  .object({
    info: xtreamSeriesInfoBlockSchema.optional(),
    seasons: z.array(xtreamSeasonItemSchema).optional(),
    episodes: z.record(z.array(z.unknown())).optional(),
  })
  .passthrough();

export function transformXtreamSeriesDetail(
  raw: z.infer<typeof xtreamSeriesDetailResponseSchema>,
): XtreamSeriesDetail {
  const info = raw.info ?? {};
  const seasons = (raw.seasons ?? []).map((s) => ({
    seasonNumber: s.season_number ?? s.season ?? 0,
    name: s.name || `Season ${s.season_number ?? s.season ?? 0}`,
  }));

  const episodes: Record<string, XtreamSeriesEpisode[]> = {};
  if (raw.episodes) {
    for (const [seasonNum, eps] of Object.entries(raw.episodes)) {
      const parsedEps: XtreamSeriesEpisode[] = [];
      for (const ep of eps) {
        const result = xtreamEpisodeItemSchema.safeParse(ep);
        if (result.success) parsedEps.push(result.data);
      }
      episodes[seasonNum] = parsedEps;
    }
  }

  return {
    seasons,
    episodes,
    info: {
      name: info.name ?? '',
      cover: info.cover ?? '',
      plot: info.plot ?? '',
      cast: info.cast ?? '',
      director: info.director ?? '',
      genre: info.genre ?? '',
      releaseDate: info.releaseDate || info.release_date || '',
      rating: info.rating ?? '',
    },
  };
}

// ─── VOD detail (movie info) ────────────────────────────────────────────

/**
 * Three subtitle shapes the wild emits:
 *   1. bare URL string  → `"https://.../en.srt"`
 *   2. `{ url|href, language|lang|locale }`
 *   3. `{ url|href }`  (language inferred from URL)
 */
const xtreamVodSubtitleEntrySchema = z.union([
  z.string(),
  z
    .object({
      url: flexString.optional(),
      href: flexString.optional(),
      language: flexString.optional(),
      lang: flexString.optional(),
      locale: flexString.optional(),
    })
    .passthrough(),
]);

function inferLangFromUrl(url: string): string {
  const path = url.toLowerCase().split('?')[0];
  const m = path.match(/[._/-]([a-z]{2,3})\.(?:srt|vtt|ass|ssa)$/i);
  if (m) return m[1];
  return 'und';
}

/** Backdrop is sometimes a single URL, sometimes an array. */
const xtreamBackdropSchema = z.union([z.string(), z.array(z.unknown())]).optional();

const xtreamVodInfoBlockSchema = z
  .object({
    name: flexString.optional(),
    title: flexString.optional(),
    plot: flexString.optional(),
    description: flexString.optional(),
    cast: flexString.optional(),
    actors: flexString.optional(),
    director: flexString.optional(),
    genre: flexString.optional(),
    category_name: flexString.optional(),
    releasedate: flexString.optional(),
    release_date: flexString.optional(),
    releaseDate: flexString.optional(),
    rating: flexString.optional(),
    rating_5based: flexString.optional(),
    duration: flexString.optional(),
    duration_secs: flexString.optional(),
    movie_image: flexString.optional(),
    cover_big: flexString.optional(),
    cover: flexString.optional(),
    backdrop_path: xtreamBackdropSchema,
    backdropPath: xtreamBackdropSchema,
    tagline: flexString.optional(),
    youtube_trailer: flexString.optional(),
    youtubeTrailer: flexString.optional(),
    subtitles: z.array(xtreamVodSubtitleEntrySchema).optional(),
    tmdb_id: z.unknown().optional(),
    tmdb: z.unknown().optional(),
  })
  .passthrough();

export const xtreamVodInfoResponseSchema = z
  .object({
    info: xtreamVodInfoBlockSchema.optional(),
    movie_data: z
      .object({
        tmdb_id: z.unknown().optional(),
      })
      .passthrough()
      .optional(),
    subtitles: z.array(xtreamVodSubtitleEntrySchema).optional(),
  })
  .passthrough();

export function transformXtreamVodDetail(
  raw: z.infer<typeof xtreamVodInfoResponseSchema>,
): XtreamVodDetail {
  // Some providers stash the movie meta under `movie_data`, but we
  // always prefer `info` when both are present.
  const info = raw.info ?? (raw.movie_data as z.infer<typeof xtreamVodInfoBlockSchema>) ?? {};
  const movieData = raw.movie_data ?? {};

  const backdropRaw = info.backdrop_path ?? info.backdropPath;
  const backdropUrl = Array.isArray(backdropRaw)
    ? String(backdropRaw[0] ?? '')
    : String(backdropRaw ?? '');

  const subs: XtreamSubtitle[] = [];
  const rawSubs = info.subtitles ?? raw.subtitles ?? [];
  for (const s of rawSubs) {
    if (typeof s === 'string' && s) {
      subs.push({ language: inferLangFromUrl(s), url: s });
    } else if (s && typeof s === 'object') {
      const url = s.url || s.href || '';
      if (!url) continue;
      const language = s.language || s.lang || s.locale || inferLangFromUrl(url);
      subs.push({ language, url });
    }
  }

  const tmdbRaw = info.tmdb_id ?? info.tmdb ?? movieData.tmdb_id ?? null;
  const tmdbId = tmdbRaw ? Number(tmdbRaw) || null : null;

  // Build a rating string preserving the "5/5"-shaped fallback when only
  // `rating_5based` is present (matches the original inline behaviour).
  const rating = info.rating || (info.rating_5based ? `${info.rating_5based}/5` : '');

  return {
    name: info.name || info.title || '',
    plot: info.plot || info.description || '',
    cast: info.cast || info.actors || '',
    director: info.director ?? '',
    genre: info.genre || info.category_name || '',
    releaseDate: info.releasedate || info.release_date || info.releaseDate || '',
    rating,
    duration: info.duration || info.duration_secs || '',
    cover: info.movie_image || info.cover_big || info.cover || '',
    backdropUrl,
    tagline: info.tagline ?? '',
    youtubeTrailer: info.youtube_trailer || info.youtubeTrailer || '',
    subtitles: subs,
    tmdbId,
  };
}

// ─── Re-exports for client.ts convenience ───────────────────────────────

export type { XtreamAuthInfo, XtreamSeriesDetail, XtreamVodDetail };
