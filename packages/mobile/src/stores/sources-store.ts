import { create } from 'zustand';
import {
  StalkerClient,
  XtreamClient,
  classifyEntry,
  parseM3u,
  type ContentItem,
  type ContentType,
  type M3uEntry,
} from '@yancotv/core';
import { fetchHttpClient, fetchTextRaw, pingHost } from '../http/fetch-http-client';
import { asyncStorageKV } from '../storage/async-storage';
import { getJson, setJson, type KVStore } from '../storage/kv-store';

const KEY_SOURCES = 'yancotv.v1.sources';
// Channels are NOT persisted. On old devices (e.g. Pixel XL) a 10k-item JSON
// blob overruns AsyncStorage's SQLite (SQLITE_FULL, code 12) and crashes the
// app. Sources alone are tiny, so we keep those saved and re-sync on demand.
const KEY_CHANNELS_LEGACY = 'yancotv.v1.channels';

// NOTE: sources-store is deliberately NOT a core store factory (unlike
// favorites/recent-channels). The Xtream/Stalker sync path pulls in
// platform-specific HTTP plumbing, preflight ping, and a parser pipeline that
// only makes sense on mobile for now. When desktop grows multi-source sync we
// can revisit extracting the shape (sources CRUD + sync status) into core and
// inject the sync function as a dependency.

export type MobileSourceType = 'm3u_url' | 'xtream' | 'stalker';

interface BaseSource {
  id: string;
  name: string;
  createdAt: number;
  lastSynced?: number;
  lastError?: string;
  channelCount: number;
}

export interface M3uSource extends BaseSource {
  type: 'm3u_url';
  url: string;
}

export interface XtreamSource extends BaseSource {
  type: 'xtream';
  url: string;
  username: string;
  password: string;
}

export interface StalkerSource extends BaseSource {
  type: 'stalker';
  url: string;
  macAddress: string;
}

export type MobileSource = M3uSource | XtreamSource | StalkerSource;

export type SyncStatus = 'idle' | 'fetching' | 'parsing' | 'done' | 'error';

interface SourcesState {
  sources: MobileSource[];
  channels: ContentItem[];
  syncStatus: SyncStatus;
  syncMessage?: string;
  hydrated: boolean;

  hydrate: () => Promise<void>;
  addM3uSource: (input: { name: string; url: string }) => Promise<void>;
  addXtreamSource: (input: {
    name: string;
    url: string;
    username: string;
    password: string;
  }) => Promise<void>;
  addStalkerSource: (input: {
    name: string;
    url: string;
    macAddress: string;
  }) => Promise<void>;
  removeSource: (id: string) => Promise<void>;
  resync: (id: string) => Promise<void>;
  // Merge a partial metadata patch into an in-memory channel. Used after a
  // detail-screen fetch to hydrate plot/cast/subtitles/episodes without
  // re-syncing the whole provider. Not persisted — enrichment is cheap to
  // re-fetch, and AsyncStorage already chokes on large content payloads.
  enrichContent: (id: string, patch: Record<string, unknown>) => void;
}

const kv: KVStore = asyncStorageKV;

function makeId(prefix: string) {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}

function m3uEntryToItem(
  entry: M3uEntry,
  sourceId: string,
  index: number,
  type: ContentType,
): ContentItem {
  return {
    id: `${sourceId}:${index}`,
    sourceId,
    type,
    title: entry.title || entry.streamUrl,
    groupName: entry.groupTitle || undefined,
    streamUrl: entry.streamUrl,
    logoUrl: entry.tvgLogo || undefined,
    tvgId: entry.tvgId || undefined,
    sortOrder: index,
    createdAt: Date.now(),
  };
}

async function persistSources(sources: MobileSource[]) {
  await setJson(kv, KEY_SOURCES, sources);
}

async function syncM3u(
  source: M3uSource,
  setMsg: (msg: string) => void,
): Promise<SyncResult> {
  setMsg(`Fetching ${source.name}...`);
  const text = await fetchTextRaw(source.url, { timeoutMs: 30_000 });
  setMsg('Parsing playlist...');
  const { entries } = parseM3u(text);
  const counts = { live: 0, movie: 0, series: 0 };
  const items = entries.map((entry, i) => {
    const type = classifyEntry(entry);
    counts[type]++;
    return m3uEntryToItem(entry, source.id, i, type);
  });
  return { items, counts, warnings: [] };
}

