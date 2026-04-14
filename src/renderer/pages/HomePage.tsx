import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { usePlayerStore } from '../stores/player-store';
import { useFavoritesStore } from '../stores/favorites-store';
import type { FavoriteEntry } from '../../main/services/favorites-store';
import type { HistoryEntry } from '../../main/services/history-store';

interface ContentCounts {
  live: number;
  movie: number;
  series: number;
}

export function HomePage() {
  const [counts, setCounts] = useState<ContentCounts>({ live: 0, movie: 0, series: 0 });
  const [recentlyWatched, setRecentlyWatched] = useState<HistoryEntry[]>([]);
  const [favorites, setFavorites] = useState<FavoriteEntry[]>([]);
  const navigate = useNavigate();
  const play = usePlayerStore((s) => s.play);
  const favoriteIds = useFavoritesStore((s) => s.favoriteIds);

  useEffect(() => {
    if (!window.api) return;
    window.api.db.status().then((status: { ok: boolean; counts?: ContentCounts }) => {
      if (status?.ok && status.counts) {
        setCounts(status.counts as ContentCounts);
      }
    });
    window.api.history.getRecent(10).then((data: HistoryEntry[]) => setRecentlyWatched(data));
    window.api.favorites.getAll().then((data: FavoriteEntry[]) => setFavorites(data.slice(0, 10)));
  }, []);

  // Refresh favorites when favoriteIds change (user toggled from another page)
  useEffect(() => {
    if (!window.api) return;
    window.api.favorites.getAll().then((data: FavoriteEntry[]) => setFavorites(data.slice(0, 10)));
  }, [favoriteIds]);

  const total = counts.live + counts.movie + counts.series;

  const handlePlayHistory = useCallback(
    (entry: HistoryEntry) => {
      play(
        entry.content.streamUrl,
        entry.content.cleanTitle || entry.content.title,
        entry.contentId,
        entry.episodeId,
      );
    },
    [play],
  );

  const handlePlayFavorite = useCallback(
    (fav: FavoriteEntry) => {
      play(
        fav.content.streamUrl,
        fav.content.cleanTitle || fav.content.title,
        fav.content.id,
      );
    },
    [play],
  );

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-surface-100">Home</h2>

      <div className="grid grid-cols-3 gap-4">
        <StatusCard title="Live TV" count={counts.live} icon="tv" onClick={() => navigate('/live')} />
        <StatusCard title="Movies" count={counts.movie} icon="film" onClick={() => navigate('/movies')} />
        <StatusCard title="Series" count={counts.series} icon="layers" onClick={() => navigate('/series')} />
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
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
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
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-lg font-semibold text-surface-200">Recently Watched</h3>
            </div>
            {recentlyWatched.length === 0 ? (
              <div className="flex items-center justify-center rounded-xl border border-dashed border-surface-700 bg-surface-900/50 py-10">
                <p className="text-sm text-surface-500">Nothing watched yet. Start browsing!</p>
              </div>
            ) : (
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
                {recentlyWatched.map((entry) => (
                  <HistoryCard key={entry.id} entry={entry} onPlay={handlePlayHistory} />
                ))}
              </div>
            )}
          </section>

          <section>
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-lg font-semibold text-surface-200">Favorites</h3>
              {favorites.length > 0 && (
                <button
                  onClick={() => navigate('/favorites')}
                  className="text-sm text-accent hover:underline"
                >
                  See all
                </button>
              )}
            </div>
            {favorites.length === 0 ? (
              <div className="flex items-center justify-center rounded-xl border border-dashed border-surface-700 bg-surface-900/50 py-10">
                <p className="text-sm text-surface-500">
                  No favorites yet. Tap the heart on any content item.
                </p>
              </div>
            ) : (
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
                {favorites.map((fav) => (
                  <FavoriteCard key={fav.favoriteId} fav={fav} onPlay={handlePlayFavorite} />
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}

function HistoryCard({
  entry,
  onPlay,
}: {
  entry: HistoryEntry;
  onPlay: (entry: HistoryEntry) => void;
}) {
  const { content, positionSeconds, durationSeconds } = entry;
  const progress =
    durationSeconds && durationSeconds > 0
      ? Math.min(100, (positionSeconds / durationSeconds) * 100)
      : null;

  return (
    <button
      onClick={() => onPlay(entry)}
      className="group flex flex-col overflow-hidden rounded-lg border border-surface-800 bg-surface-900 text-left transition-all hover:border-accent/50 hover:shadow-lg hover:shadow-accent/5"
    >
      <div className="relative aspect-video w-full overflow-hidden bg-surface-800">
        {content.logoUrl ? (
          <img
            src={content.logoUrl}
            alt={content.title}
            className="h-full w-full object-contain p-2"
            loading="lazy"
            onError={(e) => {
              (e.target as HTMLImageElement).style.display = 'none';
            }}
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            <span className="text-xl font-bold text-surface-600">
              {(content.cleanTitle || content.title).charAt(0).toUpperCase()}
            </span>
          </div>
        )}
        {progress !== null && (
          <div className="absolute bottom-0 left-0 right-0 h-1 bg-surface-700">
            <div className="h-full bg-accent" style={{ width: `${progress}%` }} />
          </div>
        )}
      </div>
      <div className="p-2.5">
        <p className="line-clamp-2 text-sm font-medium text-surface-200 group-hover:text-surface-100">
          {content.cleanTitle || content.title}
        </p>
        {positionSeconds > 0 && (
          <p className="mt-0.5 text-xs text-surface-500">{formatTime(positionSeconds)}</p>
        )}
      </div>
    </button>
  );
}

function FavoriteCard({
  fav,
  onPlay,
}: {
  fav: FavoriteEntry;
  onPlay: (fav: FavoriteEntry) => void;
}) {
  const { content } = fav;
  return (
    <button
      onClick={() => onPlay(fav)}
      className="group flex flex-col overflow-hidden rounded-lg border border-surface-800 bg-surface-900 text-left transition-all hover:border-accent/50 hover:shadow-lg hover:shadow-accent/5"
    >
      <div className="aspect-video w-full overflow-hidden bg-surface-800">
        {content.logoUrl ? (
          <img
            src={content.logoUrl}
            alt={content.title}
            className="h-full w-full object-contain p-2"
            loading="lazy"
            onError={(e) => {
              (e.target as HTMLImageElement).style.display = 'none';
            }}
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            <span className="text-xl font-bold text-surface-600">
              {(content.cleanTitle || content.title).charAt(0).toUpperCase()}
            </span>
          </div>
        )}
      </div>
      <div className="p-2.5">
        <p className="line-clamp-2 text-sm font-medium text-surface-200 group-hover:text-surface-100">
          {content.cleanTitle || content.title}
        </p>
        {content.groupName && (
          <p className="mt-0.5 truncate text-xs text-surface-500">{content.groupName}</p>
        )}
      </div>
    </button>
  );
}

function formatTime(seconds: number): string {
  const s = Math.floor(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  if (h > 0) return `${h}:${pad(m)}:${pad(sec)}`;
  return `${m}:${pad(sec)}`;
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
