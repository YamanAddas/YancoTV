import { create } from 'zustand';
import { getVideoElement } from '../components/player/video-ref';

export type PlayerMode = 'idle' | 'theater';
export type PlayerBackend = 'mpv' | 'html5' | 'none';

export interface SubtitleTrack {
  id: number;
  title: string;
  language?: string;
  selected: boolean;
}

export interface AudioTrack {
  id: number;
  title: string;
  language?: string;
  selected: boolean;
}

export interface MediaInfo {
  videoCodec?: string;
  audioCodec?: string;
  width?: number;
  height?: number;
  fps?: number;
  bitrate?: number;
  hwdec?: string;
}

export interface PlayerStoreState {
  status: 'idle' | 'playing' | 'paused' | 'buffering' | 'stopped' | 'error';
  mode: PlayerMode;
  /** Which playback engine is active: mpv (full codec support) or html5 (fallback) */
  backend: PlayerBackend;
  position: number;
  duration: number;
  volume: number;
  muted: boolean;
  speed: number;
  aspectRatio: string;
  fullscreen: boolean;
  subtitleTracks: SubtitleTrack[];
  audioTracks: AudioTrack[];
  mediaInfo: MediaInfo;
  currentUrl?: string;
  currentTitle?: string;
  currentContentId?: string;
  currentEpisodeId?: string;
  currentHistoryId?: string;
  error?: string;
  showSettings: boolean;
  controlsVisible: boolean;
  /** Used by VideoPlayer (html5 backend) to know the start position */
  _startPosition?: number;
}

interface PlayerStoreActions {
  play: (url: string, title?: string, contentId?: string, episodeId?: string) => Promise<void>;
  pause: () => void;
  resume: () => void;
  stop: () => void;
  seek: (seconds: number) => void;
  setVolume: (level: number) => void;
  toggleMute: () => void;
  setSpeed: (speed: number) => void;
  cycleSpeed: () => void;
  setAspectRatio: (ratio: string) => void;
  cycleAspectRatio: () => void;
  toggleFullscreen: () => void;
  toggleSubtitles: () => void;
  loadSubtitleFile: () => Promise<void>;
  setError: (error: string | undefined) => void;
  setMode: (mode: PlayerMode) => void;
  setShowSettings: (show: boolean) => void;
  toggleSettings: () => void;
  setControlsVisible: (visible: boolean) => void;
}

export type PlayerStore = PlayerStoreState & PlayerStoreActions;

const SPEED_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2];
const ASPECT_RATIOS = ['auto', '16:9', '4:3', '21:9', 'fill'];

