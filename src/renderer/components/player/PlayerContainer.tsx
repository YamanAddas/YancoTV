import { useEffect, useRef, useCallback } from 'react';
import { usePlayerStore } from '../../stores/player-store';
import { VideoPlayer } from './VideoPlayer';
import { TheaterControls } from './TheaterControls';
import { SettingsPanel } from './SettingsPanel';
import { AspectMenu } from './AspectMenu';

const CONTROLS_HIDE_DELAY = 3000;

/**
 * Integrated player — video fills the screen with auto-hiding controls on top.
 * This is the theater mode view: sidebar hidden, full-window video + overlay controls.
 */
export function PlayerContainer() {
  const containerRef = useRef<HTMLDivElement>(null);
  const hideTimerRef = useRef<ReturnType<typeof setTimeout>>();
  const status = usePlayerStore((s) => s.status);
  const mode = usePlayerStore((s) => s.mode);
  const backend = usePlayerStore((s) => s.backend);
  const showSettings = usePlayerStore((s) => s.showSettings);
  const showAspectMenu = usePlayerStore((s) => s.showAspectMenu);
  const controlsVisible = usePlayerStore((s) => s.controlsVisible);
  const setControlsVisible = usePlayerStore((s) => s.setControlsVisible);
  const setShowSettings = usePlayerStore((s) => s.setShowSettings);
  const setShowAspectMenu = usePlayerStore((s) => s.setShowAspectMenu);

  const isActive = status === 'playing' || status === 'paused' || status === 'buffering';

  // Auto-hide controls after inactivity
  const resetHideTimer = useCallback(() => {
    setControlsVisible(true);
    if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
    hideTimerRef.current = setTimeout(() => {
      const state = usePlayerStore.getState();
      if (
        state.status === 'playing' &&
        !state.showSettings &&
        !state.showAspectMenu
      ) {
        setControlsVisible(false);
      }
    }, CONTROLS_HIDE_DELAY);
  }, [setControlsVisible]);

  // Cleanup timer
  useEffect(() => {
    return () => {
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
    };
  }, []);

  // Auto-hide when playing, always show when paused/buffering
  useEffect(() => {
    if (status === 'playing') {
      resetHideTimer();
    } else {
      setControlsVisible(true);
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
    }
  }, [status, resetHideTimer, setControlsVisible]);

  if (mode !== 'theater') return null;

  // mpv backend renders video in a separate child window below this overlay —
  // the container must stay fully transparent so the video is visible. The
  // html5 backend paints its own <video> element and benefits from a black
  // backdrop to hide letterboxing.
  const isMpv = backend === 'mpv';

  return (
    <div
      ref={containerRef}
      className={`absolute inset-0 z-40 ${isMpv ? '' : 'bg-black'}`}
      onMouseMove={resetHideTimer}
      style={{ cursor: controlsVisible ? 'default' : 'none' }}
    >
      {/* mpv backend: video plays in its own window. This container shows status/controls.
          html5 backend: VideoPlayer renders the HTML5 video element here. */}
      {!isMpv && <VideoPlayer />}

      {/* Click-to-pause / double-click-fullscreen overlay */}
      <div
        className="absolute inset-0 z-[41]"
        onClick={() => {
          const state = usePlayerStore.getState();
          if (state.status === 'playing' || state.status === 'buffering') {
            state.pause();
          } else if (state.status === 'paused') {
            state.resume();
          }
        }}
        onDoubleClick={() => {
          usePlayerStore.getState().toggleFullscreen();
        }}
      />

      {/* Buffering spinner overlay */}
      {status === 'buffering' && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div className="flex flex-col items-center gap-3">
            <svg className="h-12 w-12 animate-spin text-accent" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
          </div>
        </div>
      )}

      {/* Error display */}
      {status === 'error' && (
        <div className="absolute inset-0 flex items-center justify-center">
          <div className="flex flex-col items-center gap-3 text-center">
            <svg className="h-12 w-12 text-red-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z" />
            </svg>
            <span className="text-sm text-red-400">
              {usePlayerStore.getState().error || 'Playback error'}
            </span>
            <span className="text-xs text-surface-500">Press Escape to go back</span>
          </div>
        </div>
      )}

      {/* Controls overlay — auto-hiding, transparent gradients */}
      {isActive && (
        <TheaterControls
          visible={controlsVisible}
          onInteraction={resetHideTimer}
        />
      )}

      {/* Settings panel */}
      {showSettings && (
        <SettingsPanel onClose={() => setShowSettings(false)} />
      )}

      {/* Compact aspect-ratio popover (separate from the full settings panel) */}
      {showAspectMenu && (
        <AspectMenu onClose={() => setShowAspectMenu(false)} />
      )}
    </div>
  );
}
