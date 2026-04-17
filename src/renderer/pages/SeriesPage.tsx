import { useEffect, useState, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ContentGrid, type ContentCardData } from '../components/ContentGrid';
import { CategorySidebar } from '../components/CategorySidebar';
import { EmptyState } from '../components/EmptyState';
import { SourceSwitcher } from '../components/SourceSwitcher';
import { SortDropdown, type SortOption } from '../components/SortDropdown';
import { useFavoritesStore } from '../stores/favorites-store';

const EMPTY_ITEMS: ContentCardData[] = [];
const EMPTY_CATS: string[] = [];

export function SeriesPage() {
  const [selectedCategory, setSelectedCategory] = useState<string | string[] | null>(null);
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<SortOption>('provider');

  useEffect(() => {
    setSelectedCategory(null);
  }, [selectedSource, sortBy]);

  const seriesQuery = useQuery({
    queryKey: ['content', 'series', selectedSource, sortBy],
    queryFn: () => window.api.content.getSeries(selectedSource ?? undefined, sortBy),
    enabled: !!window.api,
    staleTime: 5 * 60_000,
    placeholderData: (prev) => prev,
  });

  const catsQuery = useQuery({
    queryKey: ['categories', 'series'],
    queryFn: () => window.api.content.getCategories('series'),
    enabled: !!window.api,
    staleTime: 5 * 60_000,
    placeholderData: (prev) => prev,
  });

  const series = seriesQuery.data ?? EMPTY_ITEMS;
  const categories = catsQuery.data ?? EMPTY_CATS;
  const isLoading =
    (seriesQuery.isLoading || catsQuery.isLoading) && (!seriesQuery.data || !catsQuery.data);

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

  const navigate = useNavigate();
  const toggle = useFavoritesStore((s) => s.toggle);
  const favoriteIds = useFavoritesStore((s) => s.favoriteIds);

  const handleShowClick = useCallback(
    (item: ContentCardData) => {
      navigate(`/series/${item.id}`);
    },
    [navigate],
  );

  const handleFavoriteToggle = useCallback(
    (item: ContentCardData) => {
      toggle(item.id);
    },
    [toggle],
  );

  if (!isLoading && series.length === 0) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <h2 className="font-serif text-4xl italic tracking-tight text-surface-100">Series</h2>
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

  // Show grid
  return (
    <div className="flex h-full flex-col">
      <div className="mb-4 flex items-baseline justify-between">
        <div className="flex items-baseline gap-3">
          <h2 className="font-serif text-4xl italic tracking-tight text-surface-100">Series</h2>
          <span className="font-mono text-[11px] uppercase tracking-widest-plus text-surface-500 tabular-nums">
            {filtered.length.toLocaleString()} shows
          </span>
        </div>
        <div className="flex items-center gap-3">
          <SortDropdown value={sortBy} onChange={setSortBy} />
          <SourceSwitcher selected={selectedSource} onSelect={setSelectedSource} />
        </div>
      </div>

      <div className="flex min-h-0 flex-1 gap-4">
        <CategorySidebar
          categories={categories}
          selected={selectedCategory}
          onSelect={setSelectedCategory}
          contentType="series"
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
            viewMode="grid"
          />
        </div>
      </div>
    </div>
  );
}
