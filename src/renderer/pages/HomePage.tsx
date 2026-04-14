export function HomePage() {
  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-surface-100">Home</h2>

      <div className="grid grid-cols-3 gap-4">
        <StatusCard title="Live TV" count={0} icon="tv" />
        <StatusCard title="Movies" count={0} icon="film" />
        <StatusCard title="Series" count={0} icon="layers" />
      </div>

      <section>
        <h3 className="mb-3 text-lg font-semibold text-surface-200">Recently Watched</h3>
        <EmptyState message="Nothing watched yet. Add a source to get started." />
      </section>

      <section>
        <h3 className="mb-3 text-lg font-semibold text-surface-200">Favorites</h3>
        <EmptyState message="No favorites yet. Browse content and mark your favorites." />
      </section>
    </div>
  );
}

function StatusCard({ title, count, icon }: { title: string; count: number; icon: string }) {
  const iconPaths: Record<string, string> = {
    tv: 'M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
    film: 'M7 4v16M17 4v16M3 8h4m10 0h4M3 12h18M3 16h4m10 0h4M4 20h16a1 1 0 001-1V5a1 1 0 00-1-1H4a1 1 0 00-1 1v14a1 1 0 001 1z',
    layers: 'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10',
  };

  return (
    <div className="rounded-xl border border-surface-800 bg-surface-900 p-5">
      <div className="flex items-center gap-3">
        <svg
          className="h-8 w-8 text-accent"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={1.5}
        >
          <path strokeLinecap="round" strokeLinejoin="round" d={iconPaths[icon]} />
        </svg>
        <div>
          <p className="text-2xl font-bold text-surface-100">{count}</p>
          <p className="text-sm text-surface-400">{title}</p>
        </div>
      </div>
    </div>
  );
}

function EmptyState({ message }: { message: string }) {
  return (
    <div className="flex items-center justify-center rounded-xl border border-dashed border-surface-700 bg-surface-900/50 py-12">
      <p className="text-sm text-surface-500">{message}</p>
    </div>
  );
}
