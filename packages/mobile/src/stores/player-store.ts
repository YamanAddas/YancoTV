import { create } from 'zustand';

// Playback selection state. Holds only the current track + UI flags —
// the actual <Video> surface lives in PersistentPlayerHost so it doesn't
// unmount across mini/fullscreen transitions (M4R rule 5).
// Mirrors the desktop player-store shape (rule 13).

export interface PlayerTrack {
  contentId: string;
  url: string;
  title: string;
  logoUrl?: string;
}

interface PlayerState {
  track: PlayerTrack | null;
  isFullscreen: boolean;
  isPaused: boolean;
  play: (track: PlayerTrack) => void;
  stop: () => void;
  enterFullscreen: () => void;
  exitFullscreen: () => void;
  togglePause: () => void;
}

export const usePlayerStore = create<PlayerState>((set) => ({
  track: null,
  isFullscreen: false,
  isPaused: false,
  play: (track) => set({ track, isFullscreen: false, isPaused: false }),
  stop: () => set({ track: null, isFullscreen: false, isPaused: false }),
  enterFullscreen: () => set({ isFullscreen: true }),
  exitFullscreen: () => set({ isFullscreen: false }),
  togglePause: () => set((s) => ({ isPaused: !s.isPaused })),
}));
