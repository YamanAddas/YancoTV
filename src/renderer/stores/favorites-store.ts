import { createFavoritesStore, type FavoritesAdapter } from '@yancotv/core';

const ipcAdapter: FavoritesAdapter = {
  getIds: async () => {
    if (!window.api) return [];
    return window.api.favorites.getIds();
  },
  add: async (contentId: string) => {
    if (!window.api) return;
    await window.api.favorites.add(contentId);
  },
  remove: async (contentId: string) => {
    if (!window.api) return;
    await window.api.favorites.remove(contentId);
  },
};

export const useFavoritesStore = createFavoritesStore(ipcAdapter);
