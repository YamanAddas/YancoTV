import { useEffect } from 'react';
import { usePlayerStore } from '../stores/player-store';

const SEEK_STEP = 10; // seconds
const VOLUME_STEP = 5;

/**
 * Global keyboard shortcuts for playback control.
 *
 * Space       — play/pause toggle
 * Left/Right  — seek back/forward 10s
 * Up/Down     — volume up/down 5%
 * M           — mute toggle
 * F           — toggle fullscreen
 * A           — cycle aspect ratio (auto → 16:9 → 4:3 → 21:9 → fill)
 * S           — cycle playback speed (0.5 → 0.75 → 1 → 1.25 → 1.5 → 2)
 * Escape      — stop playback
 */
export function usePlayerShortcuts(): void {
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      // Don't intercept shortcuts when typing in inputs
      const tag = (e.target as HTMLElement).tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

      const state = usePlayerStore.getState();
      const isActive = state.status === 'playing' || state.status === 'paused' || state.status === 'buffering';

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
          if (state.position > 0) {
            state.seek(Math.max(0, state.position - SEEK_STEP));
          }
          break;

        case 'ArrowRight':
          e.preventDefault();
          if (state.duration > 0) {
            state.seek(Math.min(state.duration, state.position + SEEK_STEP));
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
          state.cycleSpeed();
          break;

        case 'Escape':
          e.preventDefault();
          state.stop();
          break;
      }
    }

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);
}
