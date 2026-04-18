/**
 * Recent-channels store factory — platform-agnostic.
 *
 * Persistent ring buffer of recently-played LIVE channel IDs. Separate from
 * watch history so both apps can: show a quick-access strip, implement a
 * "last channel" recall shortcut, and auto-play on launch.
 *
 * The adapter is async on both read and write so mobile (AsyncStorage) can
 * consume it directly. Desktop (localStorage) wraps its sync calls in
 * resolved Promises. Stores start empty and the UI calls `hydrate()` once
 * at boot (typically inside the App-level hydration gate).
 */

import { create, type StoreApi, type UseBoundStore } from 'zustand';

export interface RecentChannelsAdapter {
  /** Load the persisted list. Called once by `hydrate()`. */
  read(): Promise<string[]>;
  /**
   * Persist the current list. Called on every mutation. The store awaits
   * the result and surfaces errors via the `lastError` field so callers
   * can decide whether to retry or ignore.
   */
  write(ids: string[]): Promise<void>;
}

export interface RecentChannelsStoreState {
  /** Most-recent-first list of live channel IDs. Empty until `hydrate()`. */
  ids: string[];
  /** True once `hydrate()` has completed at least once. */
  hydrated: boolean;
  /** Last error from a read or write; cleared on the next successful call. */
  lastError: Error | null;
  /** Load persisted IDs. Idempotent — safe to call twice (e.g. under fast refresh). */
  hydrate: () => Promise<void>;
  /** Record a channel as "just played". Moves it to the head if already present. */
  record: (id: string) => Promise<void>;
  /** Remove one ID (e.g. when the channel was deleted from the library). */
  remove: (id: string) => Promise<void>;
  clear: () => Promise<void>;
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

  return create<RecentChannelsStoreState>((set, get) => {
    async function persist(next: string[]): Promise<void> {
      try {
        await adapter.write(next);
        if (get().lastError) set({ lastError: null });
      } catch (err) {
        set({ lastError: err instanceof Error ? err : new Error(String(err)) });
      }
    }

    return {
      ids: [],
      hydrated: false,
      lastError: null,

      hydrate: async () => {
        try {
          const ids = (await adapter.read()).slice(0, maxEntries);
          set({ ids, hydrated: true, lastError: null });
        } catch (err) {
          set({
            hydrated: true,
            lastError: err instanceof Error ? err : new Error(String(err)),
          });
        }
      },

      record: async (id: string) => {
        if (!id) return;
        const current = get().ids;
        const filtered = current.filter((x) => x !== id);
        const next = [id, ...filtered].slice(0, maxEntries);
        set({ ids: next });
        await persist(next);
      },

      remove: async (id: string) => {
        const next = get().ids.filter((x) => x !== id);
        set({ ids: next });
        await persist(next);
      },

      clear: async () => {
        set({ ids: [] });
        await persist([]);
      },

      previous: () => get().ids[1],
      mostRecent: () => get().ids[0],
    };
  });
}
