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
import { getJson, type KVStore } from '../storage/kv-store';
import * as sourcesDb from '../db/sources-store';
import * as contentDb from '../db/content-store';

/**
 * Mobile sources-store — Zustand in-memory shape over the op-sqlite persistence
 * layer (`src/db/sources-store.ts` + `src/db/content-store.ts`).
 *
 * Content lives in SQLite. The in-memory `channels` array is a read-through
 * cache we rebuild from the DB on boot and after each resync, so the screens
 * that look up channels synchronously (ChannelListScreen, HomeScreen, player)
 * stay fast and simple. Credentials are hydrated out of the sources table and
 * exposed on the union so the detail-fetch path in `use-content-detail.ts`
 * keeps working without an extra async call.
 *
 * Legacy AsyncStorage keys (`KEY_SOURCES`, `KEY_CHANNELS_LEGACY`) are migrated
 * to SQLite on first hydrate and then removed. `KEY_CHANNELS_LEGACY` was the
 * culprit behind MB-11 (crash on launch with 10k-item JSON blob).
 */

const KEY_SOURCES_LEGACY = 'yancotv.v1.sources';
const KEY_CHANNELS_LEGACY = 'yancotv.v1.channels';
const KEY_SOURCES_MIGRATED = 'yancotv.v2.sources-migrated';

// NOTE: sources-store is deliberately NOT a core store factory (unlike
// favorites/history). The Xtream/Stalker sync path pulls in platform-specific
// HTTP plumbing, preflight ping, and a parser pipeline that only makes sense
// on mobile for now. When desktop grows multi-source sync we can revisit
// extracting the shape (sources CRUD + sync status) into core and inject the
// sync function as a dependency.

export type MobileSourceType = sourcesDb.MobileSourceType;

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
  // Merge a partial metadata patch into an in-memory channel + persist through
  // to SQLite. Used after a detail-screen fetch to hydrate plot/cast/subtitles
  // /episodes without re-syncing the whole provider.
  enrichContent: (id: string, patch: Record<string, unknown>) => void;
}

const kv: KVStore = asyncStorageKV;

function makeId(prefix: string) {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}

function storedToMobile(
  stored: sourcesDb.StoredSource,
  creds: sourcesDb.StoredCredentials,
): MobileSource {
  const base: BaseSource = {
    id: stored.id,
    name: stored.name,
    createdAt: stored.createdAt,
    lastSynced: stored.lastSynced,
    lastError: stored.lastSyncError,
    channelCount: stored.channelCount,
  };
  if (stored.type === 'xtream') {
    return {
      ...base,
      type: 'xtream',
      url: stored.url,
      username: creds.username ?? '',
      password: creds.password ?? '',
    };
  }
  if (stored.type === 'stalker') {
    return {
      ...base,
      type: 'stalker',
      url: stored.url,
      macAddress: creds.macAddress ?? '',
    };
  }
  return { ...base, type: 'm3u_url', url: stored.url };
}

async function loadAllChannels(): Promise<ContentItem[]> {
  // Keep the union of all three types consistent with sortOrder from the DB so
  // ChannelListScreen's provider-order filtering still matches the payload the
  // user picked up at sync time. We fetch per-type and concat — one SELECT per
  // type is cheap on op-sqlite compared to the hex-card render that follows.
  const types: ContentType[] = ['live', 'movie', 'series'];
  const all: ContentItem[] = [];
  for (const type of types) {
    const items = await contentDb.getContentByType(type);
    for (const item of items) all.push(item);
  }
  return all;
}

