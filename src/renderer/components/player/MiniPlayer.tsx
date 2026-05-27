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
  const title = usePlayerStore((s) => s.currentTitle);
  const status = usePlayerStore((s) => s.status);
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
      {/* html5 backend: VideoStage paints the <video> through the transparent
          card. mpv backend: the embedded child window paints on top (no React
          surface needed); the card surface stays empty as a fallback for the
          brief moment before main repositions mpv. */}

      {/* Top bar — title + close */}
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
