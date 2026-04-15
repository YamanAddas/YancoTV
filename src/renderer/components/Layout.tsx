import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { PlayerOverlay } from './PlayerOverlay';
import { usePlayerShortcuts } from '../hooks/use-player-shortcuts';

export function Layout() {
  usePlayerShortcuts();

  return (
    <div className="flex h-screen w-screen">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <main className="min-w-0 flex-1 overflow-y-auto p-6">
          <Outlet />
        </main>
        <PlayerOverlay />
      </div>
    </div>
  );
}
