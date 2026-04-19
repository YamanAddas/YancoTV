import { create } from 'zustand';
import { asyncStorageKV } from '../storage/async-storage';
import { getJson, setJson } from '../storage/kv-store';

/**
 * Mobile search-history store — ring buffer of ≤20 recent queries, most-recent
 * first, persisted to AsyncStorage. Mirrors the desktop utility at
 * src/renderer/utils/search-history.ts (which backs the same "Recent searches"
 * chip strip on desktop).
 *
 * Kept as a Zustand store rather than free functions so the SearchScreen chips
 * update reactively when the user picks, removes, or records a query — no
 * manual re-read required.
 */

const KEY = 'app:search-history';
const MAX_ENTRIES = 20;

async function readFromStorage(): Promise<string[]> {
  const raw = await getJson<unknown>(asyncStorageKV, KEY);
  if (!Array.isArray(raw)) return [];
  return raw.filter((s): s is string => typeof s === 'string');
}

export interface SearchHistoryStoreState {
  entries: string[];
  isLoaded: boolean;

  /** Populate from AsyncStorage. Safe to call repeatedly. */
  load: () => Promise<void>;

  /** Prepend a query (deduped case-insensitively). No-op for queries <2 chars
   *  — matches desktop, avoids polluting the list with partial debounced input. */
  record: (query: string) => Promise<void>;

  /** Remove a single query (case-insensitive match). */
  remove: (query: string) => Promise<void>;

  /** Wipe the list. */
  clear: () => Promise<void>;
}

export const useSearchHistoryStore = create<SearchHistoryStoreState>((set, get) => ({
  entries: [],
  isLoaded: false,

  load: async () => {
    const entries = await readFromStorage();
    set({ entries, isLoaded: true });
  },

  record: async (query: string) => {
    const trimmed = query.trim();
    if (trimmed.length < 2) return;
    const normalized = trimmed.toLowerCase();
    const current = get().entries;
    const filtered = current.filter((s) => s.toLowerCase() !== normalized);
    filtered.unshift(trimmed);
    const next = filtered.slice(0, MAX_ENTRIES);
    await setJson(asyncStorageKV, KEY, next);
    set({ entries: next });
  },

  remove: async (query: string) => {
    const normalized = query.trim().toLowerCase();
    const next = get().entries.filter((s) => s.toLowerCase() !== normalized);
    await setJson(asyncStorageKV, KEY, next);
    set({ entries: next });
  },

  clear: async () => {
    await setJson(asyncStorageKV, KEY, []);
    set({ entries: [] });
  },
}));
