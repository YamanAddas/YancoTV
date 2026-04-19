import { create } from 'zustand';
import type { ContentType } from '@yancotv/core';

// UI selection state for the HomeShell. Stores what's selected, never bulk
// content — ContentPanel queries SQLite directly off these selectors.
// Per M4R rule 4: Zustand holds UI state only, not lists.

export type RailCategory =
  | { kind: 'type'; type: ContentType }
  | { kind: 'favorites' }
  | { kind: 'group'; type: ContentType; groupName: string };

interface ShellState {
  category: RailCategory;
  activeContentId: string | null;
  setCategory: (c: RailCategory) => void;
  setActiveContent: (id: string | null) => void;
}

const DEFAULT_CATEGORY: RailCategory = { kind: 'type', type: 'live' };

export const useShellStore = create<ShellState>((set) => ({
  category: DEFAULT_CATEGORY,
  activeContentId: null,
  setCategory: (category) =>
    set({ category, activeContentId: null }),
  setActiveContent: (activeContentId) => set({ activeContentId }),
}));

export function categoryKey(c: RailCategory): string {
  if (c.kind === 'type') return `type:${c.type}`;
  if (c.kind === 'favorites') return 'favorites';
  return `group:${c.type}:${c.groupName}`;
}
