import https from 'https';
import http from 'http';
import type { IncomingMessage } from 'http';
import log from 'electron-log/main';
import type { Result } from '../../shared/types/result';

// --- Stalker Portal API response types ---

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

// --- Constants ---

const MAX_RESPONSE_SIZE = 150 * 1024 * 1024;
const MAX_RETRIES = 3;
const RETRY_DELAYS = [1000, 3000, 8000];
const MAX_PAGES = 500; // Safety limit for pagination

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

// --- Client ---

export class StalkerClient {
  private portalUrl: string;
  private macAddress: string;
  private timeout: number;
  private token: string | null = null;
  constructor(portalUrl: string, macAddress: string, timeout = 60_000) {
    // Normalize: strip trailing slash, ensure /stalker_portal or /c/ base path is handled
    this.portalUrl = portalUrl.replace(/\/+$/, '');
    this.macAddress = macAddress;
    this.timeout = timeout;
  }

  async authenticate(): Promise<Result<StalkerAuthInfo>> {
    // Step 1: Handshake to get token
    const handshakeResult = await this.request('stb', 'handshake', { prehash: '0' });
    if (!handshakeResult.ok) return handshakeResult;

    const tokenData = handshakeResult.value;
    const token = tokenData?.js?.token ?? tokenData?.token;
    if (!token || typeof token !== 'string') {
      return { ok: false, error: new Error('Stalker handshake failed: no token received') };
    }

    this.token = token;

    // Step 2: Get profile to validate MAC authorization
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

    const raw = data.value?.js ?? [];
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

    for (let page = 1; page <= MAX_PAGES; page++) {
      const data = await this.request('itv', 'get_all_channels', { p: String(page) });
      if (!data.ok) return data;

      const js = data.value?.js;
      const items = js?.data ?? [];

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      for (const ch of (Array.isArray(items) ? items : [])) {
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

      const totalItems = Number(js?.total_items) || allChannels.length;
      if (allChannels.length >= totalItems) break;
    }

    return { ok: true, value: allChannels };
  }

  async getVodCategories(): Promise<Result<StalkerCategory[]>> {
    const data = await this.request('vod', 'get_categories');
    if (!data.ok) return data;

    const raw = data.value?.js ?? [];
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

    for (let page = 1; page <= MAX_PAGES; page++) {
      const data = await this.request('vod', 'get_ordered_list', {
        category: '*',
        p: String(page),
      });
      if (!data.ok) return data;

      const js = data.value?.js;
      const items = js?.data ?? [];

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      for (const v of (Array.isArray(items) ? items : [])) {
        allItems.push({
          id: Number(v.id) || 0,
          name: String(v.name ?? ''),
          cmd: String(v.cmd ?? ''),
          categoryId: String(v.category_id ?? ''),
          logo: String(v.screenshot_uri ?? v.logo ?? ''),
          description: String(v.description ?? ''),
        });
      }

      const totalItems = Number(js?.total_items) || allItems.length;
      if (allItems.length >= totalItems) break;
    }

    return { ok: true, value: allItems };
  }

  async getSeriesCategories(): Promise<Result<StalkerCategory[]>> {
    const data = await this.request('series', 'get_categories');
    if (!data.ok) return data;

    const raw = data.value?.js ?? [];
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

    for (let page = 1; page <= MAX_PAGES; page++) {
      const data = await this.request('series', 'get_ordered_list', {
        category: '*',
        p: String(page),
      });
      if (!data.ok) return data;

      const js = data.value?.js;
      const items = js?.data ?? [];

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      for (const s of (Array.isArray(items) ? items : [])) {
        allSeries.push({
          id: Number(s.id) || 0,
          name: String(s.name ?? ''),
          categoryId: String(s.category_id ?? ''),
          cover: String(s.screenshot_uri ?? s.cover ?? ''),
          plot: String(s.description ?? ''),
          genre: String(s.genre ?? ''),
        });
      }

      const totalItems = Number(js?.total_items) || allSeries.length;
      if (allSeries.length >= totalItems) break;
    }

    return { ok: true, value: allSeries };
  }

  /** Extract the playback URL from a Stalker cmd string.
   *  Stalker uses `cmd` like "ffrt http://..." or just the URL. */
  buildStreamUrl(cmd: string): string {
    // Remove common prefixes: "ffrt ", "ffmpeg ", etc.
    return cmd.replace(/^(?:ffrt|ffmpeg|auto)\s+/i, '').trim();
  }

  // --- Internal ---

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private async request(type: string, action: string, extraParams?: Record<string, string>): Promise<Result<any>> {
    const params = new URLSearchParams({
      type,
      action,
      JsHttpRequest: '1-xml',
      ...(this.token && { token: this.token }),
      ...extraParams,
    });

    const url = `${this.portalUrl}/server/load.php?${params.toString()}`;

    for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      try {
        const body = await this.fetchJson(url);
        return { ok: true, value: body };
      } catch (error) {
        const msg = error instanceof Error ? error.message : String(error);

        if (attempt < MAX_RETRIES && isRetryableError(msg)) {
          const delay = RETRY_DELAYS[attempt] ?? 8000;
          log.warn(`Stalker API [${type}/${action}] attempt ${attempt + 1} failed: ${msg} — retrying in ${delay}ms`);
          await sleep(delay);
          continue;
        }

        log.error(`Stalker API error [${type}/${action}]:`, error);
        return {
          ok: false,
          error: error instanceof Error ? error : new Error(String(error)),
        };
      }
    }

    return { ok: false, error: new Error('Max retries exceeded') };
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private fetchJson(url: string): Promise<any> {
    return new Promise((resolve, reject) => {
      const headers: Record<string, string> = {
        'User-Agent': 'Mozilla/5.0 (QtEmbedded; U; Linux; C)',
        Cookie: `mac=${encodeURIComponent(this.macAddress)}; stb_lang=en; timezone=Europe/London`,
        'X-User-Agent': 'Model: MAG254; Link: Ethernet',
      };

      if (this.token) {
        headers['Authorization'] = `Bearer ${this.token}`;
      }

      const callback = (res: IncomingMessage) => {
        if (res.statusCode && [301, 302, 307, 308].includes(res.statusCode) && res.headers.location) {
          this.fetchJson(res.headers.location).then(resolve, reject);
          return;
        }

        if (res.statusCode && (res.statusCode < 200 || res.statusCode >= 300)) {
          reject(new Error(`HTTP ${res.statusCode}: ${res.statusMessage}`));
          return;
        }

        let receivedBytes = 0;
        const chunks: Buffer[] = [];

        res.on('data', (chunk: Buffer) => {
          receivedBytes += chunk.length;
          if (receivedBytes > MAX_RESPONSE_SIZE) {
            res.destroy();
            reject(new Error(`Response exceeded ${Math.round(MAX_RESPONSE_SIZE / 1024 / 1024)}MB limit`));
            return;
          }
          chunks.push(chunk);
        });

        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf-8');
          try {
            resolve(JSON.parse(text));
          } catch {
            const preview = text.slice(0, 200);
            reject(new Error(`Invalid JSON from Stalker API: ${preview}`));
          }
        });

        res.on('error', reject);
      };

      const options = {
        timeout: this.timeout,
        headers,
      };

      const request = url.startsWith('https')
        ? https.get(url, options, callback)
        : http.get(url, options, callback);

      request.on('error', reject);
      request.on('timeout', () => {
        request.destroy();
        reject(new Error('Stalker API request timed out'));
      });
    });
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