interface SyncResult {
  items: ContentItem[];
  counts: { live: number; movie: number; series: number };
  warnings: string[];
}

async function syncXtream(
  source: XtreamSource,
  setMsg: (msg: string) => void,
): Promise<SyncResult> {
  setMsg(`Reaching ${source.name}...`);
  const ping = await pingHost(source.url);
  if (!ping.ok) {
    throw new Error(`Cannot reach server. ${ping.detail}`);
  }

  const client = new XtreamClient(source.url, source.username, source.password, {
    http: fetchHttpClient,
  });

  setMsg('Authenticating...');
  const auth = await client.authenticate();
  if (!auth.ok) throw auth.error;

  setMsg('Fetching catalogs in parallel...');
  const [liveResult, vodResult, seriesResult, liveCats, vodCats, seriesCats] =
    await Promise.all([
      client.getLiveStreams(),
      client.getVodStreams(),
      client.getSeriesList(),
      client.getLiveCategories(),
      client.getVodCategories(),
      client.getSeriesCategories(),
    ]);

  // Per-endpoint resilience: a provider refusing VOD shouldn't wipe live.
  // Capture each failure as a warning but keep whatever succeeded.
  const warnings: string[] = [];
  if (!liveResult.ok) warnings.push(`live: ${liveResult.error.message}`);
  if (!vodResult.ok) warnings.push(`vod: ${vodResult.error.message}`);
  if (!seriesResult.ok) warnings.push(`series: ${seriesResult.error.message}`);
  if (!liveCats.ok) warnings.push(`live-cats: ${liveCats.error.message}`);
  if (!vodCats.ok) warnings.push(`vod-cats: ${vodCats.error.message}`);
  if (!seriesCats.ok) warnings.push(`series-cats: ${seriesCats.error.message}`);

  if (!liveResult.ok && !vodResult.ok && !seriesResult.ok) {
    throw new Error(`All catalog endpoints failed: ${warnings.join('; ')}`);
  }

  const catMap = new Map<string, string>();
  for (const r of [liveCats, vodCats, seriesCats]) {
    if (r.ok) {
      for (const c of r.value) catMap.set(c.categoryId, c.categoryName);
    }
  }

  const now = Date.now();
  let sortOrder = 0;
  const items: ContentItem[] = [];
  const counts = { live: 0, movie: 0, series: 0 };

  if (liveResult.ok) {
    for (const s of liveResult.value) {
      items.push({
        id: `${source.id}:live:${s.streamId}`,
        sourceId: source.id,
        type: 'live',
        title: s.name,
        groupName: catMap.get(s.categoryId),
        streamUrl: client.buildStreamUrl(s.streamId, 'live'),
        logoUrl: s.streamIcon || undefined,
        tvgId: s.epgChannelId || undefined,
        sortOrder: sortOrder++,
        createdAt: now,
        metadataJson: JSON.stringify({
          streamId: s.streamId,
          tvArchive: s.tvArchive,
          tvArchiveDuration: s.tvArchiveDuration,
        }),
      });
      counts.live++;
    }
  }

  if (vodResult.ok) {
    for (const s of vodResult.value) {
      items.push({
        id: `${source.id}:movie:${s.streamId}`,
        sourceId: source.id,
        type: 'movie',
        title: s.name,
        groupName: catMap.get(s.categoryId),
        streamUrl: client.buildStreamUrl(s.streamId, 'movie', s.containerExtension),
        logoUrl: s.streamIcon || undefined,
        sortOrder: sortOrder++,
        createdAt: now,
        metadataJson: JSON.stringify({
          streamId: s.streamId,
          rating: s.rating,
        }),
      });
      counts.movie++;
    }
  }

  if (seriesResult.ok) {
    for (const s of seriesResult.value) {
      items.push({
        id: `${source.id}:series:${s.seriesId}`,
        sourceId: source.id,
        type: 'series',
        title: s.name,
        groupName: catMap.get(s.categoryId),
        streamUrl: '',
        logoUrl: s.cover || undefined,
        sortOrder: sortOrder++,
        createdAt: now,
        metadataJson: JSON.stringify({
          seriesId: s.seriesId,
          plot: s.plot,
          cast: s.cast,
          director: s.director,
          genre: s.genre,
          releaseDate: s.releaseDate,
          rating: s.rating,
        }),
      });
      counts.series++;
    }
  }

  return { items, counts, warnings };
}

