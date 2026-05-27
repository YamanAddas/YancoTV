import { useEffect, useRef, useCallback } from 'react';
import { usePlayerStore } from '../../stores/player-store';
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
  const reconnectAttempt = usePlayerStore((s) => s.reconnectAttempt);
  const reconnectMaxAttempts = usePlayerStore((s) => s.reconnectMaxAttempts);
  const setControlsVisible = usePlayerStore((s) => s.setControlsVisible);
  const setShowSettings = usePlayerStore((s) => s.setShowSettings);
  const setShowAspectMenu = usePlayerStore((s) => s.setShowAspectMenu);

  const isActive =
    status === 'playing' ||
    status === 'paused' ||
    status === 'buffering' ||
    status === 'reconnecting';

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

  // Drive the mpv presentation state to match this container's mode so the
  // dedicated mpv video child window and transparent controls overlay are
  // visible whenever theater mode is active, and torn down when it isn't.
  // (The main process keeps its own copy of presentation state; we just push
  // mode transitions so it can react.)
  useEffect(() => {
    if (backend !== 'mpv' || !window.api?.player?.setPresentation) return;
    if (mode === 'theater') {
      window.api.player.setPresentation('theater').catch(() => {});
    }
  }, [backend, mode]);

  if (mode !== 'theater') return null;

  // The theater overlay is transparent — VideoStage (for html5) or the mpv
  // child window (for mpv) sits underneath. Background-colour gating happens
  // in VideoStage; PlayerContainer is chrome only.
  return (
    <div
      ref={containerRef}
      className="absolute inset-0 z-[42]"
      onMouseMove={resetHideTimer}
      style={{ cursor: controlsVisible ? 'default' : 'none' }}
    >
      {/* Click-to-pause / double-click-fullscreen overlay */}
      <div
        className="absolute inset-0 z-[43]"
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

      {/* Reconnect overlay — after an unexpected stream drop. */}
      {status === 'reconnecting' && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div className="flex flex-col items-center gap-3 rounded-xl bg-surface-950/70 px-6 py-5 backdrop-blur-sm">
            <svg className="h-10 w-10 animate-spin text-amber-400" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
            <div className="text-center">
              <div className="text-sm font-medium text-amber-200">
                Reconnecting…
              </div>
              {reconnectAttempt && reconnectMaxAttempts ? (
                <div className="mt-0.5 text-xs text-surface-400">
                  Attempt {reconnectAttempt} of {reconnectMaxAttempts}
                </div>
              ) : null}
            </div>
          </div>
        </div>
      )}

      {/* Error display — TheaterControls don't render in this state (isActive
          gates on playing/paused/buffering/reconnecting), so without an
          explicit Back button here the only escape is the Escape key. That's
          a discoverability hole; show real buttons. */}
      {status === 'error' && (
        <div className="absolute inset-0 z-[44] flex items-center justify-center bg-black/70">
          <div className="flex max-w-md flex-col items-center gap-4 text-center">
            <svg className="h-12 w-12 text-red-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z" />
            </svg>
            <span className="text-sm text-red-300">
              {usePlayerStore.getState().error || 'Playback error'}
            </span>
            <div className="flex items-center gap-3">
              <button
                onClick={() => usePlayerStore.getState().stop()}
                className="rounded-lg bg-white/10 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-white/20"
              >
                Back to menu
              </button>
            </div>
            <span className="text-xs text-surface-500">Or press Escape</span>
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
