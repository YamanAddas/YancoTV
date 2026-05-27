import { useEffect } from 'react';
import { usePlayerStore } from '../../stores/player-store';

/**
 * Docked mini-player chrome — appears in the bottom-right corner whenever
 * mode === 'mini'. Positioned to overlap the matching mini cell rendered by
 * VideoStage (for html5) so the video surface and chrome line up visually.
 *
 * Click anywhere on the card → expand to theater. The Close (X) button is the
 * only path to fully stop playback from mini.
 *
 * For mpv backend in this commit, the embedded video window stays hidden in
 * mini mode (the main process drops it on PLAYER_SET_PRESENTATION='mini') so
 * the user gets audio + the docked chrome without a misplaced full-window
 * surface obscuring the menu. Phase 2 will reposition mpv's window over this
 * card so the video itself shrinks to fit.
 */
export function MiniPlayer() {
  const expand = usePlayerStore((s) => s.expand);
  const stop = usePlayerStore((s) => s.stop);
  const title = usePlayerStore((s) => s.currentTitle);
  const status = usePlayerStore((s) => s.status);
  const backend = usePlayerStore((s) => s.backend);

  // mpv backend: in mini mode the dedicated video child window must hide so
  // the sidebar and page content remain interactive. Restore presentation
  // when the user expands (theater) or stops (idle).
  useEffect(() => {
    if (backend !== 'mpv' || !window.api?.player?.setPresentation) return;
    window.api.player.setPresentation('mini').catch(() => {});
    return () => {
      // Don't reach into the store here — the next mount (MiniPlayer for
      // another stream, or PlayerContainer entering theater) will set the
      // appropriate presentation. We only need to clean up our own claim.
    };
  }, [backend]);

  const isReconnecting = status === 'reconnecting';
  const isError = status === 'error';
  const showAudioLabel = backend === 'mpv';

  return (
    <div
      className="group fixed bottom-4 right-4 z-50 aspect-video w-96 cursor-pointer overflow-hidden rounded-lg ring-1 ring-accent/30 transition-all hover:ring-accent/60"
      onClick={expand}
      role="button"
      aria-label="Expand player to theater mode"
    >
      {/* mpv backend: visible card surface (the video itself isn't drawn here
          in this phase). html5 backend: transparent so VideoStage shows
          through underneath. */}
      {showAudioLabel && (
        <div className="absolute inset-0 flex items-center justify-center bg-surface-900">
          <div className="flex flex-col items-center gap-2 text-surface-500">
            <svg className="h-10 w-10" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.25}>
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M9 9l10.5-3m0 6.553v3.75a2.25 2.25 0 01-1.632 2.163l-1.32.377a1.803 1.803 0 11-.99-3.467l2.31-.66a2.25 2.25 0 001.632-2.163zm0 0V2.25L9 5.25v10.303m0 0v3.75a2.25 2.25 0 01-1.632 2.163l-1.32.377a1.803 1.803 0 01-.99-3.467l2.31-.66A2.25 2.25 0 009 15.553z"
              />
            </svg>
            <span className="text-[10px] font-display uppercase tracking-widest-plus">
              Audio playing
            </span>
          </div>
        </div>
      )}

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
