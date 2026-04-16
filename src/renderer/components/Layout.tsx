import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { PlayerContainer } from './player/PlayerContainer';
import { usePlayerShortcuts } from '../hooks/use-player-shortcuts';
import { usePlayerStore } from '../stores/player-store';

export function Layout() {
  usePlayerShortcuts();

  const mode = usePlayerStore((s) => s.mode);
  const isTheater = mode === 'theater';

  return (
    <div className="relative flex h-screen w-screen overflow-hidden bg-space">
      {/* Subtle star-like dots via CSS — static for performance */}
      {!isTheater && (
        <div
          className="pointer-events-none absolute inset-0 opacity-30"
          style={{
            backgroundImage: `
              radial-gradient(1px 1px at 10% 20%, rgba(0, 255, 170, 0.3), transparent),
              radial-gradient(1px 1px at 30% 70%, rgba(0, 204, 255, 0.2), transparent),
              radial-gradient(1px 1px at 50% 10%, rgba(255, 255, 255, 0.2), transparent),
              radial-gradient(1px 1px at 70% 50%, rgba(0, 255, 170, 0.2), transparent),
              radial-gradient(1px 1px at 90% 80%, rgba(0, 204, 255, 0.3), transparent),
              radial-gradient(1px 1px at 20% 90%, rgba(255, 255, 255, 0.15), transparent),
              radial-gradient(1px 1px at 60% 40%, rgba(0, 255, 200, 0.2), transparent),
              radial-gradient(1px 1px at 80% 15%, rgba(255, 255, 255, 0.2), transparent),
              radial-gradient(1px 1px at 40% 55%, rgba(0, 255, 170, 0.15), transparent),
              radial-gradient(1px 1px at 15% 45%, rgba(0, 204, 255, 0.2), transparent),
              radial-gradient(1px 1px at 85% 65%, rgba(255, 255, 255, 0.15), transparent),
              radial-gradient(1px 1px at 55% 85%, rgba(0, 255, 170, 0.2), transparent)
            `,
          }}
        />
      )}

      {/* Sidebar — hidden in theater mode */}
      {!isTheater && <Sidebar />}

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Page content — hidden in theater mode */}
        {!isTheater && (
          <main className="min-w-0 flex-1 overflow-y-auto p-6">
            <Outlet />
          </main>
        )}
      </div>

      {/* Player — overlays the entire window in theater mode */}
      <PlayerContainer />
    </div>
  );
}
