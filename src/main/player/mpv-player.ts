import { spawn, type ChildProcess } from 'child_process';
import { randomUUID } from 'crypto';
import fs from 'fs';
import path from 'path';
import { app } from 'electron';
import log from 'electron-log/main';
import type {
  IPlayer,
  PlayOptions,
  PlayerState,
  PlayerEventMap,
  MediaInfo,
  SubtitleTrack,
  AudioTrack,
  AspectRatio,
} from './player.interface';
import { MpvIpc } from './mpv-ipc';
import { findMpvPath } from './mpv-path';
import { getPlaybackArgs, getSubtitleAppearanceArgs, getNetworkArgs } from './mpv-args';
import { getSetting } from '../services/settings-service';

const CONNECT_RETRY_DELAY = 300;
const CONNECT_MAX_RETRIES = 20; // 20 * 300ms = 6 seconds — enough for cold mpv starts

function defaultState(): PlayerState {
  return {
    status: 'idle',
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
  };
}

export class MpvPlayer implements IPlayer {
  private process: ChildProcess | null = null;
  private ipc: MpvIpc | null = null;
  private pipeName: string;
  private mpvPath: string | null = null;
  private state: PlayerState = defaultState();
  private media: MediaInfo = {};
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private listeners: { [K in keyof PlayerEventMap]?: Set<any> } = {};
  private destroyed = false;
  private screenshotDir: string;

  constructor() {
    this.pipeName = `mpv-yancotv-${randomUUID().slice(0, 8)}`;
    this.mpvPath = findMpvPath();
    this.screenshotDir = path.join(app.getPath('pictures'), 'YancoTV');
  }

  async play(url: string, options?: PlayOptions): Promise<void> {
    if (!this.mpvPath) {
      throw new Error(
        'mpv not found. Install mpv and ensure it is in your PATH or place mpv.exe in the app directory.',
      );
    }

    // If mpv is already running, just load the new file
    if (this.process && this.ipc?.isConnected()) {
      await this.ipc.command(['loadfile', url, 'replace']);
      if (options?.startPosition && options.startPosition > 0) {
        // Wait briefly for file to load before seeking
        await this.waitForEvent('file-loaded');
        await this.ipc.command(['seek', options.startPosition, 'absolute']);
      }
      if (options?.subtitleFile) {
        await this.ipc.command(['sub-add', options.subtitleFile]);
      }
      this.state.currentUrl = url;
      return;
    }

    // Spawn a new mpv process
    await this.spawnMpv(url, options);
  }

  async pause(): Promise<void> {
    this.ensureConnected();
    await this.ipc!.command(['set_property', 'pause', true]);
  }

  async resume(): Promise<void> {
    this.ensureConnected();
    await this.ipc!.command(['set_property', 'pause', false]);
  }

  async stop(): Promise<void> {
    if (!this.ipc?.isConnected()) {
      this.state = defaultState();
      this.emitEvent('state-change', this.state);
      return;
    }
    try {
      await this.ipc.command(['stop']);
    } catch {
      // Process might already be gone
    }
    this.state = {
      ...defaultState(),
      volume: this.state.volume,
      muted: this.state.muted,
      speed: this.state.speed,
      aspectRatio: this.state.aspectRatio,
    };
    this.state.status = 'stopped';
    this.media = {};
    this.emitEvent('state-change', this.state);
  }

  async seek(seconds: number): Promise<void> {
    this.ensureConnected();
    await this.ipc!.command(['seek', seconds, 'absolute']);
  }

  async setVolume(level: number): Promise<void> {
    const clamped = Math.max(0, Math.min(100, level));
    if (this.ipc?.isConnected()) {
      await this.ipc.command(['set_property', 'volume', clamped]);
    }
    this.state.volume = clamped;
    this.emitEvent('state-change', this.state);
  }

  getState(): PlayerState {
    return { ...this.state, mediaInfo: { ...this.media } };
  }

  getMediaInfo(): MediaInfo {
    return { ...this.media };
  }

  async toggleMute(): Promise<void> {
    if (this.ipc?.isConnected()) {
      await this.ipc.command(['cycle', 'mute']);
    } else {
      this.state.muted = !this.state.muted;
      this.emitEvent('state-change', this.state);
    }
  }