export const usePlayerStore = create<PlayerStore>((set, get) => ({
  // State
  status: 'idle',
  mode: 'idle',
  backend: 'none',
  position: 0,
  duration: 0,
  volume: 100,
  muted: false,
  speed: 1,
  aspectRatio: 'auto',
  fullscreen: false,
  subtitleTracks: [],
  audioTracks: [],
  mediaInfo: {},
  currentUrl: undefined,
  currentTitle: undefined,
  currentContentId: undefined,
  currentEpisodeId: undefined,
  currentHistoryId: undefined,
  error: undefined,
  showSettings: false,
  controlsVisible: true,
  _startPosition: undefined,

  // --- Actions ---

  play: async (url: string, title?: string, contentId?: string, episodeId?: string) => {
    if (!window.api) return;
    const { backend } = get();

    let startPosition: number | undefined;

    if (contentId) {
      const pos = await window.api.history.getPosition(contentId, episodeId);
      if (pos && pos.positionSeconds > 30) {
        const nearEnd =
          pos.durationSeconds && pos.positionSeconds > pos.durationSeconds - 60;
        if (!nearEnd) {
          startPosition = pos.positionSeconds;
        }
      }

      const histResult = await window.api.history.record(contentId, episodeId);
      if (histResult?.ok && histResult.historyId) {
        set({ currentHistoryId: histResult.historyId });
      }
    }

    if (backend === 'mpv') {
      // mpv backend: call IPC to start mpv. State updates arrive via push events.
      set({
        status: 'buffering',
        mode: 'theater',
        currentUrl: url,
        currentTitle: title,
        currentContentId: contentId,
        currentEpisodeId: episodeId,
        error: undefined,
        showSettings: false,
        mediaInfo: {},
        position: 0,
        duration: 0,
      });
      try {
        const result = await window.api.player.play(url, title, startPosition);
        if (result && !result.ok) {
          set({ status: 'error', error: result.error || 'Failed to start playback' });
        }
      } catch (err) {
        set({ status: 'error', error: String(err) });
      }
    } else {
      // html5 backend: set currentUrl so VideoPlayer component picks it up
      set({
        status: 'buffering',
        mode: 'theater',
        currentUrl: url,
        currentTitle: title,
        currentContentId: contentId,
        currentEpisodeId: episodeId,
        error: undefined,
        showSettings: false,
        mediaInfo: {},
        position: 0,
        duration: 0,
        _startPosition: startPosition,
      });
    }
  },

  pause: () => {
    if (get().backend === 'mpv') {
      window.api?.player.pause().catch(() => {});
    } else {
      getVideoElement()?.pause();
    }
  },

  resume: () => {
    if (get().backend === 'mpv') {
      window.api?.player.resume().catch(() => {});
    } else {
      getVideoElement()?.play();
    }
  },

  stop: () => {
    const { backend, fullscreen } = get();

    if (backend === 'mpv') {
      window.api?.player.stop().catch(() => {});
    } else {
      const video = getVideoElement();
      if (video) video.pause();
    }

    set({
      status: 'idle',
      mode: 'idle',
      currentUrl: undefined,
      currentTitle: undefined,
      currentContentId: undefined,
      currentEpisodeId: undefined,
      currentHistoryId: undefined,
      position: 0,
      duration: 0,
      subtitleTracks: [],
      audioTracks: [],
      mediaInfo: {},
      showSettings: false,
      fullscreen: false,
      _startPosition: undefined,
    });

    if (fullscreen && window.api) {
      window.api.player.setFullscreen(false).catch(() => {});
    }
  },

  seek: (seconds: number) => {
    if (get().backend === 'mpv') {
      window.api?.player.seek(seconds).catch(() => {});
    } else {
      const video = getVideoElement();
      if (video) video.currentTime = seconds;
    }
  },

  setVolume: (level: number) => {
    const clamped = Math.max(0, Math.min(100, level));
    if (get().backend === 'mpv') {
      window.api?.player.setVolume(clamped).catch(() => {});
    } else {
      const video = getVideoElement();
      if (video) video.volume = clamped / 100;
    }
    set({ volume: clamped });
  },

  toggleMute: () => {
    if (get().backend === 'mpv') {
      window.api?.player.toggleMute().catch(() => {});
    } else {
      const video = getVideoElement();
      if (video) {
        video.muted = !video.muted;
        set({ muted: video.muted });
      }
    }
  },

  setSpeed: (speed: number) => {
    const clamped = Math.max(0.25, Math.min(4, speed));
    if (get().backend === 'mpv') {
      window.api?.player.setSpeed(clamped).catch(() => {});
    } else {
      const video = getVideoElement();
      if (video) video.playbackRate = clamped;
    }
    set({ speed: clamped });
  },

  cycleSpeed: () => {
    const current = get().speed;
    const idx = SPEED_STEPS.indexOf(current);
    const next = SPEED_STEPS[(idx + 1) % SPEED_STEPS.length];
    get().setSpeed(next);
  },

  setAspectRatio: (ratio: string) => {
    if (get().backend === 'mpv') {
      window.api?.player.setAspectRatio(ratio).catch(() => {});
    } else {
      const video = getVideoElement();
      if (video) {
        if (ratio === 'auto') {
          video.style.objectFit = 'contain';
        } else if (ratio === 'fill') {
          video.style.objectFit = 'cover';
        } else {
          video.style.objectFit = 'contain';
        }
      }
    }
    set({ aspectRatio: ratio });
  },

  cycleAspectRatio: () => {
    const current = get().aspectRatio;
    const idx = ASPECT_RATIOS.indexOf(current);
    const next = ASPECT_RATIOS[(idx + 1) % ASPECT_RATIOS.length];
    get().setAspectRatio(next);
  },

  toggleFullscreen: () => {
    if (!window.api) return;
    const current = get().fullscreen;
    window.api.player.setFullscreen(!current).then((result: { ok?: boolean; fullscreen?: boolean }) => {
      if (result?.ok !== undefined) {
        set({ fullscreen: result.fullscreen ?? !current });
      }
    }).catch(() => {});
  },

  toggleSubtitles: () => {
    if (get().backend === 'mpv') {
      window.api?.player.toggleSubtitles().catch(() => {});
    } else {
      const video = getVideoElement();
      if (!video) return;
      for (let i = 0; i < video.textTracks.length; i++) {
        const track = video.textTracks[i];
        track.mode = track.mode === 'showing' ? 'hidden' : 'showing';
      }
    }
  },

  loadSubtitleFile: async () => {
    if (!window.api) return;
    await window.api.player.loadSubtitleFile();
  },

  setError: (error: string | undefined) => set({ error }),
  setMode: (mode: PlayerMode) => set({ mode }),
  setShowSettings: (show: boolean) => set({ showSettings: show }),
  toggleSettings: () => set((s) => ({ showSettings: !s.showSettings })),
  setControlsVisible: (visible: boolean) => set({ controlsVisible: visible }),
}));

