import { create } from 'zustand';
import type { HistoryEntry } from '@yancotv/core';
import * as historyDb from '../db/history-store';

/**
 * Mobile watch-history Zustand store.
 *
 * Wraps the stateless op-sqlite history module to provide a reactive
 * "Recently Watched" rail and a single place the PlayerScreen can call
 * recordWatch/updatePosition without pulling the SQL module into every
 * component. Matches the shape desktop will expose once its history
 * bindings are extracted to @yancotv/core.
 */

const RECENT_LIMIT = 20;

export interface HistoryStoreState {
  recent: HistoryEntry[];
  isLoaded: boolean;
  lastError: Error | null;

  /** Populate `recent` from SQLite. Safe to call repeatedly. */
  load: () => Promise<void>;

  /** Insert a new watch-session row and refresh `recent`. */
  recordWatch: (contentId: string, episodeId?: string) => Promise<string>;

  /** Update the live position on a session. Does not refresh `recent` to
   *  avoid thrashing during playback — callers that care should call load()
   *  after the session ends. */
  updatePosition: (
    historyId: string,
    positionSeconds: number,
    durationSeconds?: number,
  ) => Promise<void>;

  /** Delete one entry and refresh `recent`. */
  removeEntry: (id: string) => Promise<void>;

  /** Drop every row and refresh `recent`. */
  clearAll: () => Promise<void>;

  /** One-shot read for resume-from-position logic (player boot). */
  getLastPosition: (
    contentId: string,
    episodeId?: string,
  ) => Promise<{ positionSeconds: number; durationSeconds?: number } | null>;
}

export const useHistoryStore = create<HistoryStoreState>((set) => ({
  recent: [],
  isLoaded: false,
  lastError: null,

  load: async () => {
    try {
      const recent = await historyDb.getRecentlyWatched(RECENT_LIMIT);
      set({ recent, isLoaded: true, lastError: null });
    } catch (err) {
      set({
        isLoaded: true,
        lastError: err instanceof Error ? err : new Error(String(err)),
      });
    }
  },

  recordWatch: async (contentId, episodeId) => {
    const id = await historyDb.recordWatch(contentId, episodeId);
    const recent = await historyDb.getRecentlyWatched(RECENT_LIMIT);
    set({ recent });
    return id;
  },

  updatePosition: async (historyId, positionSeconds, durationSeconds) => {
    await historyDb.updatePosition(historyId, positionSeconds, durationSeconds);
  },

  removeEntry: async (id) => {
    await historyDb.removeHistoryEntry(id);
    const recent = await historyDb.getRecentlyWatched(RECENT_LIMIT);
    set({ recent });
  },

  clearAll: async () => {
    await historyDb.clearHistory();
    set({ recent: [] });
  },

  getLastPosition: (contentId, episodeId) =>
    historyDb.getLastPosition(contentId, episodeId),
}));