  async setSpeed(speed: number): Promise<void> {
    const clamped = Math.max(0.25, Math.min(4, speed));
    if (this.ipc?.isConnected()) {
      await this.ipc.command(['set_property', 'speed', clamped]);
    }
    this.state.speed = clamped;
    this.emitEvent('state-change', this.state);
  }

  async setAspectRatio(ratio: AspectRatio): Promise<void> {
    if (this.ipc?.isConnected()) {
      if (ratio === 'auto') {
        await this.ipc.command(['set_property', 'video-aspect-override', '-1']);
        await this.ipc.command(['set_property', 'panscan', 0]);
      } else if (ratio === 'fill') {
        await this.ipc.command(['set_property', 'panscan', 1]);
      } else {
        await this.ipc.command(['set_property', 'panscan', 0]);
        await this.ipc.command(['set_property', 'video-aspect-override', ratio]);
      }
    }
    this.state.aspectRatio = ratio;
    this.emitEvent('state-change', this.state);
  }

  async toggleFullscreen(): Promise<void> {
    if (this.ipc?.isConnected()) {
      await this.ipc.command(['cycle', 'fullscreen']);
    }
  }

  getSubtitleTracks(): SubtitleTrack[] {
    return this.state.subtitleTracks;
  }

  async setSubtitleTrack(id: number): Promise<void> {
    this.ensureConnected();
    await this.ipc!.command(['set_property', 'sid', id]);
  }

  async toggleSubtitles(): Promise<void> {
    this.ensureConnected();
    await this.ipc!.command(['cycle', 'sub-visibility']);
  }

  async addSubtitleFile(filePath: string): Promise<void> {
    this.ensureConnected();
    await this.ipc!.command(['sub-add', filePath]);
  }

  getAudioTracks(): AudioTrack[] {
    return this.state.audioTracks;
  }

  async setAudioTrack(id: number): Promise<void> {
    this.ensureConnected();
    await this.ipc!.command(['set_property', 'aid', id]);
  }

  async setSubtitleDelay(seconds: number): Promise<void> {
    const clamped = Math.max(-60, Math.min(60, seconds));
    if (this.ipc?.isConnected()) {
      await this.ipc.command(['set_property', 'sub-delay', clamped]);
    }
    this.state.subtitleDelay = clamped;
    this.emitEvent('state-change', this.state);
  }

  async setAudioDelay(seconds: number): Promise<void> {
    const clamped = Math.max(-10, Math.min(10, seconds));
    if (this.ipc?.isConnected()) {
      await this.ipc.command(['set_property', 'audio-delay', clamped]);
    }
    this.state.audioDelay = clamped;
    this.emitEvent('state-change', this.state);
  }

  async setVideoZoom(factor: number): Promise<void> {
    const clamped = Math.max(0.5, Math.min(3, factor));
    // mpv's `video-zoom` is a log2 scale: 0 = 1x, 1 = 2x, -1 = 0.5x.
    const logZoom = Math.log2(clamped);
    if (this.ipc?.isConnected()) {
      await this.ipc.command(['set_property', 'video-zoom', logZoom]);
    }
    this.state.videoZoom = clamped;
    this.emitEvent('state-change', this.state);
  }

  async takeScreenshot(): Promise<string> {
    this.ensureConnected();
    await fs.promises.mkdir(this.screenshotDir, { recursive: true });
    const filename = `yancotv-${Date.now()}.png`;
    const outPath = path.join(this.screenshotDir, filename);
    await this.ipc!.command(['screenshot-to-file', outPath, 'video']);
    return outPath;
  }

  on<K extends keyof PlayerEventMap>(event: K, handler: PlayerEventMap[K]): void {
    if (!this.listeners[event]) {
      this.listeners[event] = new Set();
    }
    this.listeners[event]!.add(handler);
  }

  off<K extends keyof PlayerEventMap>(event: K, handler: PlayerEventMap[K]): void {
    this.listeners[event]?.delete(handler);
  }

  async destroy(): Promise<void> {
    this.destroyed = true;
    if (this.ipc) {
      try {
        if (this.ipc.isConnected()) {
          await this.ipc.command(['quit']);
        }
      } catch {
        // Ignore — we're shutting down
      }
      this.ipc.destroy();
      this.ipc = null;
    }
    if (this.process) {
      this.process.kill();
      this.process = null;
    }
    this.state = defaultState();
    this.media = {};
    this.listeners = {};
  }

