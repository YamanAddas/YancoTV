import type { Result } from '../types/index.js';
import { NOOP_LOGGER, type Logger } from '../logger.js';
import { HttpResponseError, type HttpClient } from '../http/index.js';
import {
  extractStalkerHandshakeToken,
  stalkerCategoryItemSchema,
  stalkerCategoryListResponseSchema,
  stalkerChannelItemSchema,
  stalkerChannelPageResponseSchema,
  stalkerSeriesItemSchema,
  stalkerSeriesPageResponseSchema,
  stalkerVodItemSchema,
  stalkerVodPageResponseSchema,
} from './schemas.js';

export interface StalkerAuthInfo {
  token: string;
  portalUrl: string;
  macAddress: string;
}

export interface StalkerCategory {
  id: string;
  title: string;
}

export interface StalkerChannel {
  id: number;
  name: string;
  cmd: string;
  tvGenreId: string;
  logo: string;
  epgId: string;
  number: number;
  tvArchive: number;
  tvArchiveDuration: number;
}

export interface StalkerVodItem {
  id: number;
  name: string;
  cmd: string;
  categoryId: string;
  logo: string;
  description: string;
}

export interface StalkerSeriesItem {
  id: number;
  name: string;
  categoryId: string;
  cover: string;
  plot: string;
  genre: string;
}

const MAX_RESPONSE_BYTES = 150 * 1024 * 1024;
const MAX_RETRIES = 3;
const RETRY_DELAYS = [1000, 3000, 8000];
const MAX_PAGES = 500;

const STALKER_USER_AGENT = 'Mozilla/5.0 (QtEmbedded; U; Linux; C)';
const STALKER_X_USER_AGENT = 'Model: MAG254; Link: Ethernet';

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

export interface StalkerClientOptions {
  http: HttpClient;
  logger?: Logger;
  timeoutMs?: number;
}

export class StalkerClient {
  private portalUrl: string;
  private macAddress: string;
  private http: HttpClient;
  private logger: Logger;
  private timeoutMs: number;
  private token: string | null = null;

  constructor(portalUrl: string, macAddress: string, options: StalkerClientOptions) {
    this.portalUrl = portalUrl.replace(/\/+$/, '');
    this.macAddress = macAddress;
    this.http = options.http;
    this.logger = options.logger ?? NOOP_LOGGER;
    this.timeoutMs = options.timeoutMs ?? 60_000;
  }

  async authenticate(): Promise<Result<StalkerAuthInfo>> {
    const handshakeResult = await this.request('stb', 'handshake', { prehash: '0' });
    if (!handshakeResult.ok) return handshakeResult;

    const token = extractStalkerHandshakeToken(handshakeResult.value);
    if (!token) {
      return { ok: false, error: new Error('Stalker handshake failed: no token received') };
    }

    this.token = token;

    const profileResult = await this.request('stb', 'get_profile');
    if (!profileResult.ok) return profileResult;

    return {
      ok: true,
      value: {
        token: this.token,
        portalUrl: this.portalUrl,
        macAddress: this.macAddress,
      },
    };
  }

  async getLiveCategories(): Promise<Result<StalkerCategory[]>> {
    const data = await this.request('itv', 'get_genres');
    if (!data.ok) return data;

    return { ok: true, value: this.parseCategories(data.value) };
  }

  async getLiveChannels(): Promise<Result<StalkerChannel[]>> {
    const allChannels: StalkerChannel[] = [];
    let totalItems = 0;
    let lastPageReached = 0;

    for (let page = 1; page <= MAX_PAGES; page++) {
      const data = await this.request('itv', 'get_all_channels', { p: String(page) });
      if (!data.ok) return data;

      const { items, totalItems: pageTotal } = this.parsePage(
        data.value,
        stalkerChannelPageResponseSchema,
      );
      for (const raw of items) {
        const parsed = stalkerChannelItemSchema.safeParse(raw);
        if (parsed.success) allChannels.push(parsed.data);
      }

      totalItems = pageTotal || allChannels.length;
      lastPageReached = page;
      if (allChannels.length >= totalItems) break;
    }

    if (lastPageReached === MAX_PAGES && allChannels.length < totalItems) {
      this.logger.warn(
        `Stalker getLiveChannels: hit MAX_PAGES (${MAX_PAGES}) cap before fetching all channels — got ${allChannels.length} of ${totalItems}. Increase MAX_PAGES if portal is legitimate.`,
      );
    }

    return { ok: true, value: allChannels };
  }

  async getVodCategories(): Promise<Result<StalkerCategory[]>> {
    const data = await this.request('vod', 'get_categories');
    if (!data.ok) return data;

    return { ok: true, value: this.parseCategories(data.value) };
  }

  async getVodItems(): Promise<Result<StalkerVodItem[]>> {
    const allItems: StalkerVodItem[] = [];
    let totalItems = 0;
    let lastPageReached = 0;

    for (let page = 1; page <= MAX_PAGES; page++) {
      const data = await this.request('vod', 'get_ordered_list', {
        category: '*',
        p: String(page),
      });
      if (!data.ok) return data;

      const { items, totalItems: pageTotal } = this.parsePage(
        data.value,
        stalkerVodPageResponseSchema,
      );
      for (const raw of items) {
        const parsed = stalkerVodItemSchema.safeParse(raw);
        if (parsed.success) allItems.push(parsed.data);
      }

      totalItems = pageTotal || allItems.length;
      lastPageReached = page;
      if (allItems.length >= totalItems) break;
    }

    if (lastPageReached === MAX_PAGES && allItems.length < totalItems) {
      this.logger.warn(
        `Stalker getVodItems: hit MAX_PAGES (${MAX_PAGES}) cap — got ${allItems.length} of ${totalItems}.`,
      );
    }

    return { ok: true, value: allItems };
  }

