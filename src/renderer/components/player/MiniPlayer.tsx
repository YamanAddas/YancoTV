import { useCallback, useEffect, useRef, useState, type CSSProperties, type MouseEvent as ReactMouseEvent } from 'react';
import { usePlayerStore } from '../../stores/player-store';

/**
 * Pixels reserved at the top of the mini card for the title + Close button
 * row. mpv's child window paints over Chromium chrome on Windows, so the
 * pushed bounds inset by this much keeps the top bar visible above the video.
 */
const MPV_TOP_INSET_PX = 32;

/**
 * Minimum pixel distance the user has to drag before we treat a mouse
 * gesture as a drag rather than a click. Anything less than this fires the
 * onClick handler (expand to theater) on mouse-up. Tuned so a steady-hand
 * click never accidentally drags but a casual drag never accidentally
 * expands.
 */
const DRAG_THRESHOLD_PX = 5;

/** localStorage key for the persisted mini-player position. */
const POSITION_STORAGE_KEY = 'yancotv.mini-player-position';

type Position = { x: number; y: number };

/**
 * Read the user's last-saved mini-player position from localStorage. Returns
 * null on first run or if the stored value is malformed. Position is in
 * viewport pixels (top-left origin).
 */
function loadStoredPosition(): Position | null {
  try {
    const raw = localStorage.getItem(POSITION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as unknown;
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      typeof (parsed as Position).x === 'number' &&
      typeof (parsed as Position).y === 'number'
    ) {
      return parsed as Position;
    }
    return null;
  } catch {
    return null;
  }
}

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

  // User-positioned mini location. `null` = default bottom-right corner
  // (handled by Tailwind classes). When set, the card switches to absolute
  // top/left placement at viewport-pixel coordinates. Initialised from
  // localStorage so the position persists across app launches.
  const [position, setPosition] = useState<Position | null>(() => loadStoredPosition());
  // Whether the user is mid-drag right now. Drives cursor style and the
  // recently-dragged guard that suppresses the click-to-expand handler on
  // mouse-up after a real drag.
  const [isDragging, setIsDragging] = useState(false);
  // Captured at mouse-down for the duration of a drag. Null when no
  // gesture is in progress.
  const dragStartRef = useRef<{ cardX: number; cardY: number; mouseX: number; mouseY: number } | null>(null);
  // Set true on mouse-up of a real drag and cleared on the very next click
  // event so onClick={expand} doesn't fire after release.
  const justDraggedRef = useRef(false);

  // Compute + push the mpv video-stage bounds. Lifted out of the effect so
  // the drag-move handler can call it on every frame instead of waiting for
  // the next ResizeObserver tick (which doesn't fire on position-only
  // changes).
  const pushMpvBounds = useCallback(() => {
    if (backend !== 'mpv' || !window.api?.player?.setVideoBounds) return;
    const el = cardRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
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
  }, [backend]);

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

    pushMpvBounds();
    // Bring up the mpv surface at our just-pushed bounds. setPresentation is
    // idempotent on the main side, so re-entering mini from a previous mini
    // session is a no-op.
    window.api.player.setPresentation('mini').catch(() => {});

    const ro = new ResizeObserver(() => pushMpvBounds());
    ro.observe(el);
    // Card uses `fixed bottom-4 right-4` by default — its renderer-relative
    // position shifts when the window itself resizes (size doesn't change),
    // so ResizeObserver misses it. The main-side parent-resize listener
    // re-syncs with stale customBounds (still pointing at the pre-resize
    // position) which leaves the mpv surface offset from the card. Push
    // fresh bounds on window resize too.
    const onResize = () => pushMpvBounds();
    window.addEventListener('resize', onResize);
    return () => {
      ro.disconnect();
      window.removeEventListener('resize', onResize);
      // Clear the custom bounds on unmount so the next presentation (theater
      // or idle) starts from a clean full-content state.
      window.api.player.setVideoBounds(null).catch(() => {});
    };
  }, [backend, pushMpvBounds]);

  // When the card moves (user-driven drag), the mpv video-stage child window
  // needs to follow on every frame. Position changes don't trigger
  // ResizeObserver, so a separate effect watches `position` and re-pushes.
  useEffect(() => {
    pushMpvBounds();
  }, [position, pushMpvBounds]);

  // Drag handlers. Mouse-down captures the start state but doesn't start
  // dragging until the cursor moves past DRAG_THRESHOLD_PX — keeps casual
  // clicks from accidentally moving the card.
  const handleMouseDown = useCallback(
    (e: ReactMouseEvent<HTMLDivElement>) => {
      // Don't initiate a drag if the user is pressing on a button — those
      // have their own click handlers (close/play/pause/mute) and the
      // user's intent is to interact with the control, not move the card.
      if ((e.target as HTMLElement).closest('button')) return;
      // Right/middle clicks are not drag gestures.
      if (e.button !== 0) return;
      const el = cardRef.current;
      if (!el) return;
      const rect = el.getBoundingClientRect();
      dragStartRef.current = {
        cardX: rect.left,
        cardY: rect.top,
        mouseX: e.clientX,
        mouseY: e.clientY,
      };
    },
    [],
  );

  // Global mouse-move + mouse-up listeners for the duration of a gesture.
  // Mounted only while `dragStartRef.current` is potentially set so the
  // listeners aren't on the window forever, but in practice it's fine to
  // keep them attached: they early-return when no drag is in progress.
  useEffect(() => {
    const onMove = (e: MouseEvent) => {
      const start = dragStartRef.current;
      if (!start) return;
      const dx = e.clientX - start.mouseX;
      const dy = e.clientY - start.mouseY;
      if (!isDragging) {
        if (Math.abs(dx) + Math.abs(dy) < DRAG_THRESHOLD_PX) return;
        setIsDragging(true);
      }
      const el = cardRef.current;
      if (!el) return;
      const w = el.offsetWidth;
      const h = el.offsetHeight;
      const maxX = window.innerWidth - w;
      const maxY = window.innerHeight - h;
      const nextX = Math.max(0, Math.min(maxX, start.cardX + dx));
      const nextY = Math.max(0, Math.min(maxY, start.cardY + dy));
      setPosition({ x: nextX, y: nextY });
    };
    const onUp = () => {
      if (!dragStartRef.current) return;
      dragStartRef.current = null;
      if (isDragging) {
        setIsDragging(false);
        justDraggedRef.current = true;
        // Persist the final position. Wrapped because some sandbox modes
        // disable localStorage; persistence is best-effort.
        try {
          if (cardRef.current) {
            const rect = cardRef.current.getBoundingClientRect();
            localStorage.setItem(
              POSITION_STORAGE_KEY,
              JSON.stringify({ x: rect.left, y: rect.top }),
            );
          }
        } catch {
          // ignore
        }
      }
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [isDragging]);

  // The card's onClick fires after a click-finished gesture. If the gesture
  // was actually a drag, swallow the click so the user's drag-to-move
  // doesn't also trigger an expand-to-theater.
  const handleCardClick = useCallback(() => {
    if (justDraggedRef.current) {
      justDraggedRef.current = false;
      return;
    }
    expand();
  }, [expand]);

  // Reposition the card if its persisted position is off-screen (user
  // resized the window smaller after last drag, or moved to a smaller
  // monitor). Clamp back into the viewport once per mount + on window
  // resize.
  useEffect(() => {
    const clamp = () => {
      const el = cardRef.current;
      if (!el || !position) return;
      const w = el.offsetWidth;
      const h = el.offsetHeight;
      const maxX = window.innerWidth - w;
      const maxY = window.innerHeight - h;
      const clamped: Position = {
        x: Math.max(0, Math.min(maxX, position.x)),
        y: Math.max(0, Math.min(maxY, position.y)),
      };
      if (clamped.x !== position.x || clamped.y !== position.y) {
        setPosition(clamped);
      }
    };
    clamp();
    window.addEventListener('resize', clamp);
    return () => window.removeEventListener('resize', clamp);
  }, [position]);

  const isBuffering = status === 'buffering';
  const isReconnecting = status === 'reconnecting';
  const isError = status === 'error';

  const positionedClass = position
    ? 'group fixed z-50 aspect-video w-96 overflow-hidden rounded-lg ring-1 ring-accent/30 hover:ring-accent/60'
    : 'group fixed bottom-4 right-4 z-50 aspect-video w-96 overflow-hidden rounded-lg ring-1 ring-accent/30 hover:ring-accent/60';
  const positionedStyle: CSSProperties = position
    ? { left: position.x, top: position.y, cursor: isDragging ? 'grabbing' : 'grab' }
    : { cursor: isDragging ? 'grabbing' : 'grab' };

  return (
    <div
      ref={cardRef}
      className={positionedClass}
      style={positionedStyle}
      onMouseDown={handleMouseDown}
      onClick={handleCardClick}
      role="button"
      aria-label="Expand player to theater mode (drag to move)"
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

      {/* Hover hint — surfaces the two gestures the card supports so the
          drag affordance is discoverable. Hidden during the actual drag so
          the floating label doesn't follow the cursor distractingly. */}
      {!isDragging && (
        <div className="pointer-events-none absolute inset-x-0 bottom-0 z-10 flex items-center justify-center gap-3 bg-gradient-to-t from-black/70 to-transparent px-3 py-2 opacity-0 transition-opacity group-hover:opacity-100">
          <span className="flex items-center gap-1.5 text-[10px] font-display uppercase tracking-widest-plus text-white/90">
            <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              {/* arrows-out / expand */}
              <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 3.75v4.5m0-4.5h4.5m-4.5 0L9 9M3.75 20.25v-4.5m0 4.5h4.5m-4.5 0L9 15M20.25 3.75h-4.5m4.5 0v4.5m0-4.5L15 9m5.25 11.25h-4.5m4.5 0v-4.5m0 4.5L15 15" />
            </svg>
            Click to expand
          </span>
          <span className="text-white/40">·</span>
          <span className="flex items-center gap-1.5 text-[10px] font-display uppercase tracking-widest-plus text-white/90">
            <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              {/* arrows-pointing-out / move */}
              <path strokeLinecap="round" strokeLinejoin="round" d="M7.5 21L3 16.5m0 0L7.5 12M3 16.5h13.5m0-13.5L21 7.5m0 0L16.5 12M21 7.5H7.5" />
            </svg>
            Drag to move
          </span>
        </div>
      )}
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
