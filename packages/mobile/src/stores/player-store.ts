import { create } from 'zustand';
import { launchNativePlayer } from '../player/PlayerLauncher';
import { Sentry } from '../sentry';

// Playback selection state.
//
// Post-TiviMate rewrite: the native PlayerActivity owns everything about
// playback (surface, controls, pause, fullscreen, D-pad). JS only tracks
// the last track the user opened so the MiniPlayer re-entry tile can
// re-launch the same stream. No <Video> mounts in RN.

export interface PlayerTrack {
  contentId: string;
  url: string;
  title: string;
  logoUrl?: string;
}

interface PlayerState {
  track: PlayerTrack | null;
  play: (track: PlayerTrack) => void;
  stop: () => void;
}

export const usePlayerStore = create<PlayerState>((set) => ({
  track: null,
  play: (track) => {
    set({ track });
    void launchNativePlayer({ url: track.url, title: track.title }).catch(
      (e: unknown) => {
        Sentry.captureException(e);
      },
    );
  },
  stop: () => set({ track: null }),
}));
