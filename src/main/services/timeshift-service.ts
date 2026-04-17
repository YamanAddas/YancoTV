import log from 'electron-log/main';
import { BrowserWindow } from 'electron';
import { IpcChannels } from '../../shared/ipc-channels';
import { getLivePlaybackArgs } from '../player/mpv-args';

// ---------------------------------------------------------------------------
// Timeshift Service
//
// Manages pause/rewind state for live TV. The actual buffering is handled by
// mpv's built-in cache when configured with --demuxer-max-back-bytes.
//
// We track the timeshift state here and broadcast it to the renderer so the
// UI can show a timeshift indicator and allow seeking within the buffer.
// ---------------------------------------------------------------------------

export interface TimeshiftState {
  /** Whether timeshift mode is active */
  active: boolean;
  /** Maximum seconds available in the rewind buffer */
  bufferSeconds: number;
  /** Current offset from live edge (0 = live, negative = behind) */
  offsetSeconds: number;
  /** Timestamp when timeshift was activated */
  activatedAt?: number;
}

let state: TimeshiftState = {
  active: false,
  bufferSeconds: 0,
  offsetSeconds: 0,
};

// Default buffer size: 30 minutes of rewind
const DEFAULT_BUFFER_SECONDS = 30 * 60;

/**
 * Get mpv args to enable timeshift buffering for live TV.
 * @deprecated Use `getLivePlaybackArgs()` from `src/main/player/mpv-args.ts`.
 *   Kept as a thin re-export so consumers/tests don't break while the call
 *   sites migrate. The new module separates VOD and live tuning and fixes
 *   the stutter caused by the old `--cache-pause=no` on VOD streams.
 */
export function getTimeshiftMpvArgs(bufferSeconds?: number): string[] {
  return getLivePlaybackArgs(bufferSeconds);
}

/**
 * Activate timeshift mode (called when user pauses live TV).
 */
export function activateTimeshift(): void {
  if (state.active) return;

  state = {
    active: true,
    bufferSeconds: DEFAULT_BUFFER_SECONDS,
    offsetSeconds: 0,
    activatedAt: Date.now(),
  };

  broadcastTimeshiftState();
  log.info('Timeshift activated');
}

/**
 * Update timeshift offset (called when user seeks within buffer).
 */
export function updateTimeshiftOffset(offsetSeconds: number): void {
  state.offsetSeconds = offsetSeconds;
  broadcastTimeshiftState();
}

/**
 * Deactivate timeshift (called when user resumes live or changes channel).
 */
export function deactivateTimeshift(): void {
  if (!state.active) return;

  state = {
    active: false,
    bufferSeconds: 0,
    offsetSeconds: 0,
  };

  broadcastTimeshiftState();
  log.info('Timeshift deactivated');
}

/**
 * Get current timeshift state.
 */
export function getTimeshiftState(): TimeshiftState {
  return { ...state };
}

/**
 * Broadcast timeshift state to all renderer windows.
 */
function broadcastTimeshiftState(): void {
  for (const win of BrowserWindow.getAllWindows()) {
    try {
      win.webContents.send(IpcChannels.TIMESHIFT_STATE, state);
    } catch {
      // Window may have been closed
    }
  }
}
