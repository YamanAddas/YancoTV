export function MoviesPage() {
  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-surface-100">Movies</h2>
      <div className="flex items-center justify-center rounded-xl border border-dashed border-surface-700 bg-surface-900/50 py-24">
        <div className="text-center">
          <svg
            className="mx-auto h-12 w-12 text-surface-600"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1}
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M7 4v16M17 4v16M3 8h4m10 0h4M3 12h18M3 16h4m10 0h4M4 20h16a1 1 0 001-1V5a1 1 0 00-1-1H4a1 1 0 00-1 1v14a1 1 0 001 1z"
            />
          </svg>
          <p className="mt-3 text-sm text-surface-500">
            No movies available. Add an IPTV source in Settings.
          </p>
        </div>
      </div>
    </div>
  );
}
