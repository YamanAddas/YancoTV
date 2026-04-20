import { create } from 'zustand';
import type { ContentType } from '@yancotv/core';

// UI selection state for the HomeShell. Stores what's selected, never bulk
// content — ContentPanel queries SQLite directly off these selectors.
// Per M4R rule 4: Zustand holds UI state only, not lists.

// M4R.D.1 split: `navTarget` is the top-level global navigation (desktop
// Sidebar parity — Home / Live TV / TV Guide / Movies / Series / Favorites /
// Recordings / Downloads / Settings). `category` is the content-filter
// concept the ContentPanel queries against; for content-bearing targets it's
// derived from `navTarget`. Non-content targets (home/guide/recordings/
// downloads/settings) render placeholder panels until their milestones land.
export type NavTarget =
  | 'home'
  | 'live'
  | 'guide'
  | 'movies'
  | 'series'
  | 'favorites'
  | 'recordings'
  | 'downloads'
  | 'settings';

export type RailCategory =
  | { kind: 'type'; type: ContentType }
  | { kind: 'favorites' }
  | { kind: 'group'; type: ContentType; groupName: string };

interface ShellState {
  navTarget: NavTarget;
  category: RailCategory;
  activeContentId: string | null;
  sourcesModalOpen: boolean;
  searchOverlayOpen: boolean;
  filterDrawerOpen: boolean;
  setNavTarget: (t: NavTarget) => void;
  setCategory: (c: RailCategory) => void;
  setActiveContent: (id: string | null) => void;
  openSourcesModal: () => void;
  closeSourcesModal: () => void;
  openSearchOverlay: () => void;
  closeSearchOverlay: () => void;
  openFilterDrawer: () => void;
  closeFilterDrawer: () => void;
}

const DEFAULT_NAV: NavTarget = 'live';

function categoryForNavTarget(t: NavTarget): RailCategory {
  switch (t) {
    case 'live':
      return { kind: 'type', type: 'live' };
    case 'movies':
      return { kind: 'type', type: 'movie' };
    case 'series':
      return { kind: 'type', type: 'series' };
    case 'favorites':
      return { kind: 'favorites' };
    default:
      // Placeholder targets keep the last content category so returning to a
      // content target doesn't blow away the previous selection.
      return { kind: 'type', type: 'live' };
  }
}

export const useShellStore = create<ShellState>((set) => ({
  navTarget: DEFAULT_NAV,
  category: categoryForNavTarget(DEFAULT_NAV),
  activeContentId: null,
  sourcesModalOpen: false,
  searchOverlayOpen: false,
  filterDrawerOpen: false,
  setNavTarget: (navTarget) => {
    if (isContentNavTarget(navTarget)) {
      set({
        navTarget,
        category: categoryForNavTarget(navTarget),
        activeContentId: null,
      });
    } else {
      // Preserve the previous content category; non-content targets render
      // placeholders but we don't want to reset the user's content filter.
      set({ navTarget, activeContentId: null });
    }
  },
  setCategory: (category) => set({ category, activeContentId: null }),
  setActiveContent: (activeContentId) => set({ activeContentId }),
  openSourcesModal: () => set({ sourcesModalOpen: true }),
  closeSourcesModal: () => set({ sourcesModalOpen: false }),
  openSearchOverlay: () => set({ searchOverlayOpen: true }),
  closeSearchOverlay: () => set({ searchOverlayOpen: false }),
  openFilterDrawer: () => set({ filterDrawerOpen: true }),
  closeFilterDrawer: () => set({ filterDrawerOpen: false }),
}));

export function categoryKey(c: RailCategory): string {
  if (c.kind === 'type') return `type:${c.type}`;
  if (c.kind === 'favorites') return 'favorites';
  return `group:${c.type}:${c.groupName}`;
}

export function isContentNavTarget(t: NavTarget): boolean {
  return t === 'live' || t === 'movies' || t === 'series' || t === 'favorites';
}
