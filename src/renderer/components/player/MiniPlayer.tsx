import { useEffect, useRef } from 'react';
import { usePlayerStore } from '../../stores/player-store';

/**
 * Pixels reserved at the top of the mini card for the title + Close button
 * row. mpv's child window paints over Chromium chrome on Windows, so the
 * pushed bounds inset by this much keeps the top bar visible above the video.
 */
const MPV_TOP_INSET_PX = 32;

/**
 * Docked mini-player chrome — appears in the bottom-right corner whenever
 * mode === 'mini'. For the html5 backend, VideoStage paints the actual video
 * underneath this card. For the mpv backend, the renderer measures this card
 * and pushes its DIP-space rect to the main process via setVideoBounds, so
 * mpv's embedded video child window resizes/moves over the card to match.
 *
 * Click anywhere → expand to theater. The Close (X) button is the only path
 * to fully stop playback from mini.
 */
export function MiniPlayer() {
  const expand = usePlayerStore((s) => s.expand);
  const stop = usePlayerStore((s) => s.stop);
  const pause = usePlayerStore((s) => s.pause);
  const resume = usePlayerStore((s) => s.resume);
  const toggleMute = usePlayerStore((s) => s.toggleMute);
  const title = usePlayerStore((s) => s.currentTitle);
  const status = usePlayerStore((s) => s.status);
  const muted = usePlayerStore((s) => s.muted);
  const volume = usePlayerStore((s) => s.volume);
  const backend = usePlayerStore((s) => s.backend);
  const cardRef = useRef<HTMLDivElement>(null);

  // mpv backend: measure the card and push its bounds to main before flipping
  // the presentation to 'mini' — order matters because setPresentation('mini')
  // calls showVideoWindow, which reads customBounds. Re-push on any resize so
  // a Sidebar collapse / window resize keeps the mpv surface aligned.
  //
  // Z-order: the mpv child window paints on top of the Chromium-rendered
  // chrome, so if we pushed the full card rect the top bar (title + Close
  // button) would disappear behind the video. Inset the pushed rect by the
  // top-bar height so chrome stays visible and clickable.
  useEffect(() => {
    if (backend !== 'mpv' || !window.api?.player?.setVideoBounds) return;
    const el = cardRef.current;
    if (!el) return;

    const pushBounds = () => {
      const rect = el.getBoundingClientRect();
      // Skip zero-sized measurements (briefly possible during mount / unmount
      // transitions); the next ResizeObserver tick will deliver real bounds.
      if (rect.width < 2 || rect.height < 2) return;
      const inset = MPV_TOP_INSET_PX;
      const height = rect.height - inset;
      if (height < 2) return;
      window.api.player.setVideoBounds({
        x: rect.left,
        y: rect.top + inset,
        width: rect.width,
        height,
      }).catch(() => {});
    };

    pushBounds();
    // Bring up the mpv surface at our just-pushed bounds. setPresentation is
    // idempotent on the main side, so re-entering mini from a previous mini
    // session is a no-op.
    window.api.player.setPresentation('mini').catch(() => {});

    const ro = new ResizeObserver(pushBounds);
    ro.observe(el);
    // Card uses `fixed bottom-4 right-4`, so its renderer-relative position
    // shifts when the window itself resizes — but its size doesn't change,
    // so ResizeObserver misses it. The main-side parent-resize listener
    // re-syncs with stale customBounds (still pointing at the pre-resize
    // position) which leaves the mpv surface offset from the card. Push
    // fresh bounds on window resize too.
    window.addEventListener('resize', pushBounds);
    return () => {
      ro.disconnect();
      window.removeEventListener('resize', pushBounds);
      // Clear the custom bounds on unmount so the next presentation (theater
      // or idle) starts from a clean full-content state.
      window.api.player.setVideoBounds(null).catch(() => {});
    };
  }, [backend]);

  const isBuffering = status === 'buffering';
  const isReconnecting = status === 'reconnecting';
  const isError = status === 'error';

  return (
    <div
      ref={cardRef}
      className="group fixed bottom-4 right-4 z-50 aspect-video w-96 cursor-pointer overflow-hidden rounded-lg ring-1 ring-accent/30 transition-all hover:ring-accent/60"
      onClick={expand}
      role="button"
      aria-label="Expand player to theater mode"
    >
      {/* Card is intentionally transparent. For html5, VideoStage at z-40
          paints the <video> through it. For mpv, the embedded child window
          (a separate Win32 HWND) paints above the Chromium tree. The chrome
          (title bar + spinner + hover hint) layers on top via z-indexes. */}

      {/* Centered buffering spinner — shown during initial mpv spawn /
          channel switches / reconnect so the user gets a "something is
          happening" cue instead of staring at an empty card. The dim
          backdrop only covers the card body (skips the top-bar area) so
          the close button + title stay readable. */}
      {(isBuffering || isReconnecting) && (
        <div className="pointer-events-none absolute inset-0 z-[5] flex items-center justify-center bg-surface-950/40 backdrop-blur-sm">
          <div className="flex flex-col items-center gap-2 rounded-xl bg-surface-900/85 px-5 py-3 shadow-lg ring-1 ring-white/5">
            <svg
              className={`h-7 w-7 animate-spin ${isReconnecting ? 'text-amber-400' : 'text-accent'}`}
              fill="none"
              viewBox="0 0 24 24"
            >
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
            {isReconnecting && (
              <span className="text-[10px] font-medium uppercase tracking-widest-plus text-amber-200">
                Reconnecting
              </span>
            )}
          </div>
        </div>
      )}

      {/* Top bar — title + playback controls + close. Playback controls live
          here so the user doesn't have to expand to theater just to pause or
          mute a stream. They only render while a stream is actually active
          (playing/paused/buffering) so the bar stays clean on errors. */}
      <div className="pointer-events-none absolute inset-x-0 top-0 z-10 flex items-start gap-2 bg-gradient-to-b from-black/80 via-black/40 to-transparent px-3 py-2">
        <div className="min-w-0 flex-1">
          <p className="truncate text-xs font-semibold text-white drop-shadow">
            {title || 'Playing'}
          </p>
          {isReconnecting && (
            <p className="truncate text-[10px] font-medium text-amber-300">Reconnecting…</p>
          )}
          {isError && (
            <p className="truncate text-[10px] font-medium text-red-300">Playback error</p>
          )}
        </div>

        {/* Play / Pause */}
        {(status === 'playing' || status === 'paused' || status === 'buffering') && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              if (status === 'paused') {
                resume();
              } else {
                pause();
              }
            }}
            className="pointer-events-auto flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full bg-black/40 text-white/85 backdrop-blur-sm transition-colors hover:bg-white/20 hover:text-white"
            title={status === 'paused' ? 'Play (Space)' : 'Pause (Space)'}
            aria-label={status === 'paused' ? 'Play' : 'Pause'}
          >
            {status === 'paused' ? <MiniPlayIcon /> : <MiniPauseIcon />}
          </button>
        )}

        {/* Mute toggle */}
        {(status === 'playing' || status === 'paused' || status === 'buffering') && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              toggleMute();
            }}
            className="pointer-events-auto flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full bg-black/40 text-white/85 backdrop-blur-sm transition-colors hover:bg-white/20 hover:text-white"
            title={muted || volume === 0 ? 'Unmute (M)' : 'Mute (M)'}
            aria-label={muted || volume === 0 ? 'Unmute' : 'Mute'}
          >
            {muted || volume === 0 ? <MiniMutedIcon /> : <MiniVolumeIcon />}
          </button>
        )}

        {/* Close — last, separated visually so the destructive action is the
            farthest from the rest. */}
        <button
          onClick={(e) => {
            e.stopPropagation();
            stop();
          }}
          className="pointer-events-auto flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full bg-black/40 text-white/80 backdrop-blur-sm transition-colors hover:bg-red-500/70 hover:text-white"
          title="Close player"
          aria-label="Close player"
        >
          <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* Expand hint — only visible on hover for low visual noise */}
      <div className="pointer-events-none absolute inset-x-0 bottom-0 z-10 flex items-center justify-center bg-gradient-to-t from-black/70 to-transparent px-3 py-2 opacity-0 transition-opacity group-hover:opacity-100">
        <span className="flex items-center gap-1.5 text-[10px] font-display uppercase tracking-widest-plus text-white/90">
          <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 3.75v4.5m0-4.5h4.5m-4.5 0L9 9M3.75 20.25v-4.5m0 4.5h4.5m-4.5 0L9 15M20.25 3.75h-4.5m4.5 0v4.5m0-4.5L15 9m5.25 11.25h-4.5m4.5 0v-4.5m0 4.5L15 15" />
          </svg>
          Click to expand
        </span>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Compact icons for the mini-player top bar. Sized to match the existing
