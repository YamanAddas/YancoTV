export function LiveTvPage() {
  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-surface-100">Live TV</h2>
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
              d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
            />
          </svg>
          <p className="mt-3 text-sm text-surface-500">
            No live channels available. Add an IPTV source in Settings.
          </p>
        </div>
      </div>
    </div>
  );
}
