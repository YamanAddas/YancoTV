import { create } from 'zustand';

export interface PlayerStoreState {
  status: 'idle' | 'playing' | 'paused' | 'buffering' | 'stopped' | 'error';
  position: number;
  duration: number;
  volume: number;
  muted: boolean;
  currentUrl?: string;
  currentTitle?: string;
  currentContentId?: string;
  currentEpisodeId?: string;
  currentHistoryId?: string;
  error?: string;
}

interface PlayerStoreActions {
  play: (url: string, title?: string, contentId?: string, episodeId?: string) => Promise<void>;
  pause: () => Promise<void>;
  resume: () => Promise<void>;
  stop: () => Promise<void>;
  seek: (seconds: number) => Promise<void>;
  setVolume: (level: number) => Promise<void>;
  setError: (error: string | undefined) => void;
}

export type PlayerStore = PlayerStoreState & PlayerStoreActions;

export const usePlayerStore = create<PlayerStore>((set) => ({
  // State
  status: 'idle',
  position: 0,
  duration: 0,
  volume: 100,
  muted: false,
  currentUrl: undefined,
  currentTitle: undefined,
  currentContentId: undefined,
  currentEpisodeId: undefined,
  currentHistoryId: undefined,
  error: undefined,

  // Actions
  play: async (url: string, title?: string, contentId?: string, episodeId?: string) => {
    if (!window.api) return;

    set({
      status: 'buffering',
      currentUrl: url,
      currentTitle: title,
      currentContentId: contentId,
      currentEpisodeId: episodeId,
      currentHistoryId: undefined,
      error: undefined,
    });

    let startPosition: number | undefined;

    if (contentId) {
      // Check for a resume position (skip for live content — duration will be 0)
      const pos = await window.api.history.getPosition(contentId, episodeId);
      if (pos && pos.positionSeconds > 30) {
        // Only resume if not near the end
        const nearEnd =
          pos.durationSeconds && pos.positionSeconds > pos.durationSeconds - 60;
        if (!nearEnd) {
          startPosition = pos.positionSeconds;
        }
      }

      // Record a new history entry
      const histResult = await window.api.history.record(contentId, episodeId);
      if (histResult?.ok && histResult.historyId) {
        set({ currentHistoryId: histResult.historyId });
      }
    }

    const result = await window.api.player.play(url, title, startPosition);
    if (!result.ok) {
      set({ status: 'error', error: result.error });
    }
  },

  pause: async () => {
    if (!window.api) return;
    await window.api.player.pause();
  },

  resume: async () => {
    if (!window.api) return;
    await window.api.player.resume();
  },

  stop: async () => {
    if (!window.api) return;
    await window.api.player.stop();
    set({
      status: 'idle',
      currentUrl: undefined,
      currentTitle: undefined,
      currentContentId: undefined,
      currentEpisodeId: undefined,
      currentHistoryId: undefined,
      position: 0,
      duration: 0,
    });
  },

  seek: async (seconds: number) => {
    if (!window.api) return;
    await window.api.player.seek(seconds);
  },

  setVolume: async (level: number) => {
    if (!window.api) return;
    set({ volume: level });
    await window.api.player.setVolume(level);
  },

  setError: (error: string | undefined) => set({ error }),
}));

/**
 * Subscribe to player events from the main process.
 * Call once at app startup. Returns cleanup function.
 */
export function initPlayerEventListeners(): () => void {
  if (!window.api) return () => {};

  const cleanups: (() => void)[] = [];
  let lastHistoryUpdateAt = 0;

  cleanups.push(
    window.api.player.onStateChange((state: unknown) => {
      const s = state as PlayerStoreState;
      usePlayerStore.setState({
        status: s.status,
        position: s.position,
        duration: s.duration,
        volume: s.volume,
        muted: s.muted,
        currentUrl: s.currentUrl,
      });
    }),
  );

  cleanups.push(
    window.api.player.onTimeUpdate((position: number) => {
      usePlayerStore.setState({ position });

      // Throttle history position updates to every 30 seconds
      const now = Date.now();
      if (now - lastHistoryUpdateAt < 30_000) return;
      lastHistoryUpdateAt = now;

      const { currentHistoryId, duration } = usePlayerStore.getState();
      if (!currentHistoryId) return;

      window.api.history.updatePosition(
        currentHistoryId,
        Math.floor(position),
        duration > 0 ? Math.floor(duration) : undefined,
      );
    }),
  );

  cleanups.push(
    window.api.player.onError((message: string) => {
      usePlayerStore.setState({ status: 'error', error: message });
    }),
  );

  return () => {
    for (const cleanup of cleanups) cleanup();
  };
}
