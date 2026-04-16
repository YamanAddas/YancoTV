import { useEffect } from 'react';
import { usePlayerStore } from '../stores/player-store';
import { getVideoElement } from '../components/player/video-ref';

const SEEK_STEP = 10; // seconds
const SEEK_STEP_LARGE = 30; // seconds (Shift+Arrow)
const VOLUME_STEP = 5;
const SPEED_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2];

/**
 * Global keyboard shortcuts for playback control.
 *
 * Space          — play/pause toggle
 * Left/Right     — seek back/forward 10s
 * Shift+Left/Right — seek back/forward 30s
 * Up/Down        — volume up/down 5%
 * M              — mute toggle
 * F / F11        — toggle fullscreen
 * A              — cycle aspect ratio
 * S              — toggle subtitles
 * G              — toggle settings panel
 * I              — toggle info (settings → info tab)
 * [ / ]          — decrease / increase speed
 * Backspace      — reset speed to 1x
 * Escape         — exit theater mode / close settings / stop
 */
export function usePlayerShortcuts(): void {
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      // Don't intercept shortcuts when typing in inputs
      const tag = (e.target as HTMLElement).tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

      const state = usePlayerStore.getState();
      const isActive = state.status === 'playing' || state.status === 'paused' || state.status === 'buffering';
      const isTheater = state.mode === 'theater';

      // Escape always works in theater mode (even on error)
      if (e.key === 'Escape' && isTheater) {
        e.preventDefault();
        if (state.showSettings) {
          usePlayerStore.setState({ showSettings: false });
        } else if (state.fullscreen) {
          state.toggleFullscreen();
        } else {
          state.stop();
        }
        return;
      }

      if (!isActive) return;

      switch (e.key) {
        case ' ':
          e.preventDefault();
          if (state.status === 'playing' || state.status === 'buffering') {
            state.pause();
          } else if (state.status === 'paused') {
            state.resume();
          }
          break;

        case 'ArrowLeft':
          e.preventDefault();
          {
            // mpv: position comes from store (fed by IPC push).
            // html5: read from video element for accuracy.
            const pos = state.backend === 'mpv'
              ? state.position
              : (getVideoElement()?.currentTime ?? state.position);
            if (pos > 0) {
              const step = e.shiftKey ? SEEK_STEP_LARGE : SEEK_STEP;
              state.seek(Math.max(0, pos - step));
            }
          }
          break;

        case 'ArrowRight':
          e.preventDefault();
          {
            const pos = state.backend === 'mpv'
              ? state.position
              : (getVideoElement()?.currentTime ?? state.position);
            if (state.duration > 0) {
              const step = e.shiftKey ? SEEK_STEP_LARGE : SEEK_STEP;
              state.seek(Math.min(state.duration, pos + step));
            }
          }
          break;

        case 'ArrowUp':
          e.preventDefault();
          state.setVolume(Math.min(100, state.volume + VOLUME_STEP));
          break;

        case 'ArrowDown':
          e.preventDefault();
          state.setVolume(Math.max(0, state.volume - VOLUME_STEP));
          break;

        case 'm':
        case 'M':
          e.preventDefault();
          state.toggleMute();
          break;

        case 'f':
        case 'F':
        case 'F11':
          e.preventDefault();
          state.toggleFullscreen();
          break;

        case 'a':
        case 'A':
          e.preventDefault();
          state.cycleAspectRatio();
          break;

        case 's':
        case 'S':
          e.preventDefault();
          state.toggleSubtitles();
          break;

        case 'g':
        case 'G':
          e.preventDefault();
          usePlayerStore.setState((s) => ({ showSettings: !s.showSettings }));
          break;

        case 'i':
        case 'I':
          e.preventDefault();
          // Toggle settings panel (info tab will be auto-selected by settings panel)
          usePlayerStore.setState((s) => ({ showSettings: !s.showSettings }));
          break;

        case '[':
          e.preventDefault();
          {
            const idx = SPEED_STEPS.indexOf(state.speed);
            if (idx > 0) {
              state.setSpeed(SPEED_STEPS[idx - 1]);
            }
          }
          break;

        case ']':
          e.preventDefault();
          {
            const idx = SPEED_STEPS.indexOf(state.speed);
            if (idx < SPEED_STEPS.length - 1) {
              state.setSpeed(SPEED_STEPS[idx + 1]);
            }
          }
          break;

        case 'Backspace':
          e.preventDefault();
          if (state.speed !== 1) {
            state.setSpeed(1);
          }
          break;

        // Escape is handled above (works in all theater states, not just isActive)
      }
    }

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);
}
