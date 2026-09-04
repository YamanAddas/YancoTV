import { create } from 'zustand';
import { getVideoElement } from '../components/player/video-ref';
import { useRecentChannelsStore } from './recent-channels-store';
import { useParentalStore } from './parental-store';

export type PlayerMode = 'idle' | 'mini' | 'theater';
export type PlayerBackend = 'mpv' | 'html5' | 'none';
export type SettingsTab = 'subtitles' | 'audio' | 'video' | 'speed' | 'info';

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

export interface ZapTarget {
  /** Content id of the channel the user has scrolled to (not yet tuned). */
  contentId: string;
  title: string;
  logoUrl?: string;
  streamUrl: string;
  /** Position in the channel list (for "3 of 215" hint). */
  index: number;
  total: number;
}

export interface PlayerStoreState {
  status: 'idle' | 'playing' | 'paused' | 'buffering' | 'stopped' | 'error' | 'reconnecting';
  /** Current reconnect attempt (1-based). Set while status === 'reconnecting'. */
  reconnectAttempt?: number;
  /** Max reconnect attempts for the current stream. */
  reconnectMaxAttempts?: number;
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
  subtitleDelay: number;
  audioDelay: number;
  videoZoom: number;
  subtitleTracks: SubtitleTrack[];
  audioTracks: AudioTrack[];
  mediaInfo: MediaInfo;
  currentUrl?: string;
  currentTitle?: string;
  currentContentId?: string;
  currentEpisodeId?: string;
  /** What kind of content is currently playing — used by features (e.g. channel zapping) that only apply to live. */
  currentContentType?: 'live' | 'movie' | 'series';
  currentHistoryId?: string;
  error?: string;
  showSettings: boolean;
  /** Which tab opens when the settings panel toggles on (gear = 'info' by default). */
  settingsTab: SettingsTab;
  /** Compact aspect-ratio popover, separate from the full settings panel. */
  showAspectMenu: boolean;
  controlsVisible: boolean;
  /** Used by VideoPlayer (html5 backend) to know the start position */
  _startPosition?: number;
  /** Ephemeral preview of the channel the user is zapping to (PageUp/Down). Null when no zap is in progress. */
  zapTarget: ZapTarget | null;
}

interface PlayerStoreActions {
  play: (
    url: string,
    title?: string,
    contentId?: string,
    episodeId?: string,
    contentType?: 'live' | 'movie' | 'series',
    /** INTERNAL — see the implementation. Only the PIN prompt's resume sets it. */
    _gateAlreadyPassed?: boolean,
  ) => Promise<void>;
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
  setSubtitleTrack: (id: number) => void;
  setAudioTrack: (id: number) => void;
  setSubtitleDelay: (seconds: number) => void;
  adjustSubtitleDelay: (deltaSeconds: number) => void;
  setAudioDelay: (seconds: number) => void;
  adjustAudioDelay: (deltaSeconds: number) => void;
  setVideoZoom: (factor: number) => void;
  adjustVideoZoom: (delta: number) => void;
  takeScreenshot: () => Promise<string | null>;
  setError: (error: string | undefined) => void;
  setMode: (mode: PlayerMode) => void;
  /** Expand the docked mini-player into full theater mode (keeps playback). */
  expand: () => void;
  /** Drop from theater back to the docked mini-player (keeps playback). */
  minimize: () => void;
  setShowSettings: (show: boolean) => void;
  /** Open the settings panel directly on a specific tab. */
  openSettings: (tab: SettingsTab) => void;
  toggleSettings: () => void;
  toggleAspectMenu: () => void;
  setShowAspectMenu: (show: boolean) => void;
  setControlsVisible: (visible: boolean) => void;
}

export type PlayerStore = PlayerStoreState & PlayerStoreActions;

