import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

interface ContentCounts {
  live: number;
  movie: number;
  series: number;
}

export function HomePage() {
  const [counts, setCounts] = useState<ContentCounts>({ live: 0, movie: 0, series: 0 });
  const navigate = useNavigate();

  useEffect(() => {
    if (!window.api) return;
    window.api.db.status().then((status: { ok: boolean; counts?: ContentCounts }) => {
      if (status?.ok && status.counts) {
        setCounts(status.counts as ContentCounts);
      }
    });
  }, []);

  const total = counts.live + counts.movie + counts.series;

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-surface-100">Home</h2>

      <div className="grid grid-cols-3 gap-4">
        <StatusCard
          title="Live TV"
          count={counts.live}
          icon="tv"
          onClick={() => navigate('/live')}
        />
        <StatusCard
          title="Movies"
          count={counts.movie}
          icon="film"
          onClick={() => navigate('/movies')}
        />
        <StatusCard
          title="Series"
          count={counts.series}
          icon="layers"
          onClick={() => navigate('/series')}
        />
      </div>

      {total === 0 && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-surface-700 bg-surface-900/50 py-16">
          <svg
            className="h-12 w-12 text-surface-600"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1}
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M12 6v6m0 0v6m0-6h6m-6 0H6"
            />
          </svg>
          <h3 className="mt-4 text-sm font-medium text-surface-300">No content yet</h3>
          <p className="mt-1 text-sm text-surface-500">
            Add an IPTV source in Settings to get started.
          </p>
          <button
            onClick={() => navigate('/settings')}
            className="mt-4 rounded-lg bg-accent/10 px-4 py-2 text-sm font-medium text-accent transition-colors hover:bg-accent/20"
          >
            Go to Settings
          </button>
        </div>
      )}

      {total > 0 && (
        <>
          <section>
            <h3 className="mb-3 text-lg font-semibold text-surface-200">Recently Watched</h3>
            <div className="flex items-center justify-center rounded-xl border border-dashed border-surface-700 bg-surface-900/50 py-12">
              <p className="text-sm text-surface-500">
                Nothing watched yet. Start browsing your content!
              </p>
            </div>
          </section>

          <section>
            <h3 className="mb-3 text-lg font-semibold text-surface-200">Favorites</h3>
            <div className="flex items-center justify-center rounded-xl border border-dashed border-surface-700 bg-surface-900/50 py-12">
              <p className="text-sm text-surface-500">
                No favorites yet. Browse content and mark your favorites.
              </p>
            </div>
          </section>
        </>
      )}
    </div>
  );
}

function StatusCard({
  title,
  count,
  icon,
  onClick,
}: {
  title: string;
  count: number;
  icon: string;
  onClick: () => void;
}) {
  const iconPaths: Record<string, string> = {
    tv: 'M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
    film: 'M7 4v16M17 4v16M3 8h4m10 0h4M3 12h18M3 16h4m10 0h4M4 20h16a1 1 0 001-1V5a1 1 0 00-1-1H4a1 1 0 00-1 1v14a1 1 0 001 1z',
    layers:
      'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10',
  };

  return (
    <button
      onClick={onClick}
      className="rounded-xl border border-surface-800 bg-surface-900 p-5 text-left transition-all hover:border-accent/30 hover:shadow-lg hover:shadow-accent/5"
    >
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
          <p className="text-2xl font-bold text-surface-100">{count.toLocaleString()}</p>
          <p className="text-sm text-surface-400">{title}</p>
        </div>
      </div>
    </button>
  );
}
