import { create } from 'zustand';
import * as db from '../db/group-preferences-store';
import type {
  GroupContentType,
  GroupPreferenceRow,
} from '../db/group-preferences-store';

/**
 * Mobile group-preferences store — mirrors desktop's
 * `src/renderer/stores/group-preferences-store.ts` shape but talks to
 * op-sqlite through `db/group-preferences-store.ts` instead of IPC.
 *
 * Optimistic updates land in the in-memory `preferences` map first so the
 * sidebar responds instantly; the DB write happens in the background.
 */

export type { GroupContentType, GroupPreferenceRow };

interface Store {
  preferences: Map<string, GroupPreferenceRow>;
  loadedType: GroupContentType | null;
  isLoaded: boolean;

  load: (contentType: GroupContentType) => Promise<void>;
  togglePinned: (
    contentType: GroupContentType,
    groupKey: string,
  ) => Promise<void>;
  toggleHidden: (
    contentType: GroupContentType,
    groupKey: string,
  ) => Promise<void>;
  rename: (
    contentType: GroupContentType,
    groupKey: string,
    customName: string | null,
  ) => Promise<void>;
  reorder: (
    contentType: GroupContentType,
    orderedKeys: string[],
  ) => Promise<void>;
}

function placeholder(
  contentType: GroupContentType,
  groupKey: string,
  patch: Partial<GroupPreferenceRow>,
): GroupPreferenceRow {
  return {
    id: '',
    contentType,
    groupKey,
    sortOrder: 0,
    isHidden: false,
    isPinned: false,
    customName: null,
    createdAt: Date.now(),
    ...patch,
  };
}

export const useGroupPreferencesStore = create<Store>((set, get) => ({
  preferences: new Map(),
  loadedType: null,
  isLoaded: false,

  load: async (contentType) => {
    if (get().loadedType === contentType && get().isLoaded) return;
    const rows = await db.listByType(contentType);
    const map = new Map<string, GroupPreferenceRow>();
    for (const r of rows) map.set(r.groupKey, r);
    set({ preferences: map, loadedType: contentType, isLoaded: true });
  },

  togglePinned: async (contentType, groupKey) => {
    const existing = get().preferences.get(groupKey);
    const isPinned = !(existing?.isPinned ?? false);
    const next = new Map(get().preferences);
    next.set(groupKey, {
      ...(existing ?? placeholder(contentType, groupKey, {})),
      isPinned,
    });
    set({ preferences: next });
    const saved = await db.upsert({ contentType, groupKey, isPinned });
    const after = new Map(get().preferences);
    after.set(groupKey, saved);
    set({ preferences: after });
  },

  toggleHidden: async (contentType, groupKey) => {
    const existing = get().preferences.get(groupKey);
    const isHidden = !(existing?.isHidden ?? false);
    const next = new Map(get().preferences);
    next.set(groupKey, {
      ...(existing ?? placeholder(contentType, groupKey, {})),
      isHidden,
    });
    set({ preferences: next });
    const saved = await db.upsert({ contentType, groupKey, isHidden });
    const after = new Map(get().preferences);
    after.set(groupKey, saved);
    set({ preferences: after });
  },

  rename: async (contentType, groupKey, customName) => {
    const existing = get().preferences.get(groupKey);
    const next = new Map(get().preferences);
    next.set(groupKey, {
      ...(existing ?? placeholder(contentType, groupKey, {})),
      customName,
    });
    set({ preferences: next });
    const saved = await db.upsert({ contentType, groupKey, customName });
    const after = new Map(get().preferences);
    after.set(groupKey, saved);
    set({ preferences: after });
  },

  reorder: async (contentType, orderedKeys) => {
    const next = new Map(get().preferences);
    for (let i = 0; i < orderedKeys.length; i++) {
      const key = orderedKeys[i];
      const existing = next.get(key);
      next.set(key, {
        ...(existing ?? placeholder(contentType, key, {})),
        sortOrder: i,
      });
    }
    set({ preferences: next });
    await db.reorder(contentType, orderedKeys);
  },
}));
