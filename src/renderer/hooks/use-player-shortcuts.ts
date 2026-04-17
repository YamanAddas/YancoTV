import { useEffect, useMemo } from 'react';
import { usePlayerStore } from '../stores/player-store';
import { getVideoElement } from '../components/player/video-ref';
import { useRecentChannelsStore } from '../stores/recent-channels-store';
import { useSettingsStore } from '../stores/settings-store';
import {
  SHORTCUTS_SETTING_KEY,
  buildKeyMap,
  normalizeKey,
  parseBindings,
  type ShortcutAction,
} from './shortcuts-registry';

const SEEK_STEP = 10; // seconds
const SEEK_STEP_LARGE = 30; // seconds (Shift+Arrow)
const VOLUME_STEP = 5;
const SPEED_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2];

/**
 * Global keyboard shortcuts for playback control.
 *
 * Rebindable actions (see `shortcuts-registry.ts`) are resolved via the
 * settings-store `shortcuts_bindings` JSON map. Arrow keys (seek + volume),
 * Escape, and F11 are fixed — users can't rebind themselves out of them.
 */
export function usePlayerShortcuts(): void {
  const rawBindings = useSettingsStore((s) => s.data[SHORTCUTS_SETTING_KEY] ?? '');
  const keyMap = useMemo(() => buildKeyMap(parseBindings(rawBindings)), [rawBindings]);

  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      const tag = (e.target as HTMLElement).tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

      const state = usePlayerStore.getState();
      const isActive =
        state.status === 'playing' ||
        state.status === 'paused' ||
        state.status === 'buffering';
      const isTheater = state.mode === 'theater';

      const action = !e.ctrlKey && !e.metaKey && !e.altKey
        ? keyMap.get(normalizeKey(e.key))
        : undefined;

      // lastChannel works globally (not gated by isActive).
      if (action === 'lastChannel') {
        const targetId = useRecentChannelsStore.getState().previous();
        if (targetId && window.api) {
          e.preventDefault();
          (async () => {
            try {
              const detail = await window.api.content.getDetail(targetId);
              const item = detail?.item;
              if (item?.streamUrl) {
                state.play(
                  item.streamUrl,
                  item.cleanTitle || item.title,
                  item.id,
                  undefined,
                  'live',
                );
              }
            } catch {
              // No detail → silently no-op.
            }
          })();
          return;
        }
      }

      // Escape always works in theater mode (even on error).
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

      // F11 is always fullscreen toggle (fixed).
      if (e.key === 'F11' && isActive) {
        e.preventDefault();
        state.toggleFullscreen();
        return;
      }

      if (!isActive) return;

      // Arrow keys are fixed (seek + volume).
      switch (e.key) {
        case 'ArrowLeft':
          e.preventDefault();
          {
            const pos = state.backend === 'mpv'
              ? state.position
              : (getVideoElement()?.currentTime ?? state.position);
            if (pos > 0) {
              const step = e.shiftKey ? SEEK_STEP_LARGE : SEEK_STEP;
              state.seek(Math.max(0, pos - step));
            }
          }
          return;

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
          return;

        case 'ArrowUp':
          e.preventDefault();
          state.setVolume(Math.min(100, state.volume + VOLUME_STEP));
          return;

        case 'ArrowDown':
          e.preventDefault();
          state.setVolume(Math.max(0, state.volume - VOLUME_STEP));
          return;
      }

      if (!action) return;

      switch (action) {
        case 'playPause':
          e.preventDefault();
          if (state.status === 'playing' || state.status === 'buffering') {
            state.pause();
          } else if (state.status === 'paused') {
            state.resume();
          }
          break;

        case 'muteToggle':
          e.preventDefault();
          state.toggleMute();
          break;

        case 'fullscreen':
          e.preventDefault();
          state.toggleFullscreen();
          break;

        case 'aspectRatio':
          e.preventDefault();
          state.cycleAspectRatio();
          break;

        case 'subtitles':
          e.preventDefault();
          state.toggleSubtitles();
          break;

        case 'toggleSettings':
        case 'toggleInfo':
          e.preventDefault();
          usePlayerStore.setState((s) => ({ showSettings: !s.showSettings }));
          break;

        case 'speedDown':
          e.preventDefault();
          {
            const idx = SPEED_STEPS.indexOf(state.speed);
            if (idx > 0) state.setSpeed(SPEED_STEPS[idx - 1]);
          }
          break;

        case 'speedUp':
          e.preventDefault();
          {
            const idx = SPEED_STEPS.indexOf(state.speed);
            if (idx < SPEED_STEPS.length - 1) state.setSpeed(SPEED_STEPS[idx + 1]);
          }
          break;

        case 'speedReset':
          e.preventDefault();
          if (state.speed !== 1) state.setSpeed(1);
          break;
      }
      void (action satisfies ShortcutAction);
    }

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [keyMap]);
}