async function syncStalker(
  source: StalkerSource,
  setMsg: (msg: string) => void,
): Promise<SyncResult> {
  const client = new StalkerClient(source.url, source.macAddress, {
    http: fetchHttpClient,
  });

  setMsg(`Reaching ${source.name}...`);
  const ping = await pingHost(source.url);
  if (!ping.ok) {
    throw new Error(`Cannot reach server. ${ping.detail}`);
  }

  setMsg('Authenticating with portal...');
  const auth = await client.authenticate();
  if (!auth.ok) throw auth.error;

  setMsg('Fetching catalogs in parallel...');
  const [liveResult, vodResult, seriesResult, liveCats, vodCats, seriesCats] =
    await Promise.all([
      client.getLiveChannels(),
      client.getVodItems(),
      client.getSeriesList(),
      client.getLiveCategories(),
      client.getVodCategories(),
      client.getSeriesCategories(),
    ]);

  const warnings: string[] = [];
  if (!liveResult.ok) warnings.push(`live: ${liveResult.error.message}`);
  if (!vodResult.ok) warnings.push(`vod: ${vodResult.error.message}`);
  if (!seriesResult.ok) warnings.push(`series: ${seriesResult.error.message}`);
  if (!liveCats.ok) warnings.push(`live-cats: ${liveCats.error.message}`);
  if (!vodCats.ok) warnings.push(`vod-cats: ${vodCats.error.message}`);
  if (!seriesCats.ok) warnings.push(`series-cats: ${seriesCats.error.message}`);

  if (!liveResult.ok && !vodResult.ok && !seriesResult.ok) {
    throw new Error(`All catalog endpoints failed: ${warnings.join('; ')}`);
  }

  const catMap = new Map<string, string>();
  for (const r of [liveCats, vodCats, seriesCats]) {
    if (r.ok) {
      for (const c of r.value) catMap.set(c.id, c.title);
    }
  }

  const now = Date.now();
  let sortOrder = 0;
  const items: ContentItem[] = [];
  const counts = { live: 0, movie: 0, series: 0 };

  if (liveResult.ok) {
    for (const ch of liveResult.value) {
      items.push({
        id: `${source.id}:live:${ch.id}`,
        sourceId: source.id,
        type: 'live',
        title: ch.name,
        groupName: catMap.get(ch.tvGenreId),
        streamUrl: client.buildStreamUrl(ch.cmd),
        logoUrl: ch.logo || undefined,
        tvgId: ch.epgId || undefined,
        sortOrder: sortOrder++,
        createdAt: now,
        metadataJson: JSON.stringify({ stalkerId: ch.id }),
      });
      counts.live++;
    }
  }

  if (vodResult.ok) {
    for (const v of vodResult.value) {
      items.push({
        id: `${source.id}:movie:${v.id}`,
        sourceId: source.id,
        type: 'movie',
        title: v.name,
        groupName: catMap.get(v.categoryId),
        streamUrl: client.buildStreamUrl(v.cmd),
        logoUrl: v.logo || undefined,
        sortOrder: sortOrder++,
        createdAt: now,
        metadataJson: JSON.stringify({ stalkerId: v.id }),
      });
      counts.movie++;
    }
  }

  if (seriesResult.ok) {
    for (const s of seriesResult.value) {
      items.push({
        id: `${source.id}:series:${s.id}`,
        sourceId: source.id,
        type: 'series',
        title: s.name,
        groupName: catMap.get(s.categoryId),
        streamUrl: '',
        logoUrl: s.cover || undefined,
        sortOrder: sortOrder++,
        createdAt: now,
        metadataJson: JSON.stringify({
          stalkerId: s.id,
          plot: s.plot,
          genre: s.genre,
        }),
      });
      counts.series++;
    }
  }

  return { items, counts, warnings };
}