  // --- Private ---

  private async spawnMpv(url: string, options?: PlayOptions): Promise<void> {
    // Generate a fresh pipe name for each spawn to avoid stale pipe conflicts
    // after a crash (Bug 10 fix)
    this.pipeName = `mpv-yancotv-${randomUUID().slice(0, 8)}`;

    // Re-resolve mpv path so a Settings → Advanced override takes effect on
    // the next stream (instead of needing an app restart).
    const resolvedMpv = findMpvPath();
    if (resolvedMpv) this.mpvPath = resolvedMpv;
    if (!this.mpvPath) {
      throw new Error(
        'mpv not found. Install mpv and ensure it is in your PATH or set a custom path in Settings → Advanced.',
      );
    }

    const embedded = typeof options?.wid === 'string' && options.wid.length > 0;

    // Settings → Playback → Hardware acceleration. Default on.
    const hwAccel = getSetting('playback_hw_accel') !== '0';

    const args = [
      url,
      `--input-ipc-server=\\\\.\\pipe\\${this.pipeName}`,
      '--no-terminal',
      '--keep-open=yes',
      '--idle=once',
      `--volume=${this.state.volume}`,
      hwAccel ? '--hwdec=auto' : '--hwdec=no',
      // Embedded mode: our React overlay draws controls, disable mpv's OSC.
      // Standalone mode: show mpv's built-in OSC.
      embedded ? '--osc=no' : '--osc=yes',
      // Cache / network tuning — differs for live vs VOD so playback is
      // smooth instead of stuttering when the upstream server burps.
      ...getPlaybackArgs({
        isLive: options?.isLive ?? false,
        bufferPreset: getSetting('playback_buffer_size'),
        networkTimeoutSecs: (() => {
          const raw = getSetting('network_connection_timeout');
          const n = raw ? Number(raw) : NaN;
          return Number.isFinite(n) && n > 0 ? n : undefined;
        })(),
      }),
      // User-configurable network layer: user-agent, proxy, IPv4 preference.
      // Per-call UA (source override) beats the global setting.
      ...getNetworkArgs({
        userAgent: options?.userAgent?.trim() || getSetting('network_user_agent'),
        proxyEnabled: getSetting('network_proxy_enabled') === '1',
        proxyType: getSetting('network_proxy_type'),
        proxyHost: getSetting('network_proxy_host'),
        proxyPort: getSetting('network_proxy_port'),
        preferIpv4: getSetting('network_prefer_ipv4') === '1',
      }),
      // User-configurable subtitle appearance (scale, color, background).
      ...getSubtitleAppearanceArgs({
        scale: getSetting('subtitle_scale'),
        color: getSetting('subtitle_color'),
        backOpacity: getSetting('subtitle_back_opacity'),
      }),
    ];

    if (embedded) {
      // Embed into the provided HWND (Electron main window). mpv creates a
      // child HWND that fills the parent's client area and composites its
      // video surface above any HTML content in that window.
      args.push(`--wid=${options!.wid}`);
    } else {
      // Standalone: mpv runs in its own borderless window with OSC controls.
      args.push('--force-window=yes');
      args.push('--title=YancoTV Player');
      args.push('--border=no');
    }

    if (options?.startPosition && options.startPosition > 0) {
      args.push(`--start=${options.startPosition}`);
    }

    if (options?.subtitleFile) {
      args.push(`--sub-file=${options.subtitleFile}`);
    }

    log.info(`Spawning mpv: ${this.mpvPath} ${args.join(' ')}`);

    this.process = spawn(this.mpvPath!, args, {
      stdio: 'ignore',
      windowsHide: false,
      detached: false,
    });

    this.process.on('error', (err) => {
      log.error('mpv process error:', err.message);
      this.state.status = 'error';
      this.emitEvent('error', err);
      this.emitEvent('state-change', this.state);
    });

    this.process.on('exit', (code) => {
      log.info(`mpv process exited with code ${code}`);
      if (!this.destroyed) {
        this.state = defaultState();
        this.media = {};
        this.emitEvent('state-change', this.state);
      }
      this.ipc?.destroy();
      this.ipc = null;
      this.process = null;
    });

    this.state.currentUrl = url;
    this.state.status = 'buffering';
    this.emitEvent('state-change', this.state);

    // Connect to the IPC pipe (mpv needs a moment to create it)
    await this.connectIpc();
    await this.setupPropertyObservers();
  }

