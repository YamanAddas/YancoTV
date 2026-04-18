import type { Result } from '../types';
import { NOOP_LOGGER, type Logger } from '../logger';
import { HttpResponseError, type HttpClient } from '../http';

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

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const tokenData = handshakeResult.value as any;
    const token = tokenData?.js?.token ?? tokenData?.token;
    if (!token || typeof token !== 'string') {
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

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const raw = (data.value as any)?.js ?? [];
    const categories: StalkerCategory[] = (Array.isArray(raw) ? raw : []).map(
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (c: any) => ({
        id: String(c.id ?? ''),
        title: String(c.title ?? c.name ?? ''),
      }),
    );

    return { ok: true, value: categories };
  }

  async getLiveChannels(): Promise<Result<StalkerChannel[]>> {
    const allChannels: StalkerChannel[] = [];
    let totalItems = 0;
    let lastPageReached = 0;

    for (let page = 1; page <= MAX_PAGES; page++) {
      const data = await this.request('itv', 'get_all_channels', { p: String(page) });
      if (!data.ok) return data;

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const js = (data.value as any)?.js;
      const items = js?.data ?? [];

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      for (const ch of Array.isArray(items) ? items : []) {
        allChannels.push({
          id: Number(ch.id) || 0,
          name: String(ch.name ?? ''),
          cmd: String(ch.cmd ?? ''),
          tvGenreId: String(ch.tv_genre_id ?? ''),
          logo: String(ch.logo ?? ''),
          epgId: String(ch.epg_channel_id ?? ch.xmltv_id ?? ''),
          number: Number(ch.number) || 0,
          tvArchive: Number(ch.tv_archive) || 0,
          tvArchiveDuration: Number(ch.tv_archive_duration) || 0,
        });
      }

      totalItems = Number(js?.total_items) || allChannels.length;
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

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const raw = (data.value as any)?.js ?? [];
    const categories: StalkerCategory[] = (Array.isArray(raw) ? raw : []).map(
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (c: any) => ({
        id: String(c.id ?? ''),
        title: String(c.title ?? c.name ?? ''),
      }),
    );

    return { ok: true, value: categories };
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

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const js = (data.value as any)?.js;
      const items = js?.data ?? [];

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      for (const v of Array.isArray(items) ? items : []) {
        allItems.push({
          id: Number(v.id) || 0,
          name: String(v.name ?? ''),
          cmd: String(v.cmd ?? ''),
          categoryId: String(v.category_id ?? ''),
          logo: String(v.screenshot_uri ?? v.logo ?? ''),
          description: String(v.description ?? ''),
        });
      }

      totalItems = Number(js?.total_items) || allItems.length;
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

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const raw = (data.value as any)?.js ?? [];
    const categories: StalkerCategory[] = (Array.isArray(raw) ? raw : []).map(
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (c: any) => ({
        id: String(c.id ?? ''),
        title: String(c.title ?? c.name ?? ''),
      }),
    );

    return { ok: true, value: categories };
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

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const js = (data.value as any)?.js;
      const items = js?.data ?? [];

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      for (const s of Array.isArray(items) ? items : []) {
        allSeries.push({
          id: Number(s.id) || 0,
          name: String(s.name ?? ''),
          categoryId: String(s.category_id ?? ''),
          cover: String(s.screenshot_uri ?? s.cover ?? ''),
          plot: String(s.description ?? ''),
          genre: String(s.genre ?? ''),
        });
      }

      totalItems = Number(js?.total_items) || allSeries.length;
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