export const useSourcesStore = create<SourcesState>((set, get) => ({
  sources: [],
  channels: [],
  syncStatus: 'idle',
  hydrated: false,

  hydrate: async () => {
    if (get().hydrated) return;
    const sources = await getJson<MobileSource[]>(kv, KEY_SOURCES);
    set({
      sources: sources ?? [],
      channels: [],
      hydrated: true,
    });
    // Best-effort cleanup of the legacy channels blob if an older build
    // previously wrote one. Ignore failures.
    try {
      await kv.remove(KEY_CHANNELS_LEGACY);
    } catch {
      // noop
    }
  },

  addM3uSource: async ({ name, url }) => {
    const source: M3uSource = {
      id: makeId('src'),
      name,
      type: 'm3u_url',
      url,
      createdAt: Date.now(),
      channelCount: 0,
    };
    set((s) => ({ sources: [...s.sources, source] }));
    await persistSources(get().sources);
    await get().resync(source.id);
  },

  addXtreamSource: async ({ name, url, username, password }) => {
    const source: XtreamSource = {
      id: makeId('src'),
      name,
      type: 'xtream',
      url,
      username,
      password,
      createdAt: Date.now(),
      channelCount: 0,
    };
    set((s) => ({ sources: [...s.sources, source] }));
    await persistSources(get().sources);
    await get().resync(source.id);
  },

  addStalkerSource: async ({ name, url, macAddress }) => {
    const source: StalkerSource = {
      id: makeId('src'),
      name,
      type: 'stalker',
      url,
      macAddress,
      createdAt: Date.now(),
      channelCount: 0,
    };
    set((s) => ({ sources: [...s.sources, source] }));
    await persistSources(get().sources);
    await get().resync(source.id);
  },

  enrichContent: (id, patch) => {
    set((s) => ({
      channels: s.channels.map((ch) => {
        if (ch.id !== id) return ch;
        let existing: Record<string, unknown> = {};
        if (ch.metadataJson) {
          try {
            existing = JSON.parse(ch.metadataJson) as Record<string, unknown>;
          } catch {
            existing = {};
          }
        }
        const merged = { ...existing, ...patch };
        return { ...ch, metadataJson: JSON.stringify(merged) };
      }),
    }));
  },

  removeSource: async (id) => {
    set((s) => ({
      sources: s.sources.filter((src) => src.id !== id),
      channels: s.channels.filter((ch) => ch.sourceId !== id),
    }));
    await persistSources(get().sources);
  },

  resync: async (id) => {
    const source = get().sources.find((s) => s.id === id);
    if (!source) return;

    set({ syncStatus: 'fetching', syncMessage: `Starting ${source.name}...` });

    const setMsg = (msg: string) => set({ syncStatus: 'fetching', syncMessage: msg });

    try {
      let result: SyncResult;
      if (source.type === 'm3u_url') {
        result = await syncM3u(source, setMsg);
      } else if (source.type === 'xtream') {
        result = await syncXtream(source, setMsg);
      } else {
        result = await syncStalker(source, setMsg);
      }

      const { items, counts, warnings } = result;
      const countsStr = `${counts.live} live · ${counts.movie} movies · ${counts.series} series`;
      const finalMsg = warnings.length
        ? `${countsStr} (partial: ${warnings.length} endpoint issue${warnings.length === 1 ? '' : 's'})`
        : `${countsStr}`;

      set((s) => ({
        channels: [...s.channels.filter((ch) => ch.sourceId !== id), ...items],
        sources: s.sources.map((src) =>
          src.id === id
            ? {
                ...src,
                lastSynced: Date.now(),
                // Surface per-endpoint issues even when the sync "succeeded".
                // Without this, a provider silently dropping VOD looks identical
                // to a provider with genuinely no VOD catalogue.
                lastError: warnings.length ? warnings.join('; ') : undefined,
                channelCount: items.length,
              }
            : src,
        ),
        syncStatus: 'done',
        syncMessage: finalMsg,
      }));
      await persistSources(get().sources);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      set((s) => ({
        sources: s.sources.map((src) =>
          src.id === id ? { ...src, lastError: msg } : src,
        ),
        syncStatus: 'error',
        syncMessage: msg,
      }));
      await persistSources(get().sources);
    }
  },
}));
