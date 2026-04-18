/**
 * Favorites store factory — platform-agnostic.
 *
 * The store holds a Set of favorited content IDs and exposes load/toggle/has.
 * Actual persistence (IPC → SQLite on desktop, op-sqlite on mobile) is
 * injected via a `FavoritesAdapter`.
 *
 * `toggle()` awaits the adapter *before* mutating local state, so a failed
 * write never leaves the UI out of sync with persistence. The last error
 * is exposed via `lastError` if callers want to react.
 */

import { create, type StoreApi, type UseBoundStore } from 'zustand';

export interface FavoritesAdapter {
  /** List all favorited content IDs. */
  getIds(): Promise<string[]>;
  /** Persist a new favorite. */
  add(contentId: string): Promise<void>;
  /** Remove an existing favorite. */
  remove(contentId: string): Promise<void>;
}

export interface FavoritesStoreState {
  favoriteIds: Set<string>;
  isLoaded: boolean;
  /** Last error from load/toggle; cleared on the next successful call. */
  lastError: Error | null;
  load: () => Promise<void>;
  toggle: (contentId: string) => Promise<void>;
  isFavorite: (contentId: string) => boolean;
}

export type FavoritesStore = UseBoundStore<StoreApi<FavoritesStoreState>>;

export function createFavoritesStore(adapter: FavoritesAdapter): FavoritesStore {
  return create<FavoritesStoreState>((set, get) => ({
    favoriteIds: new Set(),
    isLoaded: false,
    lastError: null,

    load: async () => {
      try {
        const ids = await adapter.getIds();
        set({ favoriteIds: new Set(ids), isLoaded: true, lastError: null });
      } catch (err) {
        set({
          isLoaded: true,
          lastError: err instanceof Error ? err : new Error(String(err)),
        });
      }
    },

    toggle: async (contentId: string) => {
      const { favoriteIds } = get();
      const willAdd = !favoriteIds.has(contentId);
      try {
        if (willAdd) {
          await adapter.add(contentId);
        } else {
          await adapter.remove(contentId);
        }
      } catch (err) {
        // Persistence failed — don't flip local state, surface the error.
        set({ lastError: err instanceof Error ? err : new Error(String(err)) });
        return;
      }
      const next = new Set(favoriteIds);
      if (willAdd) next.add(contentId);
      else next.delete(contentId);
      set({ favoriteIds: next, lastError: null });
    },

    isFavorite: (contentId: string) => get().favoriteIds.has(contentId),
  }));
}