async function migrateLegacySourcesIfNeeded() {
  const alreadyMigrated = await kv.get(KEY_SOURCES_MIGRATED);
  if (alreadyMigrated === '1') return;

  const legacy = await getJson<MobileSource[]>(kv, KEY_SOURCES_LEGACY);
  if (legacy && legacy.length > 0) {
    // Skip any that already exist in SQLite (re-migration safety).
    const existing = await sourcesDb.getAllSources();
    const existingIds = new Set(existing.map((s) => s.id));
    for (const src of legacy) {
      if (existingIds.has(src.id)) continue;
      await sourcesDb.insertSource({
        id: src.id,
        name: src.name,
        type: src.type,
        url: src.url,
        username: src.type === 'xtream' ? src.username : undefined,
        password: src.type === 'xtream' ? src.password : undefined,
        macAddress: src.type === 'stalker' ? src.macAddress : undefined,
      });
    }
  }

  // Mark migrated BEFORE clearing the legacy keys so a crash mid-cleanup
  // doesn't leave us re-importing forever.
  await kv.set(KEY_SOURCES_MIGRATED, '1');
  try {
    await kv.remove(KEY_SOURCES_LEGACY);
  } catch {
    // noop
  }
  try {
    await kv.remove(KEY_CHANNELS_LEGACY);
  } catch {
    // noop
  }
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

interface SyncResult {
  items: ContentItem[];
  counts: { live: number; movie: number; series: number };
  warnings: string[];
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

    await migrateLegacySourcesIfNeeded();

    const stored = await sourcesDb.getAllSources();
    const sources: MobileSource[] = [];
    for (const s of stored) {
      const creds = await sourcesDb.getSourceCredentials(s.id);
      sources.push(storedToMobile(s, creds));
    }

    const channels = await loadAllChannels();

    set({ sources, channels, hydrated: true });
  },

  addM3uSource: async ({ name, url }) => {
    const id = makeId('src');
    await sourcesDb.insertSource({ id, name, type: 'm3u_url', url });
    const source: M3uSource = {
      id,
      name,
      type: 'm3u_url',
      url,
      createdAt: Date.now(),
      channelCount: 0,
    };
    set((s) => ({ sources: [...s.sources, source] }));
    await get().resync(id);
  },

  addXtreamSource: async ({ name, url, username, password }) => {
    const id = makeId('src');
    await sourcesDb.insertSource({
      id,
      name,
      type: 'xtream',
      url,
      username,
      password,
    });
    const source: XtreamSource = {
      id,
      name,
      type: 'xtream',
      url,
      username,
      password,
      createdAt: Date.now(),
      channelCount: 0,
    };
    set((s) => ({ sources: [...s.sources, source] }));
    await get().resync(id);
  },

  addStalkerSource: async ({ name, url, macAddress }) => {
    const id = makeId('src');
    await sourcesDb.insertSource({
      id,
      name,
      type: 'stalker',
      url,
      macAddress,
    });
    const source: StalkerSource = {
      id,
      name,
      type: 'stalker',
      url,
      macAddress,
      createdAt: Date.now(),
      channelCount: 0,
    };
    set((s) => ({ sources: [...s.sources, source] }));
    await get().resync(id);
  },

  enrichContent: (id, patch) => {
    // Optimistic in-memory update so the detail screen paints immediately.
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
    // Fire-and-forget DB write. A failed merge just means the user pays a
    // re-fetch next open — not worth blocking the UI on.
    void contentDb.patchContentMetadata(id, patch).catch(() => {
      // noop
    });
  },

  removeSource: async (id) => {
    await sourcesDb.deleteSource(id);
    set((s) => ({
      sources: s.sources.filter((src) => src.id !== id),
      channels: s.channels.filter((ch) => ch.sourceId !== id),
    }));
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

      setMsg('Persisting...');
      await contentDb.replaceSourceContent(id, items);
      const lastError = warnings.length ? warnings.join('; ') : null;
      await sourcesDb.updateSourceSync(id, {
        lastSynced: Date.now(),
        channelCount: items.length,
        lastSyncError: lastError,
      });

      const countsStr = `${counts.live} live · ${counts.movie} movies · ${counts.series} series`;
      const finalMsg = warnings.length
        ? `${countsStr} (partial: ${warnings.length} endpoint issue${warnings.length === 1 ? '' : 's'})`
        : `${countsStr}`;

      set((s) => ({
        // Drop the old rows for this source and append the fresh batch.
        channels: [...s.channels.filter((ch) => ch.sourceId !== id), ...items],
        sources: s.sources.map((src) =>
          src.id === id
            ? {
                ...src,
                lastSynced: Date.now(),
                // Surface per-endpoint issues even when the sync "succeeded".
                // Without this, a provider silently dropping VOD looks identical
                // to one with genuinely no VOD catalogue.
                lastError: warnings.length ? warnings.join('; ') : undefined,
                channelCount: items.length,
              }
            : src,
        ),
        syncStatus: 'done',
        syncMessage: finalMsg,
      }));
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      // Best-effort write the error through to the DB. If that fails too
      // (disk full, db locked), swallow — we already have the error we care
      // about and we're about to report it to the user.
      try {
        await sourcesDb.updateSourceSync(id, { lastSyncError: msg });
      } catch {
        // noop
      }
      set((s) => ({
        sources: s.sources.map((src) =>
          src.id === id ? { ...src, lastError: msg } : src,
        ),
        syncStatus: 'error',
        syncMessage: msg,
      }));
    }
  },
}));
