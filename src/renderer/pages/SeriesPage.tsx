import { useEffect, useState, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { ContentGrid, type ContentCardData } from '../components/ContentGrid';
import { CategorySidebar } from '../components/CategorySidebar';
import { EmptyState } from '../components/EmptyState';
import { SourceSwitcher } from '../components/SourceSwitcher';
import { SortDropdown, type SortOption } from '../components/SortDropdown';
import { useFavoritesStore } from '../stores/favorites-store';

export function SeriesPage() {
  const [series, setSeries] = useState<ContentCardData[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [selectedCategory, setSelectedCategory] = useState<string | string[] | null>(null);
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<SortOption>('provider');
  const [isLoading, setIsLoading] = useState(true);

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
          />
        </div>
      </div>
    </div>
  );
}