// ---------------------------------------------------------------------------
// mpv IPC event subscriptions + history position tracking
// ---------------------------------------------------------------------------

let _playerListenersCleanup: (() => void) | null = null;

export function initPlayerEventListeners(): () => void {
  if (_playerListenersCleanup) {
    _playerListenersCleanup();
    _playerListenersCleanup = null;
  }

  if (!window.api) return () => {};

  const cleanups: Array<() => void> = [];

  // --- Subscribe to mpv push events (active when backend=mpv) ---
  const unsubState = window.api.player.onStateChange((mpvState: {
    status?: string;
    position?: number;
    duration?: number;
    volume?: number;
    muted?: boolean;
    speed?: number;
    aspectRatio?: string;
    fullscreen?: boolean;
    subtitleTracks?: SubtitleTrack[];
    audioTracks?: AudioTrack[];
    mediaInfo?: MediaInfo;
    currentUrl?: string;
  }) => {
    const { backend, mode } = usePlayerStore.getState();
    if (backend !== 'mpv') return;

    const update: Partial<PlayerStoreState> = {};

    if (mpvState.status) {
      update.status = mpvState.status as PlayerStoreState['status'];
      // If mpv reports idle and we're in theater mode, the stream ended or mpv exited
      if (mpvState.status === 'idle' && mode === 'theater') {
        usePlayerStore.getState().stop();
        return;
      }
    }
    if (typeof mpvState.position === 'number') update.position = mpvState.position;
    if (typeof mpvState.duration === 'number') update.duration = mpvState.duration;
    if (typeof mpvState.volume === 'number') update.volume = mpvState.volume;
    if (typeof mpvState.muted === 'boolean') update.muted = mpvState.muted;
    if (typeof mpvState.speed === 'number') update.speed = mpvState.speed;
    if (typeof mpvState.fullscreen === 'boolean') update.fullscreen = mpvState.fullscreen;
    if (mpvState.subtitleTracks) update.subtitleTracks = mpvState.subtitleTracks;
    if (mpvState.audioTracks) update.audioTracks = mpvState.audioTracks;
    if (mpvState.mediaInfo) update.mediaInfo = mpvState.mediaInfo as MediaInfo;

    if (Object.keys(update).length > 0) {
      usePlayerStore.setState(update);
    }
  });
  cleanups.push(unsubState);

  const unsubTime = window.api.player.onTimeUpdate((position: number) => {
    if (usePlayerStore.getState().backend !== 'mpv') return;
    usePlayerStore.setState({ position });
  });
  cleanups.push(unsubTime);

  const unsubError = window.api.player.onError((message: string) => {
    if (usePlayerStore.getState().backend !== 'mpv') return;
    usePlayerStore.setState({ status: 'error', error: message });
  });
  cleanups.push(unsubError);

  // --- History position updates (both backends) ---
  let lastHistoryUpdateAt = 0;

  const interval = setInterval(() => {
    const { currentHistoryId, duration, status, backend, position } = usePlayerStore.getState();
    if (!currentHistoryId || status !== 'playing') return;

    const now = Date.now();
    if (now - lastHistoryUpdateAt < 10_000) return;
    lastHistoryUpdateAt = now;

    // For mpv, position comes from the store (fed by IPC push).
    // For html5, read from the video element for accuracy.
    let pos = position;
    if (backend === 'html5') {
      const video = getVideoElement();
      if (video) pos = video.currentTime;
    }

    window.api.history.updatePosition(
      currentHistoryId,
      Math.floor(pos),
      duration > 0 ? Math.floor(duration) : undefined,
    );
  }, 5_000);
  cleanups.push(() => clearInterval(interval));

  const cleanup = () => {
    for (const fn of cleanups) fn();
  };

  _playerListenersCleanup = cleanup;
  return cleanup;
}
