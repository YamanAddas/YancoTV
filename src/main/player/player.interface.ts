export interface PlayOptions {
  startPosition?: number;
  subtitleFile?: string;
  /** Native window handle (HWND) to embed mpv into via --wid */
  wid?: string;
}

export type AspectRatio = 'auto' | '16:9' | '4:3' | '21:9' | 'fill';

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

  on<K extends keyof PlayerEventMap>(event: K, handler: PlayerEventMap[K]): void;
  off<K extends keyof PlayerEventMap>(event: K, handler: PlayerEventMap[K]): void;

  destroy(): Promise<void>;
}
