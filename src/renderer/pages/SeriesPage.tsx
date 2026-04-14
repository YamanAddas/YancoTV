export function SeriesPage() {
  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-surface-100">Series</h2>
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
              d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"
            />
          </svg>
          <p className="mt-3 text-sm text-surface-500">
            No series available. Add an IPTV source in Settings.
          </p>
        </div>
      </div>
    </div>
  );
}