  private async connectIpc(): Promise<void> {
    this.ipc = new MpvIpc(this.pipeName);

    // Prevent uncaught EventEmitter errors from crashing the process.
    // The 'error' event on MpvIpc fires for post-connection socket errors.
    this.ipc.on('error', (err: Error) => {
      log.error('mpv IPC EventEmitter error:', err.message);
      this.state.status = 'error';
      this.emitEvent('error', err);
      this.emitEvent('state-change', this.state);
    });

    for (let i = 0; i < CONNECT_MAX_RETRIES; i++) {
      // If mpv process died during retries, bail out
      if (!this.process || this.destroyed) {
        throw new Error('mpv process exited before IPC connection was established');
      }

      try {
        await this.ipc.connect();
        return;
      } catch {
        if (i === CONNECT_MAX_RETRIES - 1) {
          throw new Error(
            `Failed to connect to mpv IPC pipe after ${CONNECT_MAX_RETRIES} retries (pipe: ${this.pipeName})`,
          );
        }
        await sleep(CONNECT_RETRY_DELAY);
      }
    }
  }

  private async setupPropertyObservers(): Promise<void> {
    if (!this.ipc) return;

    this.ipc.on('property-change', (change: { name: string; data: unknown }) => {
      this.handlePropertyChange(change.name, change.data);
    });

    this.ipc.on('mpv-event', (msg: { event: string; reason?: string }) => {
      this.handleMpvEvent(msg);
    });

    this.ipc.on('close', () => {
      if (!this.destroyed) {
        this.state = defaultState();
        this.emitEvent('state-change', this.state);
      }
    });

    // Observe key properties
    await this.ipc.observeProperty('time-pos');
    await this.ipc.observeProperty('duration');
    await this.ipc.observeProperty('pause');
    await this.ipc.observeProperty('volume');
    await this.ipc.observeProperty('mute');
    await this.ipc.observeProperty('core-idle');
    await this.ipc.observeProperty('idle-active');
    await this.ipc.observeProperty('speed');
    await this.ipc.observeProperty('fullscreen');
    await this.ipc.observeProperty('track-list');
    // Subtitle text observation (for future translation pipeline)
    await this.ipc.observeProperty('sub-text');
    // Media info properties
    await this.ipc.observeProperty('video-codec');
    await this.ipc.observeProperty('audio-codec-name');
    await this.ipc.observeProperty('width');
    await this.ipc.observeProperty('height');
    await this.ipc.observeProperty('estimated-vf-fps');
    await this.ipc.observeProperty('video-bitrate');
    await this.ipc.observeProperty('hwdec-current');
    await this.ipc.observeProperty('sub-delay');
    await this.ipc.observeProperty('audio-delay');
    await this.ipc.observeProperty('video-zoom');
  }

