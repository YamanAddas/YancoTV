import { spawn, type ChildProcess } from 'child_process';
import { v4 as uuid } from 'uuid';
import log from 'electron-log/main';
import type {
  IPlayer,
  PlayOptions,
  PlayerState,
  PlayerEventMap,
  SubtitleTrack,
  AudioTrack,
} from './player.interface';
import { MpvIpc } from './mpv-ipc';
import { findMpvPath } from './mpv-path';

const CONNECT_RETRY_DELAY = 200;
const CONNECT_MAX_RETRIES = 15;

function defaultState(): PlayerState {
  return {
    status: 'idle',
    position: 0,
    duration: 0,
    volume: 100,
    muted: false,
  };
}

export class MpvPlayer implements IPlayer {
  private process: ChildProcess | null = null;
  private ipc: MpvIpc | null = null;
  private pipeName: string;
  private mpvPath: string | null = null;
  private state: PlayerState = defaultState();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private listeners: { [K in keyof PlayerEventMap]?: Set<any> } = {};
  private destroyed = false;

  constructor() {
    this.pipeName = `mpv-yancotv-${uuid().slice(0, 8)}`;
    this.mpvPath = findMpvPath();
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
    this.state = { ...defaultState(), volume: this.state.volume, muted: this.state.muted };
    this.state.status = 'stopped';
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
    return { ...this.state };
  }

  getSubtitleTracks(): SubtitleTrack[] {
    // Populated by property observations — stored when mpv reports track-list changes
    return [];
  }

  async setSubtitleTrack(id: number): Promise<void> {
    this.ensureConnected();
    await this.ipc!.command(['set_property', 'sid', id]);
  }

  async addSubtitleFile(filePath: string): Promise<void> {
    this.ensureConnected();
    await this.ipc!.command(['sub-add', filePath]);
  }

  getAudioTracks(): AudioTrack[] {
    return [];
  }

  async setAudioTrack(id: number): Promise<void> {
    this.ensureConnected();
    await this.ipc!.command(['set_property', 'aid', id]);
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
    this.listeners = {};
  }

  // --- Private ---

  private async spawnMpv(url: string, options?: PlayOptions): Promise<void> {
    const args = [
      url,
      `--input-ipc-server=\\\\.\\pipe\\${this.pipeName}`,
      '--no-terminal',
      '--keep-open=yes',
      '--idle=once',
      '--force-window=yes',
      '--title=YancoTV Player',
      `--volume=${this.state.volume}`,
    ];

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

    for (let i = 0; i < CONNECT_MAX_RETRIES; i++) {
      try {
        await this.ipc.connect();
        return;
      } catch {
        if (i === CONNECT_MAX_RETRIES - 1) {
          throw new Error('Failed to connect to mpv IPC pipe after retries');
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
    }
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
