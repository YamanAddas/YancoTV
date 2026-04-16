import { useState, useEffect, useCallback } from 'react';

/**
 * Custom titlebar for frameless window.
 * Provides drag area + window control buttons (minimize, maximize/restore, close).
 */
export function Titlebar() {
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    if (!window.api) return;
    // Check initial state
    window.api.window.isMaximized().then(setMaximized);
  }, []);

  const handleMinimize = useCallback(() => {
    window.api?.window.minimize();
  }, []);

  const handleMaximize = useCallback(async () => {
    await window.api?.window.maximize();
    const isMax = await window.api?.window.isMaximized();
    setMaximized(isMax ?? false);
  }, []);

  const handleClose = useCallback(() => {
    window.api?.window.close();
  }, []);

  return (
    <div className="flex h-8 select-none items-center bg-surface-950/80">
      {/* Drag region — fills most of the titlebar */}
      <div
        className="flex flex-1 items-center px-3 text-xs font-medium text-surface-500"
        style={{ WebkitAppRegion: 'drag' } as React.CSSProperties}
      >
        YancoTV
      </div>

      {/* Window control buttons — NOT draggable */}
      <div
        className="flex h-full"
        style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}
      >
        <button
          onClick={handleMinimize}
          className="flex h-full w-11 items-center justify-center text-surface-400 transition-colors hover:bg-surface-800 hover:text-surface-200"
          title="Minimize"
        >
          <svg className="h-3 w-3" viewBox="0 0 12 12" fill="currentColor">
            <rect x="1" y="5.5" width="10" height="1" />
          </svg>
        </button>

        <button
          onClick={handleMaximize}
          className="flex h-full w-11 items-center justify-center text-surface-400 transition-colors hover:bg-surface-800 hover:text-surface-200"
          title={maximized ? 'Restore' : 'Maximize'}
        >
          {maximized ? (
            <svg className="h-3 w-3" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1">
              <rect x="3" y="0.5" width="8.5" height="8.5" rx="0.5" />
              <rect x="0.5" y="3" width="8.5" height="8.5" rx="0.5" />
            </svg>
          ) : (
            <svg className="h-3 w-3" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1">
              <rect x="0.5" y="0.5" width="11" height="11" rx="0.5" />
            </svg>
          )}
        </button>

        <button
          onClick={handleClose}
          className="flex h-full w-11 items-center justify-center text-surface-400 transition-colors hover:bg-red-600 hover:text-white"
          title="Close"
        >
          <svg className="h-3 w-3" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M1 1l10 10M11 1L1 11" />
          </svg>
        </button>
      </div>
    </div>
  );
}