const SPEED_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2];
const ASPECT_RATIOS = ['auto', '16:9', '4:3', '21:9', '2.35:1', '1:1', 'fill'];

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
  subtitleDelay: 0,
  audioDelay: 0,
  videoZoom: 1,
  subtitleTracks: [],
  audioTracks: [],
  mediaInfo: {},
  currentUrl: undefined,
  currentTitle: undefined,
  currentContentId: undefined,
  currentEpisodeId: undefined,
  currentContentType: undefined,
  currentHistoryId: undefined,
  error: undefined,
  showSettings: false,
  settingsTab: 'info',
  showAspectMenu: false,
  controlsVisible: true,
  _startPosition: undefined,
  zapTarget: null,

  // --- Actions ---

  play: async (
    url: string,
    title?: string,
    contentId?: string,
    episodeId?: string,
    contentType?: 'live' | 'movie' | 'series',
    /**
     * INTERNAL. Set only by the PIN prompt's resume callback, to replay the one
     * call it just released without re-triggering the gate.
     *
     * This was a `_unlockRetry` flag on the store, which was wrong: a flag is
     * store-wide, so any OTHER play starting during the retry window — a zap
     * key, a firing reminder — would have skipped its own gate too. Scoped to
     * the single call, there is no window.
     */
    _gateAlreadyPassed?: boolean,
  ) => {
    if (!window.api) return;
    const { backend } = get();

    // MB-405 — ask before playing anything.
    //
    // This has to live here rather than in the PLAYER_PLAY handler because the
    // html5 backend never calls it: that path sets `currentUrl` and lets the
    // <VideoPlayer> element load the stream in the renderer. A main-process
    // check therefore guards mpv only. `play` is the one funnel both backends
    // share, and every playback entry point in the app goes through it.
    //
    // The main process is still the authority — it refuses a locked
    // PLAYER_PLAY independently. This call is what turns that refusal into a
    // PIN prompt instead of an error toast.
    if (!_gateAlreadyPassed) {
      try {
        const gate = await window.api.parental.requiresPin(contentId, url);
        if (gate?.required && gate.contentId) {
          useParentalStore.getState().requestUnlock({
            contentId: gate.contentId,
            title,
            resume: () => {
              // Re-enter with the same arguments, telling only THIS call to
              // skip the gate it has already satisfied.
              void get().play(url, title, contentId, episodeId, contentType, true);
            },
          });
          return;
        }
      } catch {
        // A failed gate check must not become a way to play locked content.
        // The main process refuses PLAYER_PLAY on its own, so mpv stays safe
        // either way; bailing here keeps the html5 path safe too.
        return;
      }
    }

    // Track recently-played live channels for the "recent strip", last-channel
    // recall shortcut, and auto-play-on-launch. Movies/series use watch history
    // instead, so we only record the live case here.
    if (contentType === 'live' && contentId) {
      useRecentChannelsStore.getState().record(contentId);
    }

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

    // Default mode for a new stream is the docked mini-player — the user
    // expands to full theater when they want it. Preserves the current mode
    // when the user re-tunes from within theater so they don't get yanked
    // back to mini mid-watch.
    const currentMode = get().mode;
    const targetMode: PlayerMode = currentMode === 'theater' ? 'theater' : 'mini';

    if (backend === 'mpv') {
      // mpv backend: call IPC to start mpv. State updates arrive via push events.
      set({
        status: 'buffering',
        mode: targetMode,
        currentUrl: url,
        currentTitle: title,
        currentContentId: contentId,
        currentEpisodeId: episodeId,
        currentContentType: contentType,
        error: undefined,
        showSettings: false,
        mediaInfo: {},
        position: 0,
        duration: 0,
        zapTarget: null,
      });
      try {
        const result = await window.api.player.play(url, title, startPosition, contentId, episodeId);
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
        mode: targetMode,
        currentUrl: url,
        currentTitle: title,
        currentContentId: contentId,
        currentEpisodeId: episodeId,
        currentContentType: contentType,
        error: undefined,
        showSettings: false,
        mediaInfo: {},
        position: 0,
        duration: 0,
        _startPosition: startPosition,
        zapTarget: null,
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

    // Exit OS fullscreen FIRST so the window decoration is back before the
    // React tree re-renders the menu inside a still-fullscreen window. The
    // IPC handler calls BrowserWindow.setFullScreen synchronously on main,
    // so the OS-level transition starts immediately; the await is omitted
    // intentionally because callers (button onClicks) aren't promise-aware
    // and the order — not the await — is what fixes the visual jank.
    if (fullscreen && window.api) {
      window.api.player.setFullscreen(false).catch(() => {});
    }

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
      currentContentType: undefined,
      currentHistoryId: undefined,
      position: 0,
      duration: 0,
      subtitleTracks: [],
      audioTracks: [],
      subtitleDelay: 0,
      audioDelay: 0,
      videoZoom: 1,
      mediaInfo: {},
      showSettings: false,
      showAspectMenu: false,
      fullscreen: false,
      _startPosition: undefined,
      zapTarget: null,
    });
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
    // The main-process handler calls sub-add directly once the user picks a
    // file, so we don't need a second IPC round-trip from the renderer.
    await window.api.player.loadSubtitleFile();
  },

  setSubtitleTrack: (id: number) => {
    if (get().backend === 'mpv') {
      window.api?.player.setSubtitleTrack(id).catch(() => {});
    }
  },

  setAudioTrack: (id: number) => {
    if (get().backend === 'mpv') {
      window.api?.player.setAudioTrack(id).catch(() => {});
    }
  },

  setSubtitleDelay: (seconds: number) => {
    const clamped = Math.max(-60, Math.min(60, Math.round(seconds * 100) / 100));
    if (get().backend === 'mpv') {
      window.api?.player.setSubtitleDelay(clamped).catch(() => {});
    }
    set({ subtitleDelay: clamped });
  },

  adjustSubtitleDelay: (deltaSeconds: number) => {
    const current = get().subtitleDelay;
    get().setSubtitleDelay(current + deltaSeconds);
  },

  setAudioDelay: (seconds: number) => {
    const clamped = Math.max(-10, Math.min(10, Math.round(seconds * 100) / 100));
    if (get().backend === 'mpv') {
      window.api?.player.setAudioDelay(clamped).catch(() => {});
    }
    set({ audioDelay: clamped });
  },

  adjustAudioDelay: (deltaSeconds: number) => {
    const current = get().audioDelay;
    get().setAudioDelay(current + deltaSeconds);
  },

  setVideoZoom: (factor: number) => {
    const clamped = Math.max(0.5, Math.min(3, Math.round(factor * 100) / 100));
    if (get().backend === 'mpv') {
      window.api?.player.setVideoZoom(clamped).catch(() => {});
    }
    set({ videoZoom: clamped });
  },

  adjustVideoZoom: (delta: number) => {
    const current = get().videoZoom;
    get().setVideoZoom(current + delta);
  },

  takeScreenshot: async () => {
    if (!window.api || get().backend !== 'mpv') return null;
    const res = await window.api.player.takeScreenshot().catch(() => null);
    return res?.ok ? res.path ?? null : null;
  },

  setError: (error: string | undefined) => set({ error }),
  setMode: (mode: PlayerMode) => set({ mode }),
  expand: () => {
    // Promotes mini → theater. No-op if we're idle or already in theater.
    const { mode, backend } = get();
    if (mode !== 'mini') return;
    if (backend === 'mpv' && window.api?.player?.setPresentation) {
      // mpv: defer the local `mode` update to the PLAYER_MODE_BROADCAST
      // that arrives after main process has resized the video child window
      // and shown the overlay — both happen synchronously in the
      // PLAYER_SET_PRESENTATION handler. Updating local mode optimistically
      // would let React commit the menu-hide + MiniPlayer-unmount before
      // the visual theater state lands, producing a ~50ms flash where the
      // sidebar is gone but the mpv surface is still mini-sized and the
      // overlay chrome hasn't appeared. The broadcast-driven update fires
      // AFTER those operations, so the React commit and the visual state
      // arrive together.
      window.api.player.setPresentation('theater').catch(() => {
        // IPC failed → fall back to the optimistic update so the UI doesn't
        // appear frozen on the user's click.
        set({ mode: 'theater' });
      });
    } else {
      // html5 / other: no IPC involved — flip locally so VideoStage moves
      // the html5 <video> element into the theater-sized box and
      // PlayerContainer's chrome mounts.
      set({ mode: 'theater' });
    }
  },
  minimize: () => {
    // Drops theater → mini, closing any open settings panels so they don't
    // float orphaned over the docked player. No-op if we're already in mini
    // or idle.
    const { mode, backend } = get();
    if (mode !== 'theater') return;
    if (backend === 'mpv' && window.api?.player?.setPresentation) {
      // Same flash-avoidance principle as expand(): main process handles
      // the video-window shrink + overlay-hide in one synchronous block,
      // *then* broadcasts MODE='mini'. The renderer commits the
      // chrome-swap once visual state has already changed. Critical detail:
      // customBounds is preserved across theater (the presentationMode
      // setter switches syncBounds' branch instead of clearing the rect),
      // so the shrink lands at the correct mini bounds without waiting for
      // MiniPlayer's mount-effect to re-push them.
      window.api.player.setPresentation('mini').catch(() => {
        set({ mode: 'mini', showSettings: false, showAspectMenu: false });
      });
    } else {
      set({ mode: 'mini', showSettings: false, showAspectMenu: false });
    }
  },
  setShowSettings: (show: boolean) => set({ showSettings: show }),
  openSettings: (tab) =>
    set((s) => {
      // Clicking the same control that opened the panel toggles it closed.
      if (s.showSettings && s.settingsTab === tab) {
        return { showSettings: false, showAspectMenu: false };
      }
      return { showSettings: true, settingsTab: tab, showAspectMenu: false };
    }),
  toggleSettings: () =>
    set((s) => ({ showSettings: !s.showSettings, showAspectMenu: false })),
  toggleAspectMenu: () =>
    set((s) => ({ showAspectMenu: !s.showAspectMenu, showSettings: false })),
  setShowAspectMenu: (show) => set({ showAspectMenu: show }),
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
  const unsubState = window.api.player.onStateChange((raw: unknown) => {
    const mpvState = raw as {
      status?: string;
      position?: number;
      duration?: number;
      volume?: number;
      muted?: boolean;
      speed?: number;
      aspectRatio?: string;
      fullscreen?: boolean;
      subtitleDelay?: number;
      audioDelay?: number;
      videoZoom?: number;
      subtitleTracks?: SubtitleTrack[];
      audioTracks?: AudioTrack[];
      mediaInfo?: MediaInfo;
      currentUrl?: string;
      reconnectAttempt?: number;
      reconnectMaxAttempts?: number;
    };
    const { backend, mode } = usePlayerStore.getState();
    if (backend !== 'mpv') return;

    const update: Partial<PlayerStoreState> = {};

    if (mpvState.status) {
      update.status = mpvState.status as PlayerStoreState['status'];
      // If mpv reports idle or stopped while we're showing player UI (mini or
      // theater), the stream ended, the user clicked Back/Escape on the
      // overlay, or mpv exited — drive the local store back to idle so the
      // mini-player / theater dismantles itself. Without this, a Back click
      // in the transparent overlay updates only the overlay's Zustand store;
      // the main window stays stuck displaying stale player chrome.
      if (
        (mpvState.status === 'idle' || mpvState.status === 'stopped') &&
        (mode === 'theater' || mode === 'mini')
      ) {
        usePlayerStore.getState().stop();
        return;
      }
    }
    // Reconnect counters: forward even when undefined so the UI can clear the
    // banner once the stream comes back.
    update.reconnectAttempt = mpvState.reconnectAttempt;
    update.reconnectMaxAttempts = mpvState.reconnectMaxAttempts;
    if (typeof mpvState.position === 'number') update.position = mpvState.position;
    if (typeof mpvState.duration === 'number') update.duration = mpvState.duration;
    if (typeof mpvState.volume === 'number') update.volume = mpvState.volume;
    if (typeof mpvState.muted === 'boolean') update.muted = mpvState.muted;
    if (typeof mpvState.speed === 'number') update.speed = mpvState.speed;
    if (typeof mpvState.fullscreen === 'boolean') update.fullscreen = mpvState.fullscreen;
    if (typeof mpvState.subtitleDelay === 'number') update.subtitleDelay = mpvState.subtitleDelay;
    if (typeof mpvState.audioDelay === 'number') update.audioDelay = mpvState.audioDelay;
    if (typeof mpvState.videoZoom === 'number') update.videoZoom = mpvState.videoZoom;
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

  // Mode broadcasts keep the main-window and overlay-window stores aligned.
  // A minimize fired from the overlay's TheaterControls (mpv backend) updates
  // the overlay's local mode, then this broadcast tells the main window to
  // flip to 'mini' so MiniPlayer mounts and pushes its bounds. The receiver
  // ignores broadcasts that match the local mode to avoid re-entrant loops.
  if (window.api.player.onModeBroadcast) {
    const unsubMode = window.api.player.onModeBroadcast((mode) => {
      const current = usePlayerStore.getState().mode;
      if (current === mode) return;
      if (mode !== 'theater' && mode !== 'mini' && mode !== 'idle') return;
      usePlayerStore.setState({ mode });
    });
    cleanups.push(unsubMode);
  }

  // --- History position updates (both backends) ---
  // Track the last update per historyId so switching content rapidly doesn't
  // cause us to skip the first save on the new item.
  const lastHistoryUpdateAt = new Map<string, number>();

  const interval = setInterval(() => {
    const { currentHistoryId, duration, status, backend, position } = usePlayerStore.getState();
    if (!currentHistoryId || status !== 'playing') return;

    const now = Date.now();
    const last = lastHistoryUpdateAt.get(currentHistoryId) ?? 0;
    if (now - last < 10_000) return;
    lastHistoryUpdateAt.set(currentHistoryId, now);

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

    // Garbage-collect entries we'll never see again so the map doesn't grow
    // unbounded over a long session.
    if (lastHistoryUpdateAt.size > 100) {
      const cutoff = now - 60 * 60_000; // 1 hour
      for (const [id, t] of lastHistoryUpdateAt) {
        if (t < cutoff && id !== currentHistoryId) lastHistoryUpdateAt.delete(id);
      }
    }
  }, 5_000);
  cleanups.push(() => {
    clearInterval(interval);
    lastHistoryUpdateAt.clear();
  });

  const cleanup = () => {
    for (const fn of cleanups) fn();
  };

  _playerListenersCleanup = cleanup;
  return cleanup;
}
