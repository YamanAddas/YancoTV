import type { Result } from '../types';
import { NOOP_LOGGER, type Logger } from '../logger';
import { HttpResponseError, type HttpClient } from '../http';

export interface XtreamAuthInfo {
  userInfo: {
    username: string;
    status: string;
    expDate: string | null;
    isTrial: boolean;
    activeCons: number;
    maxConnections: number;
  };
  serverInfo: {
    url: string;
    port: string;
    httpsPort: string | null;
    rtmpPort: string | null;
    serverProtocol: string;
    timeNow: string;
    timezone: string;
  };
}

export interface XtreamCategory {
  categoryId: string;
  categoryName: string;
  parentId: number;
}

export interface XtreamLiveStream {
  num: number;
  name: string;
  streamType: string;
  streamId: number;
  streamIcon: string;
  epgChannelId: string;
  added: string;
  categoryId: string;
  categoryIds: number[];
  customSid: string;
  tvArchive: number;
  directSource: string;
  tvArchiveDuration: number;
}

export interface XtreamVodStream {
  num: number;
  name: string;
  streamType: string;
  streamId: number;
  streamIcon: string;
  rating: string;
  added: string;
  categoryId: string;
  containerExtension: string;
  directSource: string;
}

export interface XtreamSeriesInfo {
  num: number;
  name: string;
  seriesId: number;
  cover: string;
  plot: string;
  cast: string;
  director: string;
  genre: string;
  releaseDate: string;
  rating: string;
  categoryId: string;
  lastModified: string;
}

export interface XtreamSeriesEpisode {
  id: string;
  episodeNum: number;
  title: string;
  containerExtension: string;
  info: {
    duration?: string;
    season?: number;
  };
}

export interface XtreamSeriesDetail {
  seasons: Array<{
    seasonNumber: number;
    name: string;
  }>;
  episodes: Record<string, XtreamSeriesEpisode[]>;
  info: {
    name: string;
    cover: string;
    plot: string;
    cast: string;
    director: string;
    genre: string;
    releaseDate: string;
    rating: string;
  };
}

export interface XtreamVodDetail {
  name: string;
  plot: string;
  cast: string;
  director: string;
  genre: string;
  releaseDate: string;
  rating: string;
  duration: string;
  cover: string;
}

const MAX_RESPONSE_BYTES = 150 * 1024 * 1024;
const MAX_RETRIES = 3;
const RETRY_DELAYS = [1000, 3000, 8000];