// 6×6 button slots (3.5×3.5 svg).
// ---------------------------------------------------------------------------

function MiniPlayIcon() {
  return (
    <svg className="ml-0.5 h-3.5 w-3.5" fill="currentColor" viewBox="0 0 24 24">
      <path d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.348a1.125 1.125 0 010 1.971l-11.54 6.347a1.125 1.125 0 01-1.667-.985V5.653z" />
    </svg>
  );
}

function MiniPauseIcon() {
  return (
    <svg className="h-3.5 w-3.5" fill="currentColor" viewBox="0 0 24 24">
      <path
        fillRule="evenodd"
        d="M6.75 5.25a.75.75 0 01.75-.75H9a.75.75 0 01.75.75v13.5a.75.75 0 01-.75.75H7.5a.75.75 0 01-.75-.75V5.25zm7.5 0A.75.75 0 0115 4.5h1.5a.75.75 0 01.75.75v13.5a.75.75 0 01-.75.75H15a.75.75 0 01-.75-.75V5.25z"
        clipRule="evenodd"
      />
    </svg>
  );
}

function MiniVolumeIcon() {
  return (
    <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.75}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M19.114 5.636a9 9 0 010 12.728M16.463 8.288a5.25 5.25 0 010 7.424M6.75 8.25l4.72-4.72a.75.75 0 011.28.53v15.88a.75.75 0 01-1.28.53l-4.72-4.72H4.51c-.88 0-1.704-.507-1.938-1.354A9.01 9.01 0 012.25 12c0-.83.112-1.633.322-2.396C2.806 8.756 3.63 8.25 4.51 8.25H6.75z"
      />
    </svg>
  );
}

function MiniMutedIcon() {
  return (
    <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.75}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M17.25 9.75L19.5 12m0 0l2.25 2.25M19.5 12l2.25-2.25M19.5 12l-2.25 2.25m-10.5-6l4.72-4.72a.75.75 0 011.28.531v15.88a.75.75 0 01-1.28.53l-4.72-4.72H4.51c-.88 0-1.704-.507-1.938-1.354A9.01 9.01 0 012.25 12c0-.83.112-1.633.322-2.396C2.806 8.756 3.63 8.25 4.51 8.25H6.75z"
      />
    </svg>
  );
}
