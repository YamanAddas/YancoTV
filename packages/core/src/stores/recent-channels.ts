/**
 * Recent-channels store factory — platform-agnostic.
 *
 * Persistent ring buffer of recently-played LIVE channel IDs. Separate from
 * watch history so both apps can: show a quick-access strip, implement a
 * "last channel" recall shortcut, and auto-play on launch — without having
 * to filter watch history each time.
 *
 * Persistence is injected:
 * - Desktop uses localStorage (synchronous).
 * - Mobile uses AsyncStorage (asynchronous).
 *
 * The adapter hides that difference. Read is a seed value on first use;
 * write is fire-and-forget.
 */

import { create, type StoreApi, type UseBoundStore } from 'zustand';

export interface RecentChannelsAdapter {
  /** Initial list to seed the store on creation. */
  readInitial(): string[];
  /** Persist the current list. Called on every mutation; best-effort. */
  write(ids: string[]): void;
}

export interface RecentChannelsStoreState {
  /** Most-recent-first list of live channel IDs. */
  ids: string[];
  /** Record a channel as "just played". Moves it to the head if already present. */
  record: (id: string) => void;
  /** Remove one ID (e.g. when the channel was deleted from the library). */
  remove: (id: string) => void;
  clear: () => void;
  /** The channel before the current one — used by "last channel" recall. */
  previous: () => string | undefined;
  /** The most recent entry — used for auto-play-on-launch. */
  mostRecent: () => string | undefined;
}

export type RecentChannelsStore = UseBoundStore<StoreApi<RecentChannelsStoreState>>;

export interface RecentChannelsOptions {
  /** Max entries to retain. Default 10. */
  maxEntries?: number;
}

export function createRecentChannelsStore(
  adapter: RecentChannelsAdapter,
  options: RecentChannelsOptions = {},
): RecentChannelsStore {
  const maxEntries = options.maxEntries ?? 10;

  return create<RecentChannelsStoreState>((set, get) => ({
    ids: adapter.readInitial().slice(0, maxEntries),

    record: (id: string) => {
      if (!id) return;
      const current = get().ids;
      const filtered = current.filter((x) => x !== id);
      const next = [id, ...filtered].slice(0, maxEntries);
      adapter.write(next);
      set({ ids: next });
    },

    remove: (id: string) => {
      const next = get().ids.filter((x) => x !== id);
      adapter.write(next);
      set({ ids: next });
    },

    clear: () => {
      adapter.write([]);
      set({ ids: [] });
    },

    previous: () => get().ids[1],
    mostRecent: () => get().ids[0],
  }));
}