function isRetryableError(message: string): boolean {
  return (
    message.includes('timed out') ||
    message.includes('ECONNRESET') ||
    message.includes('ECONNREFUSED') ||
    message.includes('ETIMEDOUT') ||
    message.includes('ENOTFOUND') ||
    message.includes('socket hang up') ||
    message.includes('HTTP 429') ||
    message.includes('HTTP 502') ||
    message.includes('HTTP 503') ||
    message.includes('HTTP 504')
  );
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export interface XtreamClientOptions {
  http: HttpClient;
  logger?: Logger;
  timeoutMs?: number;
}

export class XtreamClient {
  private baseUrl: string;
  private username: string;
  private password: string;
  private http: HttpClient;
  private logger: Logger;
  private timeoutMs: number;

  constructor(url: string, username: string, password: string, options: XtreamClientOptions) {
    this.baseUrl = url.replace(/\/+$/, '').replace(/\/player_api\.php$/, '');
    this.username = username;
    this.password = password;
    this.http = options.http;
    this.logger = options.logger ?? NOOP_LOGGER;
    this.timeoutMs = options.timeoutMs ?? 60_000;
  }

  async authenticate(): Promise<Result<XtreamAuthInfo>> {
    const data = await this.request('get_account_info');
    if (!data.ok) return data;

    const raw = data.value as Record<string, unknown>;
    const userInfo = raw?.user_info as Record<string, unknown> | undefined;
    if (!userInfo) {
      return { ok: false, error: new Error('Invalid auth response: missing user_info') };
    }

    if (userInfo.auth === 0 || userInfo.status === 'Disabled') {
      return { ok: false, error: new Error('Account disabled or invalid credentials') };
    }

    const serverInfo = (raw?.server_info as Record<string, unknown>) ?? {};

    return {
      ok: true,
      value: {
        userInfo: {
          username: String(userInfo.username ?? ''),
          status: String(userInfo.status ?? 'Unknown'),
          expDate: userInfo.exp_date ? String(userInfo.exp_date) : null,
          isTrial: String(userInfo.is_trial) === '1',
          activeCons: Number(userInfo.active_cons) || 0,
          maxConnections: Number(userInfo.max_connections) || 0,
        },
        serverInfo: {
          url: String(serverInfo.url ?? ''),
          port: String(serverInfo.port ?? ''),
          httpsPort: serverInfo.https_port ? String(serverInfo.https_port) : null,
          rtmpPort: serverInfo.rtmp_port ? String(serverInfo.rtmp_port) : null,
          serverProtocol: String(serverInfo.server_protocol ?? 'http'),
          timeNow: String(serverInfo.time_now ?? ''),
          timezone: String(serverInfo.timezone ?? ''),
        },
      },
    };
  }

  async getLiveCategories(): Promise<Result<XtreamCategory[]>> {
    return this.fetchCategories('get_live_categories');
  }

  async getVodCategories(): Promise<Result<XtreamCategory[]>> {
    return this.fetchCategories('get_vod_categories');
  }

  async getSeriesCategories(): Promise<Result<XtreamCategory[]>> {
    return this.fetchCategories('get_series_categories');
  }

  async getLiveStreams(categoryId?: string): Promise<Result<XtreamLiveStream[]>> {
    const extra = categoryId ? `&category_id=${categoryId}` : '';
    const data = await this.request(`get_live_streams${extra}`);
    if (!data.ok) return data;

    const streams: XtreamLiveStream[] = (Array.isArray(data.value) ? data.value : []).map(
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (s: any) => ({
        num: Number(s.num) || 0,
        name: String(s.name ?? ''),
        streamType: String(s.stream_type ?? 'live'),
        streamId: Number(s.stream_id) || 0,
        streamIcon: String(s.stream_icon ?? ''),
        epgChannelId: String(s.epg_channel_id ?? ''),
        added: String(s.added ?? ''),
        categoryId: String(s.category_id ?? ''),
        categoryIds: Array.isArray(s.category_ids) ? s.category_ids.map(Number) : [],
        customSid: String(s.custom_sid ?? ''),
        tvArchive: Number(s.tv_archive) || 0,
        directSource: String(s.direct_source ?? ''),
        tvArchiveDuration: Number(s.tv_archive_duration) || 0,
      }),
    );

    return { ok: true, value: streams };
  }

  async getVodStreams(categoryId?: string): Promise<Result<XtreamVodStream[]>> {
    const extra = categoryId ? `&category_id=${categoryId}` : '';
    const data = await this.request(`get_vod_streams${extra}`);
    if (!data.ok) return data;

    const streams: XtreamVodStream[] = (Array.isArray(data.value) ? data.value : []).map(
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (s: any) => ({
        num: Number(s.num) || 0,
        name: String(s.name ?? ''),
        streamType: String(s.stream_type ?? 'movie'),
        streamId: Number(s.stream_id) || 0,
        streamIcon: String(s.stream_icon ?? ''),
        rating: String(s.rating ?? ''),
        added: String(s.added ?? ''),
        categoryId: String(s.category_id ?? ''),
        containerExtension: String(s.container_extension ?? 'mp4'),
        directSource: String(s.direct_source ?? ''),
      }),
    );

    return { ok: true, value: streams };
  }

  async getSeriesList(categoryId?: string): Promise<Result<XtreamSeriesInfo[]>> {
    const extra = categoryId ? `&category_id=${categoryId}` : '';
    const data = await this.request(`get_series${extra}`);
    if (!data.ok) return data;

    const series: XtreamSeriesInfo[] = (Array.isArray(data.value) ? data.value : []).map(
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (s: any) => ({
        num: Number(s.num) || 0,
        name: String(s.name ?? ''),
        seriesId: Number(s.series_id) || 0,
        cover: String(s.cover ?? ''),
        plot: String(s.plot ?? ''),
        cast: String(s.cast ?? ''),
        director: String(s.director ?? ''),
        genre: String(s.genre ?? ''),
        releaseDate: String(s.releaseDate ?? s.release_date ?? ''),
        rating: String(s.rating ?? ''),
        categoryId: String(s.category_id ?? ''),
        lastModified: String(s.last_modified ?? ''),
      }),
    );

    return { ok: true, value: series };
  }

  async getSeriesInfo(seriesId: number): Promise<Result<XtreamSeriesDetail>> {
    const data = await this.request(`get_series_info&series_id=${seriesId}`);
    if (!data.ok) return data;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const raw = data.value as any;
    const info = raw?.info ?? {};
    const seasons = Array.isArray(raw?.seasons)
      ? // eslint-disable-next-line @typescript-eslint/no-explicit-any
        raw.seasons.map((s: any) => ({
          seasonNumber: Number(s.season_number ?? s.season ?? 0),
          name: String(s.name ?? `Season ${s.season_number ?? 0}`),
        }))
      : [];

    const episodes: Record<string, XtreamSeriesEpisode[]> = {};
    if (raw?.episodes && typeof raw.episodes === 'object') {
      for (const [seasonNum, eps] of Object.entries(raw.episodes)) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        episodes[seasonNum] = (Array.isArray(eps) ? eps : []).map((e: any) => ({
          id: String(e.id ?? ''),
          episodeNum: Number(e.episode_num) || 0,
          title: String(e.title ?? ''),
          containerExtension: String(e.container_extension ?? 'mp4'),
          info: {
            duration: e.info?.duration ? String(e.info.duration) : undefined,
            season: e.info?.season ? Number(e.info.season) : undefined,
          },
        }));
      }
    }

    return {
      ok: true,
      value: {
        seasons,
        episodes,
        info: {
          name: String(info.name ?? ''),
          cover: String(info.cover ?? ''),
          plot: String(info.plot ?? ''),
          cast: String(info.cast ?? ''),
          director: String(info.director ?? ''),
          genre: String(info.genre ?? ''),
          releaseDate: String(info.releaseDate ?? info.release_date ?? ''),
          rating: String(info.rating ?? ''),
        },
      },
    };
  }

  async getVodInfo(vodId: number): Promise<Result<XtreamVodDetail>> {
    const data = await this.request(`get_vod_info&vod_id=${vodId}`);
    if (!data.ok) return data;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const raw = data.value as any;
    const info = raw?.info ?? raw?.movie_data ?? {};

    return {
      ok: true,
      value: {
        name: String(info.name ?? info.title ?? ''),
        plot: String(info.plot ?? info.description ?? ''),
        cast: String(info.cast ?? info.actors ?? ''),
        director: String(info.director ?? ''),
        genre: String(info.genre ?? info.category_name ?? ''),
        releaseDate: String(info.releasedate ?? info.release_date ?? info.releaseDate ?? ''),
        rating: String(info.rating ?? (info.rating_5based ? `${info.rating_5based}/5` : '')),
        duration: String(info.duration ?? info.duration_secs ?? ''),
        cover: String(info.movie_image ?? info.cover_big ?? info.cover ?? ''),
      },
    };
  }

  buildEpgUrl(): string {
    return `${this.baseUrl}/xmltv.php?username=${encodeURIComponent(this.username)}&password=${encodeURIComponent(this.password)}`;
  }

  buildStreamUrl(streamId: number, type: 'live' | 'movie' | 'series', extension?: string): string {
    const defaultExt = type === 'live' ? 'ts' : 'mp4';
    const ext = extension?.trim() || defaultExt;
    const typePath = type === 'live' ? 'live' : type === 'movie' ? 'movie' : 'series';
    return `${this.baseUrl}/${typePath}/${this.username}/${this.password}/${streamId}.${ext}`;
  }

  private async request(action: string): Promise<Result<unknown>> {
    const url = `${this.baseUrl}/player_api.php?username=${encodeURIComponent(this.username)}&password=${encodeURIComponent(this.password)}&action=${action}`;

    for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      try {
        const body = await this.http.getJson(url, {
          timeoutMs: this.timeoutMs,
          maxResponseBytes: MAX_RESPONSE_BYTES,
        });
        return { ok: true, value: body };
      } catch (error) {
        const msg = error instanceof Error ? error.message : String(error);
        const httpMsg =
          error instanceof HttpResponseError ? `HTTP ${error.status}` : msg;

        if (attempt < MAX_RETRIES && isRetryableError(httpMsg)) {
          const delay = RETRY_DELAYS[attempt] ?? 8000;
          this.logger.warn(
            `Xtream API [${action}] attempt ${attempt + 1} failed: ${msg} — retrying in ${delay}ms`,
          );
          await sleep(delay);
          continue;
        }

        this.logger.error(`Xtream API error [${action}]: ${msg}`);
        return {
          ok: false,
          error: error instanceof Error ? error : new Error(String(error)),
        };
      }
    }

    return { ok: false, error: new Error('Max retries exceeded') };
  }

  private async fetchCategories(action: string): Promise<Result<XtreamCategory[]>> {
    const data = await this.request(action);
    if (!data.ok) return data;

    const categories: XtreamCategory[] = (Array.isArray(data.value) ? data.value : []).map(
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (c: any) => ({
        categoryId: String(c.category_id ?? ''),
        categoryName: String(c.category_name ?? ''),
        parentId: Number(c.parent_id) || 0,
      }),
    );

    return { ok: true, value: categories };
  }
}
