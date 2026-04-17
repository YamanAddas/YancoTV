import { create } from 'zustand';

// Persistent ring buffer of recently played LIVE channel IDs. Separate from
// watch history so that we can (a) show a quick-access strip on the Live TV
// page, (b) implement "last channel" recall, and (c) auto-play on launch —
// without having to filter the general watch-history store each time.

const STORAGE_KEY = 'yancotv.recent-channels';
const MAX_ENTRIES = 10;

function read(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((s): s is string => typeof s === 'string').slice(0, MAX_ENTRIES);
  } catch {
    return [];
  }
}

function write(entries: string[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
  } catch {
    // Quota / privacy mode — no-op.
  }
}

interface RecentChannelsStore {
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

export const useRecentChannelsStore = create<RecentChannelsStore>((set, get) => ({
  ids: read(),
  record: (id: string) => {
    if (!id) return;
    const current = get().ids;
    const filtered = current.filter((x) => x !== id);
    const next = [id, ...filtered].slice(0, MAX_ENTRIES);
    write(next);
    set({ ids: next });
  },
  remove: (id: string) => {
    const next = get().ids.filter((x) => x !== id);
    write(next);
    set({ ids: next });
  },
  clear: () => {
    write([]);
    set({ ids: [] });
  },
  previous: () => get().ids[1],
  mostRecent: () => get().ids[0],
}));
