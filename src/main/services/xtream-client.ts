import https from 'https';
import http from 'http';
import type { IncomingMessage } from 'http';
import log from 'electron-log/main';
import type { Result } from '../../shared/types/result';

// --- Xtream API response types ---

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

// --- Client ---

export class XtreamClient {
  private baseUrl: string;
  private username: string;
  private password: string;
  private timeout: number;

  constructor(url: string, username: string, password: string, timeout = 30_000) {
    // Normalize base URL: strip trailing slash and /player_api.php if present
    this.baseUrl = url.replace(/\/+$/, '').replace(/\/player_api\.php$/, '');
    this.username = username;
    this.password = password;
    this.timeout = timeout;
  }

  async authenticate(): Promise<Result<XtreamAuthInfo>> {
    const data = await this.request('get_account_info');
    if (!data.ok) return data;

    const raw = data.value;
    if (!raw.user_info) {
      return { ok: false, error: new Error('Invalid auth response: missing user_info') };
    }

    const userInfo = raw.user_info;
    if (userInfo.auth === 0 || userInfo.status === 'Disabled') {
      return { ok: false, error: new Error(`Account disabled or invalid credentials`) };
    }

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
          url: String(raw.server_info?.url ?? ''),
          port: String(raw.server_info?.port ?? ''),
          httpsPort: raw.server_info?.https_port ? String(raw.server_info.https_port) : null,
          rtmpPort: raw.server_info?.rtmp_port ? String(raw.server_info.rtmp_port) : null,
          serverProtocol: String(raw.server_info?.server_protocol ?? 'http'),
          timeNow: String(raw.server_info?.time_now ?? ''),
          timezone: String(raw.server_info?.timezone ?? ''),
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

    const raw = data.value;
    const info = raw.info ?? {};
    const seasons = Array.isArray(raw.seasons)
      ? // eslint-disable-next-line @typescript-eslint/no-explicit-any
        raw.seasons.map((s: any) => ({
          seasonNumber: Number(s.season_number ?? s.season ?? 0),
          name: String(s.name ?? `Season ${s.season_number ?? 0}`),
        }))
      : [];

    const episodes: Record<string, XtreamSeriesEpisode[]> = {};
    if (raw.episodes && typeof raw.episodes === 'object') {
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

  /** Build a playback URL for a stream */
  buildStreamUrl(streamId: number, type: 'live' | 'movie' | 'series', extension?: string): string {
    const ext = extension ?? (type === 'live' ? 'ts' : 'mp4');
    const typePath = type === 'live' ? 'live' : type === 'movie' ? 'movie' : 'series';
    return `${this.baseUrl}/${typePath}/${this.username}/${this.password}/${streamId}.${ext}`;
  }

  // --- Internal ---

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private async request(action: string): Promise<Result<any>> {
    const url = `${this.baseUrl}/player_api.php?username=${encodeURIComponent(this.username)}&password=${encodeURIComponent(this.password)}&action=${action}`;

    try {
      const body = await this.fetchJson(url);
      return { ok: true, value: body };
    } catch (error) {
      log.error(`Xtream API error [${action}]:`, error);
      return {
        ok: false,
        error: error instanceof Error ? error : new Error(String(error)),
      };
    }
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private fetchJson(url: string): Promise<any> {
    return new Promise((resolve, reject) => {
      const callback = (res: IncomingMessage) => {
        if (res.statusCode && (res.statusCode < 200 || res.statusCode >= 300)) {
          reject(new Error(`HTTP ${res.statusCode}: ${res.statusMessage}`));
          return;
        }

        const chunks: Buffer[] = [];
        res.on('data', (chunk: Buffer) => chunks.push(chunk));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf-8');
          try {
            resolve(JSON.parse(text));
          } catch {
            reject(new Error('Invalid JSON response from Xtream API'));
          }
        });
        res.on('error', reject);
      };

      const request = url.startsWith('https')
        ? https.get(url, { timeout: this.timeout }, callback)
        : http.get(url, { timeout: this.timeout }, callback);

      request.on('error', reject);
      request.on('timeout', () => {
        request.destroy();
        reject(new Error('Xtream API request timed out'));
      });
    });
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