  private handlePropertyChange(name: string, data: unknown): void {
    switch (name) {
      case 'time-pos':
        if (typeof data === 'number') {
          this.state.position = data;
          this.emitEvent('time-update', data);
        }
        break;
      case 'duration':
        if (typeof data === 'number') {
          this.state.duration = data;
        }
        break;
      case 'pause':
        if (typeof data === 'boolean') {
          this.state.status = data ? 'paused' : 'playing';
          this.emitEvent('state-change', this.state);
        }
        break;
      case 'volume':
        if (typeof data === 'number') {
          this.state.volume = data;
        }
        break;
      case 'mute':
        if (typeof data === 'boolean') {
          this.state.muted = data;
        }
        break;
      case 'core-idle':
        if (data === true && this.state.status === 'playing') {
          this.state.status = 'buffering';
          this.emitEvent('state-change', this.state);
        }
        break;
      case 'idle-active':
        if (data === true) {
          this.state.status = 'idle';
          this.state.currentUrl = undefined;
          this.emitEvent('state-change', this.state);
        }
        break;
      case 'speed':
        if (typeof data === 'number') {
          this.state.speed = data;
          this.emitEvent('state-change', this.state);
        }
        break;
      case 'fullscreen':
        if (typeof data === 'boolean') {
          this.state.fullscreen = data;
          this.emitEvent('state-change', this.state);
        }
        break;
      case 'track-list':
        if (Array.isArray(data)) {
          this.parseTrackList(data);
          this.emitEvent('state-change', this.state);
        }
        break;
      case 'sub-text':
        if (typeof data === 'string' && data.length > 0) {
          this.emitEvent('subtitle-text', data);
        }
        break;
      // Media info properties
      case 'video-codec':
        if (typeof data === 'string') this.media.videoCodec = data;
        break;
      case 'audio-codec-name':
        if (typeof data === 'string') this.media.audioCodec = data;
        break;
      case 'width':
        if (typeof data === 'number') this.media.width = data;
        break;
      case 'height':
        if (typeof data === 'number') this.media.height = data;
        break;
      case 'estimated-vf-fps':
        if (typeof data === 'number') this.media.fps = Math.round(data * 100) / 100;
        break;
      case 'video-bitrate':
        if (typeof data === 'number') this.media.bitrate = Math.round(data / 1000); // kbps
        break;
      case 'hwdec-current':
        if (typeof data === 'string') this.media.hwdec = data;
        break;
      case 'sub-delay':
        if (typeof data === 'number') {
          this.state.subtitleDelay = Math.round(data * 100) / 100;
        }
        break;
      case 'audio-delay':
        if (typeof data === 'number') {
          this.state.audioDelay = Math.round(data * 100) / 100;
        }
        break;
      case 'video-zoom':
        if (typeof data === 'number') {
          // mpv stores log2 zoom; convert back to plain factor.
          this.state.videoZoom = Math.round(Math.pow(2, data) * 100) / 100;
        }
        break;
    }
  }

  private parseTrackList(
    tracks: Array<{
      id: number;
      type: string;
      title?: string;
      lang?: string;
      selected?: boolean;
      codec?: string;
    }>,
  ): void {
    this.state.subtitleTracks = tracks
      .filter((t) => t.type === 'sub')
      .map((t) => ({
        id: t.id,
        title: t.title || t.lang || `Subtitle ${t.id}`,
        language: t.lang,
        selected: t.selected === true,
      }));

    this.state.audioTracks = tracks
      .filter((t) => t.type === 'audio')
      .map((t) => ({
        id: t.id,
        title: t.title || t.lang || `Audio ${t.id}`,
        language: t.lang,
        selected: t.selected === true,
      }));
  }

  private handleMpvEvent(msg: { event: string; reason?: string }): void {
    switch (msg.event) {
      case 'file-loaded':
        this.state.status = 'playing';
        this.emitEvent('state-change', this.state);
        break;
      case 'end-file':
        if (msg.reason === 'error') {
          this.state.status = 'error';
          this.emitEvent('error', new Error('Stream playback failed'));
        } else {
          this.state.status = 'stopped';
        }
        this.emitEvent('state-change', this.state);
        break;
    }
  }

  private emitEvent<K extends keyof PlayerEventMap>(
    event: K,
    ...args: Parameters<PlayerEventMap[K]>
  ): void {
    const handlers = this.listeners[event];
    if (handlers) {
      for (const handler of handlers) {
        try {
          (handler as (...a: Parameters<PlayerEventMap[K]>) => void)(...args);
        } catch (err) {
          log.error(`Player event handler error (${event}):`, err);
        }
      }
    }
  }

  private waitForEvent(eventName: string, timeoutMs = 10000): Promise<void> {
    return new Promise((resolve, reject) => {
      if (!this.ipc) {
        reject(new Error('No IPC connection'));
        return;
      }

      const timer = setTimeout(() => {
        this.ipc?.off('mpv-event', handler);
        resolve(); // Don't fail on timeout — stream might still work
      }, timeoutMs);

      const handler = (msg: { event: string }) => {
        if (msg.event === eventName) {
          clearTimeout(timer);
          this.ipc?.off('mpv-event', handler);
          resolve();
        }
      };

      this.ipc.on('mpv-event', handler);
    });
  }

  private ensureConnected(): void {
    if (!this.ipc?.isConnected()) {
      throw new Error('mpv is not running');
    }
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