  async getSeriesCategories(): Promise<Result<StalkerCategory[]>> {
    const data = await this.request('series', 'get_categories');
    if (!data.ok) return data;

    return { ok: true, value: this.parseCategories(data.value) };
  }

  async getSeriesList(): Promise<Result<StalkerSeriesItem[]>> {
    const allSeries: StalkerSeriesItem[] = [];
    let totalItems = 0;
    let lastPageReached = 0;

    for (let page = 1; page <= MAX_PAGES; page++) {
      const data = await this.request('series', 'get_ordered_list', {
        category: '*',
        p: String(page),
      });
      if (!data.ok) return data;

      const { items, totalItems: pageTotal } = this.parsePage(
        data.value,
        stalkerSeriesPageResponseSchema,
      );
      for (const raw of items) {
        const parsed = stalkerSeriesItemSchema.safeParse(raw);
        if (parsed.success) allSeries.push(parsed.data);
      }

      totalItems = pageTotal || allSeries.length;
      lastPageReached = page;
      if (allSeries.length >= totalItems) break;
    }

    if (lastPageReached === MAX_PAGES && allSeries.length < totalItems) {
      this.logger.warn(
        `Stalker getSeriesList: hit MAX_PAGES (${MAX_PAGES}) cap — got ${allSeries.length} of ${totalItems}.`,
      );
    }

    return { ok: true, value: allSeries };
  }

  /**
   * Parse a Stalker category-listing response. Stalker returns the
   * raw array under `js`; we map each entry through
   * `stalkerCategoryItemSchema` and skip anything that doesn't even
   * fit the loose category shape.
   */
  private parseCategories(raw: unknown): StalkerCategory[] {
    const wrapper = stalkerCategoryListResponseSchema.safeParse(raw);
    if (!wrapper.success) return [];
    const items = wrapper.data.js ?? [];
    const categories: StalkerCategory[] = [];
    for (const item of items) {
      const parsed = stalkerCategoryItemSchema.safeParse(item);
      if (parsed.success) categories.push(parsed.data);
    }
    return categories;
  }

  /**
   * Parse a paginated Stalker response `{ js: { data: [...], total_items: N } }`.
   * Returns the raw items array (caller validates each with the
   * appropriate item schema) and the parsed `total_items` count.
   * Schema mismatch returns `{ items: [], totalItems: 0 }`, matching
   * the pre-schema behaviour of silently skipping bad pages.
   */
  private parsePage(
    raw: unknown,
    schema: typeof stalkerChannelPageResponseSchema,
  ): { items: unknown[]; totalItems: number } {
    const parsed = schema.safeParse(raw);
    if (!parsed.success) return { items: [], totalItems: 0 };
    return {
      items: parsed.data.js?.data ?? [],
      totalItems: parsed.data.js?.total_items ?? 0,
    };
  }

  /** Strip Stalker "cmd" playback prefixes ("ffrt", "ffmpeg", "auto") and return the URL. */
  buildStreamUrl(cmd: string): string {
    return cmd.replace(/^(?:ffrt|ffmpeg|auto)\s+/i, '').trim();
  }

  private async request(
    type: string,
    action: string,
    extraParams?: Record<string, string>,
  ): Promise<Result<unknown>> {
    const params = new URLSearchParams({
      type,
      action,
      JsHttpRequest: '1-xml',
      ...(this.token && { token: this.token }),
      ...extraParams,
    });

    const url = `${this.portalUrl}/server/load.php?${params.toString()}`;

    const headers: Record<string, string> = {
      'User-Agent': STALKER_USER_AGENT,
      Cookie: `mac=${encodeURIComponent(this.macAddress)}; stb_lang=en; timezone=Europe/London`,
      'X-User-Agent': STALKER_X_USER_AGENT,
    };
    if (this.token) {
      headers['Authorization'] = `Bearer ${this.token}`;
    }

    for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      try {
        const body = await this.http.getJson(url, {
          timeoutMs: this.timeoutMs,
          maxResponseBytes: MAX_RESPONSE_BYTES,
          headers,
        });
        return { ok: true, value: body };
      } catch (error) {
        const msg = error instanceof Error ? error.message : String(error);
        const httpMsg =
          error instanceof HttpResponseError ? `HTTP ${error.status}` : msg;

        if (attempt < MAX_RETRIES && isRetryableError(httpMsg)) {
          const delay = RETRY_DELAYS[attempt] ?? 8000;
          this.logger.warn(
            `Stalker API [${type}/${action}] attempt ${attempt + 1} failed: ${msg} — retrying in ${delay}ms`,
          );
          await sleep(delay);
          continue;
        }

        this.logger.error(`Stalker API error [${type}/${action}]: ${msg}`);
        return {
          ok: false,
          error: error instanceof Error ? error : new Error(String(error)),
        };
      }
    }

    return { ok: false, error: new Error('Max retries exceeded') };
  }
}
