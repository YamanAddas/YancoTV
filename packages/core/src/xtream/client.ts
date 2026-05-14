import type { Result } from '../types/index.js';
import { NOOP_LOGGER, type Logger } from '../logger.js';
import { HttpResponseError, type HttpClient } from '../http/index.js';
import {
  xtreamAccountInfoResponseSchema,
  transformXtreamAuthInfo,
  xtreamCategoryItemSchema,
  xtreamLiveStreamItemSchema,
  xtreamVodStreamItemSchema,
  xtreamSeriesItemSchema,
  xtreamSeriesDetailResponseSchema,
  transformXtreamSeriesDetail,
  xtreamVodInfoResponseSchema,
  transformXtreamVodDetail,
} from './schemas.js';

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

export interface XtreamSubtitle {
  language: string;
  url: string;
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
  backdropUrl: string;
  tagline: string;
  youtubeTrailer: string;
  subtitles: XtreamSubtitle[];
  tmdbId: number | null;
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

    const parsed = xtreamAccountInfoResponseSchema.safeParse(data.value);
    if (!parsed.success) {
      return { ok: false, error: new Error('Invalid auth response: malformed shape') };
    }
    if (!parsed.data.user_info) {
      return { ok: false, error: new Error('Invalid auth response: missing user_info') };
    }
    const authInfo = transformXtreamAuthInfo(parsed.data);
    if (!authInfo) {
      return { ok: false, error: new Error('Account disabled or invalid credentials') };
    }
    return { ok: true, value: authInfo };
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

    return { ok: true, value: this.parseList(data.value, xtreamLiveStreamItemSchema) };
  }

  async getVodStreams(categoryId?: string): Promise<Result<XtreamVodStream[]>> {
    const extra = categoryId ? `&category_id=${categoryId}` : '';
    const data = await this.request(`get_vod_streams${extra}`);
    if (!data.ok) return data;

    return { ok: true, value: this.parseList(data.value, xtreamVodStreamItemSchema) };
  }

  async getSeriesList(categoryId?: string): Promise<Result<XtreamSeriesInfo[]>> {
    const extra = categoryId ? `&category_id=${categoryId}` : '';
    const data = await this.request(`get_series${extra}`);
    if (!data.ok) return data;

    return { ok: true, value: this.parseList(data.value, xtreamSeriesItemSchema) };
  }

  async getSeriesInfo(seriesId: number): Promise<Result<XtreamSeriesDetail>> {
    const data = await this.request(`get_series_info&series_id=${seriesId}`);
    if (!data.ok) return data;

    const parsed = xtreamSeriesDetailResponseSchema.safeParse(data.value);
    if (!parsed.success) {
      return {
        ok: false,
        error: new Error('Invalid series-info response: malformed shape'),
      };
    }
    return { ok: true, value: transformXtreamSeriesDetail(parsed.data) };
  }

  async getVodInfo(vodId: number): Promise<Result<XtreamVodDetail>> {
    const data = await this.request(`get_vod_info&vod_id=${vodId}`);
    if (!data.ok) return data;

    const parsed = xtreamVodInfoResponseSchema.safeParse(data.value);
    if (!parsed.success) {
      return {
        ok: false,
        error: new Error('Invalid VOD-info response: malformed shape'),
      };
    }
    return { ok: true, value: transformXtreamVodDetail(parsed.data) };
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

    return { ok: true, value: this.parseList(data.value, xtreamCategoryItemSchema) };
  }

  /**
   * Apply an item-level Zod schema to a top-level array response,
   * skipping individual entries that fail validation. Preserves the
   * pre-schema "tolerant on bad shape" behaviour of returning an
   * empty list when the wrapping value isn't even an array.
   */
  private parseList<T>(
    raw: unknown,
    schema: { safeParse: (v: unknown) => { success: boolean; data?: T } },
  ): T[] {
    if (!Array.isArray(raw)) return [];
    const out: T[] = [];
    for (const item of raw) {
      const parsed = schema.safeParse(item);
      if (parsed.success && parsed.data !== undefined) out.push(parsed.data);
    }
    return out;
  }
}
