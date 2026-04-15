import { useEffect, useState, useMemo, useCallback } from 'react';
import { ContentGrid, type ContentCardData } from '../components/ContentGrid';
import { CategorySidebar } from '../components/CategorySidebar';
import { EmptyState } from '../components/EmptyState';
import { SourceSwitcher } from '../components/SourceSwitcher';
import { SortDropdown, type SortOption } from '../components/SortDropdown';
import { usePlayerStore } from '../stores/player-store';
import { useFavoritesStore } from '../stores/favorites-store';

interface EpisodeData {
  id: string;
  contentId: string;
  seasonNumber?: number;
  episodeNumber?: number;
  title?: string;
  streamUrl: string;
  duration?: number;
}

export function SeriesPage() {
  const [series, setSeries] = useState<ContentCardData[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [selectedCategory, setSelectedCategory] = useState<string | string[] | null>(null);
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<SortOption>('provider');
  const [selectedShow, setSelectedShow] = useState<ContentCardData | null>(null);
  const [episodes, setEpisodes] = useState<EpisodeData[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingEpisodes, setIsLoadingEpisodes] = useState(false);

  useEffect(() => {
    if (!window.api) {
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    setSelectedCategory(null);

    Promise.all([
      window.api.content.getSeries(selectedSource ?? undefined, sortBy),
      window.api.content.getCategories('series'),
    ]).then(([seriesData, cats]) => {
      setSeries(seriesData);
      setCategories(cats);
      setIsLoading(false);
    });
  }, [selectedSource, sortBy]);

  const categoryCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const s of series) {
      if (s.groupName) {
        counts[s.groupName] = (counts[s.groupName] || 0) + 1;
      }
    }
    return counts;
  }, [series]);

  const filtered = useMemo(() => {
    if (!selectedCategory) return series;
    if (Array.isArray(selectedCategory)) {
      const set = new Set(selectedCategory);
      return series.filter((s) => s.groupName != null && set.has(s.groupName));
    }
    return series.filter((s) => s.groupName === selectedCategory);
  }, [series, selectedCategory]);

  const handleShowClick = useCallback((item: ContentCardData) => {
    setSelectedShow(item);
    setIsLoadingEpisodes(true);

    if (!window.api) {
      setIsLoadingEpisodes(false);
      return;
    }

    window.api.content.getEpisodes(item.id).then((eps: EpisodeData[]) => {
      setEpisodes(eps);
      setIsLoadingEpisodes(false);
    });
  }, []);

  const play = usePlayerStore((s) => s.play);
  const toggle = useFavoritesStore((s) => s.toggle);
  const favoriteIds = useFavoritesStore((s) => s.favoriteIds);

  const handleFavoriteToggle = useCallback(
    (item: ContentCardData) => {
      toggle(item.id);
    },
    [toggle],
  );

  const handleBack = useCallback(() => {
    setSelectedShow(null);
    setEpisodes([]);
  }, []);

  // Group episodes by season
  const seasons = useMemo(() => {
    const map = new Map<number, EpisodeData[]>();
    for (const ep of episodes) {
      const season = ep.seasonNumber ?? 1;
      if (!map.has(season)) map.set(season, []);
      map.get(season)!.push(ep);
    }
    // Sort seasons
    return Array.from(map.entries()).sort(([a], [b]) => a - b);
  }, [episodes]);

  if (!isLoading && series.length === 0) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <h2 className="text-2xl font-bold text-surface-100 text-glow-sm">Series</h2>
          <SourceSwitcher selected={selectedSource} onSelect={setSelectedSource} />
        </div>
        <EmptyState
          icon="layers"
          title="No series"
          message="Add an IPTV source in Settings to see series."
        />
      </div>
    );
  }

  // Episode detail view
  if (selectedShow) {
    return (
      <div className="flex h-full flex-col">
        <div className="mb-4 flex items-center gap-3">
          <button
            onClick={handleBack}
            className="rounded-lg p-2 text-surface-400 transition-colors hover:bg-surface-800 hover:text-surface-200"
          >
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
            </svg>
          </button>
          <div>
            <h2 className="text-2xl font-bold text-surface-100 text-glow-sm">
              {selectedShow.cleanTitle || selectedShow.title}
            </h2>
            {selectedShow.groupName && (
              <p className="text-sm text-surface-500">{selectedShow.groupName}</p>
            )}
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto">
          {isLoadingEpisodes ? (
            <div className="space-y-3">
              {Array.from({ length: 6 }).map((_, i) => (
                <div key={i} className="h-14 animate-pulse rounded-lg bg-surface-800" />
              ))}
            </div>
          ) : episodes.length === 0 ? (
            <div className="flex items-center justify-center rounded-xl border border-dashed border-surface-700 bg-surface-900/50 py-12">
              <p className="text-sm text-surface-500">No episodes found for this series.</p>
            </div>
          ) : (
            <div className="space-y-6">
              {seasons.map(([seasonNum, eps]) => (
                <div key={seasonNum}>
                  <h3 className="mb-3 text-lg font-semibold text-surface-200">
                    Season {seasonNum}
                  </h3>
                  <div className="space-y-1">
                    {eps
                      .sort((a, b) => (a.episodeNumber ?? 0) - (b.episodeNumber ?? 0))
                      .map((ep) => (
                        <button
                          key={ep.id}
                          className="flex w-full items-center gap-4 rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3 text-left transition-all hover:border-accent/20 hover:shadow-glow-sm"
                          onClick={() => {
                            const title = ep.title || `S${ep.seasonNumber ?? 1}E${ep.episodeNumber ?? '?'}`;
                            const showName = selectedShow?.cleanTitle || selectedShow?.title || '';
                            play(
                              ep.streamUrl,
                              showName ? `${showName} - ${title}` : title,
                              selectedShow?.id,
                              ep.id,
                            );
                          }}
                        >
                          <span className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-md bg-surface-800 text-sm font-medium text-surface-400">
                            {ep.episodeNumber ?? '?'}
                          </span>
                          <div className="flex-1 overflow-hidden">
                            <p className="truncate text-sm font-medium text-surface-200">
                              {ep.title || `Episode ${ep.episodeNumber ?? '?'}`}
                            </p>
                            {ep.duration && (
                              <p className="text-xs text-surface-500">
                                {formatDuration(ep.duration)}
                              </p>
                            )}
                          </div>
                          <svg
                            className="h-5 w-5 flex-shrink-0 text-surface-600"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                            strokeWidth={1.5}
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.348a1.125 1.125 0 010 1.971l-11.54 6.347a1.125 1.125 0 01-1.667-.985V5.653z"
                            />
                          </svg>
                        </button>
                      ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    );
  }

  // Show grid
  return (
    <div className="flex h-full flex-col">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-2xl font-bold text-surface-100 text-glow-sm">Series</h2>
        <div className="flex items-center gap-3">
          <SortDropdown value={sortBy} onChange={setSortBy} />
          <SourceSwitcher selected={selectedSource} onSelect={setSelectedSource} />
          <span className="text-sm text-surface-500">
            {filtered.length.toLocaleString()} show{filtered.length !== 1 ? 's' : ''}
          </span>
        </div>
      </div>

      <div className="flex min-h-0 flex-1 gap-4">
        <CategorySidebar
          categories={categories}
          selected={selectedCategory}
          onSelect={setSelectedCategory}
          isLoading={isLoading}
          categoryCounts={categoryCounts}
          totalCount={series.length}
        />
        <div className="min-h-0 flex-1">
          <ContentGrid
            items={filtered}
            onItemClick={handleShowClick}
            onFavoriteToggle={handleFavoriteToggle}
            favoriteIds={favoriteIds}
            isLoading={isLoading}
            cardStyle="poster"
          />
        </div>
      </div>
    </div>
  );
}

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}
