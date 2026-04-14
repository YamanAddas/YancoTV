import { create } from 'zustand';

interface FavoritesStore {
  favoriteIds: Set<string>;
  isLoaded: boolean;
  load: () => Promise<void>;
  toggle: (contentId: string) => Promise<void>;
  isFavorite: (contentId: string) => boolean;
}

export const useFavoritesStore = create<FavoritesStore>((set, get) => ({
  favoriteIds: new Set(),
  isLoaded: false,

  load: async () => {
    if (!window.api) return;
    const ids: string[] = await window.api.favorites.getIds();
    set({ favoriteIds: new Set(ids), isLoaded: true });
  },

  toggle: async (contentId: string) => {
    if (!window.api) return;
    const { favoriteIds } = get();
    if (favoriteIds.has(contentId)) {
      await window.api.favorites.remove(contentId);
      const next = new Set(favoriteIds);
      next.delete(contentId);
      set({ favoriteIds: next });
    } else {
      await window.api.favorites.add(contentId);
      const next = new Set(favoriteIds);
      next.add(contentId);
      set({ favoriteIds: next });
    }
  },

  isFavorite: (contentId: string) => get().favoriteIds.has(contentId),
}));
