/**
 * Favorites store factory — platform-agnostic.
 *
 * The store holds a Set of favorited content IDs and exposes load/toggle/has.
 * Actual persistence (IPC → SQLite on desktop, op-sqlite on mobile) is
 * injected via a `FavoritesAdapter`.
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
  load: () => Promise<void>;
  toggle: (contentId: string) => Promise<void>;
  isFavorite: (contentId: string) => boolean;
}

export type FavoritesStore = UseBoundStore<StoreApi<FavoritesStoreState>>;

/**
 * Build a Zustand store backed by the supplied adapter. Consumers keep the
 * returned hook as a module singleton (`export const useFavoritesStore = createFavoritesStore(...)`).
 */
export function createFavoritesStore(adapter: FavoritesAdapter): FavoritesStore {
  return create<FavoritesStoreState>((set, get) => ({
    favoriteIds: new Set(),
    isLoaded: false,

    load: async () => {
      const ids = await adapter.getIds();
      set({ favoriteIds: new Set(ids), isLoaded: true });
    },

    toggle: async (contentId: string) => {
      const { favoriteIds } = get();
      const next = new Set(favoriteIds);
      if (favoriteIds.has(contentId)) {
        await adapter.remove(contentId);
        next.delete(contentId);
      } else {
        await adapter.add(contentId);
        next.add(contentId);
      }
      set({ favoriteIds: next });
    },

    isFavorite: (contentId: string) => get().favoriteIds.has(contentId),
  }));
}
