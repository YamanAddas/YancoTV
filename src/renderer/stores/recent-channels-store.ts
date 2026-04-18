import {
  createRecentChannelsStore,
  type RecentChannelsAdapter,
} from '@yancotv/core';

// Persistent ring buffer of recently played LIVE channel IDs. Separate from
// watch history so we can (a) show a quick-access strip on the Live TV page,
// (b) implement "last channel" recall, and (c) auto-play on launch.

const STORAGE_KEY = 'yancotv.recent-channels';

const localStorageAdapter: RecentChannelsAdapter = {
  readInitial: () => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) return [];
      return parsed.filter((s): s is string => typeof s === 'string');
    } catch {
      return [];
    }
  },
  write: (entries: string[]) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
    } catch {
      // Quota / privacy mode — no-op.
    }
  },
};

export const useRecentChannelsStore = createRecentChannelsStore(localStorageAdapter);
