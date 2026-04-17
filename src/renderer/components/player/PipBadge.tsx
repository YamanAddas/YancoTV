import { usePlayerStore } from '../../stores/player-store';

/**
 * Small floating badge shown in the main window while the video is minimized
 * to picture-in-picture. The PIP video window is always-on-top and not
 * clickable, so this gives the user a way to restore theater mode.
 */
export function PipBadge() {
  const mode = usePlayerStore((s) => s.mode);
  const title = usePlayerStore((s) => s.currentTitle);
  const exitPip = usePlayerStore((s) => s.exitPip);
  const stop = usePlayerStore((s) => s.stop);

  if (mode !== 'pip') return null;

  return (
    <div className="fixed bottom-6 left-6 z-[80] flex items-center gap-2 rounded-lg border border-surface-700 bg-surface-900/95 px-3 py-2 shadow-xl backdrop-blur">
      <span className="text-xs text-surface-300">
        Playing in PIP{title ? ` — ${title}` : ''}
      </span>
      <button
        type="button"
        onClick={() => void exitPip()}
        className="rounded bg-brand-500 px-2 py-1 text-xs font-medium text-white hover:bg-brand-400"
      >
        Restore
      </button>
      <button
        type="button"
        onClick={stop}
        className="rounded bg-surface-700 px-2 py-1 text-xs text-surface-200 hover:bg-surface-600"
      >
        Stop
      </button>
    </div>
  );
}
