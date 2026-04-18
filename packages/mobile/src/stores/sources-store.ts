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
import { fetchHttpClient, fetchTextRaw } from '../http/fetch-http-client';
import { asyncStorageKV } from '../storage/async-storage';
import { getJson, setJson, type KVStore } from '../storage/kv-store';

const KEY_SOURCES = 'yancotv.v1.sources';
const KEY_CHANNELS = 'yancotv.v1.channels';

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

async function persist(sources: MobileSource[], channels: ContentItem[]) {
  await Promise.all([
    setJson(kv, KEY_SOURCES, sources),
    setJson(kv, KEY_CHANNELS, channels),
  ]);
}

async function syncM3u(
  source: M3uSource,
  setMsg: (msg: string) => void,
): Promise<ContentItem[]> {
  setMsg(`Fetching ${source.name}...`);
  const text = await fetchTextRaw(source.url, { timeoutMs: 30_000 });
  setMsg('Parsing playlist...');
  const { entries } = parseM3u(text);
  return entries.map((entry, i) =>
    m3uEntryToItem(entry, source.id, i, classifyEntry(entry)),
  );
}

async function syncXtream(
  source: XtreamSource,
  setMsg: (msg: string) => void,
): Promise<ContentItem[]> {
  const client = new XtreamClient(source.url, source.username, source.password, {
    http: fetchHttpClient,
  });

  setMsg('Authenticating...');
  const auth = await client.authenticate();
  if (!auth.ok) throw auth.error;

  setMsg('Fetching live channels...');
  const liveResult = await client.getLiveStreams();
  if (!liveResult.ok) throw liveResult.error;

  setMsg('Fetching movies...');
  const vodResult = await client.getVodStreams();
  if (!vodResult.ok) throw vodResult.error;

  setMsg('Fetching series...');
  const seriesResult = await client.getSeriesList();
  if (!seriesResult.ok) throw seriesResult.error;

  setMsg('Fetching categories...');
  const [liveCats, vodCats, seriesCats] = await Promise.all([
    client.getLiveCategories(),
    client.getVodCategories(),
    client.getSeriesCategories(),
  ]);

  const catMap = new Map<string, string>();
  for (const r of [liveCats, vodCats, seriesCats]) {
    if (r.ok) {
      for (const c of r.value) catMap.set(c.categoryId, c.categoryName);
    }
  }

  const now = Date.now();
  let sortOrder = 0;
  const items: ContentItem[] = [];

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
    });
  }

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
    });
  }

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
  }

  return items;
}

async function syncStalker(
  source: StalkerSource,
  setMsg: (msg: string) => void,
): Promise<ContentItem[]> {
  const client = new StalkerClient(source.url, source.macAddress, {
    http: fetchHttpClient,
  });

  setMsg('Authenticating with portal...');
  const auth = await client.authenticate();
  if (!auth.ok) throw auth.error;

  setMsg('Fetching live channels...');
  const liveResult = await client.getLiveChannels();
  if (!liveResult.ok) throw liveResult.error;

  setMsg('Fetching movies...');
  const vodResult = await client.getVodItems();
  if (!vodResult.ok) throw vodResult.error;

  setMsg('Fetching series...');
  const seriesResult = await client.getSeriesList();
  if (!seriesResult.ok) throw seriesResult.error;

  setMsg('Fetching categories...');
  const [liveCats, vodCats, seriesCats] = await Promise.all([
    client.getLiveCategories(),
    client.getVodCategories(),
    client.getSeriesCategories(),
  ]);

  const catMap = new Map<string, string>();
  for (const r of [liveCats, vodCats, seriesCats]) {
    if (r.ok) {
      for (const c of r.value) catMap.set(c.id, c.title);
    }
  }

  const now = Date.now();
  let sortOrder = 0;
  const items: ContentItem[] = [];

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
    });
  }

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
    });
  }

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
        seriesId: s.id,
        plot: s.plot,
        genre: s.genre,
      }),
    });
  }

  return items;
}

export const useSourcesStore = create<SourcesState>((set, get) => ({
  sources: [],
  channels: [],
  syncStatus: 'idle',
  hydrated: false,

  hydrate: async () => {
    if (get().hydrated) return;
    const [sources, channels] = await Promise.all([
      getJson<MobileSource[]>(kv, KEY_SOURCES),
      getJson<ContentItem[]>(kv, KEY_CHANNELS),
    ]);
    set({
      sources: sources ?? [],
      channels: channels ?? [],
      hydrated: true,
    });
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
    await persist(get().sources, get().channels);
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
    await persist(get().sources, get().channels);
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
    await persist(get().sources, get().channels);
    await get().resync(source.id);
  },

  removeSource: async (id) => {
    set((s) => ({
      sources: s.sources.filter((src) => src.id !== id),
      channels: s.channels.filter((ch) => ch.sourceId !== id),
    }));
    await persist(get().sources, get().channels);
  },

  resync: async (id) => {
    const source = get().sources.find((s) => s.id === id);
    if (!source) return;

    set({ syncStatus: 'fetching', syncMessage: `Starting ${source.name}...` });

    const setMsg = (msg: string) => set({ syncStatus: 'fetching', syncMessage: msg });

    try {
      let items: ContentItem[];
      if (source.type === 'm3u_url') {
        items = await syncM3u(source, setMsg);
      } else if (source.type === 'xtream') {
        items = await syncXtream(source, setMsg);
      } else {
        items = await syncStalker(source, setMsg);
      }

      set((s) => ({
        channels: [...s.channels.filter((ch) => ch.sourceId !== id), ...items],
        sources: s.sources.map((src) =>
          src.id === id
            ? {
                ...src,
                lastSynced: Date.now(),
                lastError: undefined,
                channelCount: items.length,
              }
            : src,
        ),
        syncStatus: 'done',
        syncMessage: `Synced ${items.length} items`,
      }));
      await persist(get().sources, get().channels);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      set((s) => ({
        sources: s.sources.map((src) =>
          src.id === id ? { ...src, lastError: msg } : src,
        ),
        syncStatus: 'error',
        syncMessage: msg,
      }));
      await persist(get().sources, get().channels);
    }
  },
}));
