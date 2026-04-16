/**
 * Zustand store for group preferences — manages user customizations for the
 * smart groups sidebar (sort order, visibility, pinning, renaming).
 *
 * Follows the same pattern as favorites-store.ts.
 */

import { create } from 'zustand';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface GroupPreference {
  id: string;
  contentType: string;
  groupKey: string;
  sortOrder: number;
  isHidden: boolean;
  isPinned: boolean;
  customName: string | null;
  createdAt: number;
}

interface GroupPreferencesStore {
  /** Map of groupKey → preference */
  preferences: Map<string, GroupPreference>;
  /** Currently loaded content type */
  loadedType: string | null;
  /** Whether initial load is complete */
  isLoaded: boolean;

  /** Load preferences for a content type */
  load: (contentType: string) => Promise<void>;
  /** Get preference for a group key */
  getPref: (groupKey: string) => GroupPreference | undefined;
  /** Set/update a preference */
  setPref: (input: {
    contentType: string;
    groupKey: string;
    sortOrder?: number;
    isHidden?: boolean;
    isPinned?: boolean;
    customName?: string | null;
  }) => Promise<void>;
  /** Toggle pinned state */
  togglePinned: (contentType: string, groupKey: string) => Promise<void>;
  /** Toggle hidden state */
  toggleHidden: (contentType: string, groupKey: string) => Promise<void>;
  /** Rename a group */
  rename: (contentType: string, groupKey: string, customName: string | null) => Promise<void>;
  /** Reorder groups */
  reorder: (contentType: string, orderedKeys: string[]) => Promise<void>;
  /** Remove a preference */
  remove: (contentType: string, groupKey: string) => Promise<void>;
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

export const useGroupPreferencesStore = create<GroupPreferencesStore>((set, get) => ({
  preferences: new Map(),
  loadedType: null,
  isLoaded: false,

  load: async (contentType: string) => {
    if (!window.api) return;
    // Skip if already loaded for this type
    if (get().loadedType === contentType && get().isLoaded) return;

    const prefs: GroupPreference[] = await window.api.groupPrefs.get(contentType);
    const map = new Map<string, GroupPreference>();
    for (const p of prefs) {
      map.set(p.groupKey, p);
    }
    set({ preferences: map, loadedType: contentType, isLoaded: true });
  },

  getPref: (groupKey: string) => get().preferences.get(groupKey),

  setPref: async (input) => {
    if (!window.api) return;
    const result: GroupPreference = await window.api.groupPrefs.set(input);
    const next = new Map(get().preferences);
    next.set(result.groupKey, result);
    set({ preferences: next });
  },

  togglePinned: async (contentType: string, groupKey: string) => {
    const existing = get().preferences.get(groupKey);
    const isPinned = !(existing?.isPinned ?? false);
    // Optimistic update
    const next = new Map(get().preferences);
    if (existing) {
      next.set(groupKey, { ...existing, isPinned });
    } else {
      next.set(groupKey, {
        id: '',
        contentType,
        groupKey,
        sortOrder: 0,
        isHidden: false,
        isPinned,
        customName: null,
        createdAt: Date.now(),
      });
    }
    set({ preferences: next });
    if (window.api) {
      await window.api.groupPrefs.set({ contentType, groupKey, isPinned });
    }
  },

  toggleHidden: async (contentType: string, groupKey: string) => {
    const existing = get().preferences.get(groupKey);
    const isHidden = !(existing?.isHidden ?? false);
    const next = new Map(get().preferences);
    if (existing) {
      next.set(groupKey, { ...existing, isHidden });
    } else {
      next.set(groupKey, {
        id: '',
        contentType,
        groupKey,
        sortOrder: 0,
        isHidden,
        isPinned: false,
        customName: null,
        createdAt: Date.now(),
      });
    }
    set({ preferences: next });
    if (window.api) {
      await window.api.groupPrefs.set({ contentType, groupKey, isHidden });
    }
  },

  rename: async (contentType: string, groupKey: string, customName: string | null) => {
    const existing = get().preferences.get(groupKey);
    const next = new Map(get().preferences);
    if (existing) {
      next.set(groupKey, { ...existing, customName });
    } else {
      next.set(groupKey, {
        id: '',
        contentType,
        groupKey,
        sortOrder: 0,
        isHidden: false,
        isPinned: false,
        customName,
        createdAt: Date.now(),
      });
    }
    set({ preferences: next });
    if (window.api) {
      await window.api.groupPrefs.set({ contentType, groupKey, customName });
    }
  },

  reorder: async (contentType: string, orderedKeys: string[]) => {
    // Optimistic: update sort orders in local map
    const next = new Map(get().preferences);
    for (let i = 0; i < orderedKeys.length; i++) {
      const key = orderedKeys[i];
      const existing = next.get(key);
      if (existing) {
        next.set(key, { ...existing, sortOrder: i });
      } else {
        next.set(key, {
          id: '',
          contentType,
          groupKey: key,
          sortOrder: i,
          isHidden: false,
          isPinned: false,
          customName: null,
          createdAt: Date.now(),
        });
      }
    }
    set({ preferences: next });
    if (window.api) {
      await window.api.groupPrefs.reorder(contentType, orderedKeys);
    }
  },

  remove: async (contentType: string, groupKey: string) => {
    const next = new Map(get().preferences);
    next.delete(groupKey);
    set({ preferences: next });
    if (window.api) {
      await window.api.groupPrefs.remove(contentType, groupKey);
    }
  },
}));
