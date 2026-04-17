export interface PlayOptions {
  startPosition?: number;
  subtitleFile?: string;
  /** Native window handle (HWND) to embed mpv into via --wid */
  wid?: string;
  /**
   * True for live streams (IPTV channels). Controls mpv cache tuning:
   *   • live → large timeshift buffer for rewind + tolerant of network jitter
   *   • VOD  → moderate forward cache + pause-on-underrun (smoother playback)
   */
  isLive?: boolean;
  /**
   * Per-call User-Agent override. Set from the owning source's `user_agent`
   * column so providers that require a specific UA play correctly. Takes
   * precedence over the global `network_user_agent` setting.
   */
  userAgent?: string;
}

export type AspectRatio = 'auto' | '16:9' | '4:3' | '21:9' | '2.35:1' | '1:1' | 'fill';

export type PlayerMode = 'idle' | 'theater' | 'browse' | 'multiview';

export interface MediaInfo {
  videoCodec?: string;
  audioCodec?: string;
  width?: number;
  height?: number;
  fps?: number;
  bitrate?: number;
  pixelFormat?: string;
  hwdec?: string;
}

export interface PlayerState {
  status: 'idle' | 'playing' | 'paused' | 'buffering' | 'stopped' | 'error';
  position: number;
  duration: number;
  volume: number;
  muted: boolean;
  speed: number;
  aspectRatio: AspectRatio;
  fullscreen: boolean;
  /** Subtitle timing offset in seconds (positive = subs appear later) */
  subtitleDelay: number;
  /** Audio timing offset in seconds (positive = audio plays later) */
  audioDelay: number;
  /** Video zoom factor. 1 = fit, >1 = zoomed in, <1 = zoomed out */
  videoZoom: number;
  currentUrl?: string;
  subtitleTracks: SubtitleTrack[];
  audioTracks: AudioTrack[];
  mediaInfo?: MediaInfo;
}

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

export type PlayerEventMap = {
  'state-change': (state: PlayerState) => void;
  'time-update': (position: number) => void;
  'subtitle-text': (text: string) => void;
  error: (error: Error) => void;
};

export interface IPlayer {
  play(url: string, options?: PlayOptions): Promise<void>;
  pause(): Promise<void>;
  resume(): Promise<void>;
  stop(): Promise<void>;
  seek(seconds: number): Promise<void>;
  setVolume(level: number): Promise<void>;
  toggleMute(): Promise<void>;
  setSpeed(speed: number): Promise<void>;
  setAspectRatio(ratio: AspectRatio): Promise<void>;
  toggleFullscreen(): Promise<void>;
  getState(): PlayerState;
  getMediaInfo(): MediaInfo;

  getSubtitleTracks(): SubtitleTrack[];
  setSubtitleTrack(id: number): Promise<void>;
  toggleSubtitles(): Promise<void>;
  addSubtitleFile(path: string): Promise<void>;
  getAudioTracks(): AudioTrack[];
  setAudioTrack(id: number): Promise<void>;

  /** Subtitle timing offset (seconds). Applies to the active subtitle track. */
  setSubtitleDelay(seconds: number): Promise<void>;
  /** Audio timing offset (seconds). Use to fix lip-sync. */
  setAudioDelay(seconds: number): Promise<void>;
  /** Zoom factor. 1 = no zoom, 1.5 = 50% zoomed in, etc. */
  setVideoZoom(factor: number): Promise<void>;
  /** Save a screenshot of the current frame; returns the file path. */
  takeScreenshot(): Promise<string>;

  on<K extends keyof PlayerEventMap>(event: K, handler: PlayerEventMap[K]): void;
  off<K extends keyof PlayerEventMap>(event: K, handler: PlayerEventMap[K]): void;

  destroy(): Promise<void>;
}
