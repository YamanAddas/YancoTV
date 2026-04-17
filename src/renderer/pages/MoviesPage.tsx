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

export function MoviesPage() {
  const [selectedCategory, setSelectedCategory] = useState<string | string[] | null>(null);
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<SortOption>('provider');

  // Clear category filter when source/sort changes (fresh dataset)
  useEffect(() => {
    setSelectedCategory(null);
  }, [selectedSource, sortBy]);

  const moviesQuery = useQuery({
    queryKey: ['content', 'movie', selectedSource, sortBy],
    queryFn: () => window.api.content.getMovies(selectedSource ?? undefined, sortBy),
    enabled: !!window.api,
    staleTime: 5 * 60_000,
    placeholderData: (prev) => prev,
  });

  const catsQuery = useQuery({
    queryKey: ['categories', 'movie'],
    queryFn: () => window.api.content.getCategories('movie'),
    enabled: !!window.api,
    staleTime: 5 * 60_000,
    placeholderData: (prev) => prev,
  });

  const movies = moviesQuery.data ?? EMPTY_ITEMS;
  const categories = catsQuery.data ?? EMPTY_CATS;
  const isLoading =
    (moviesQuery.isLoading || catsQuery.isLoading) && (!moviesQuery.data || !catsQuery.data);

  const categoryCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const m of movies) {
      if (m.groupName) {
        counts[m.groupName] = (counts[m.groupName] || 0) + 1;
      }
    }
    return counts;
  }, [movies]);

  const filtered = useMemo(() => {
    if (!selectedCategory) return movies;
    if (Array.isArray(selectedCategory)) {
      const set = new Set(selectedCategory);
      return movies.filter((m) => m.groupName != null && set.has(m.groupName));
    }
    return movies.filter((m) => m.groupName === selectedCategory);
  }, [movies, selectedCategory]);

  const navigate = useNavigate();
  const toggle = useFavoritesStore((s) => s.toggle);
  const favoriteIds = useFavoritesStore((s) => s.favoriteIds);

  const handleItemClick = useCallback(
    (item: ContentCardData) => {
      navigate(`/movies/${item.id}`);
    },
    [navigate],
  );

  const handleFavoriteToggle = useCallback(
    (item: ContentCardData) => {
      toggle(item.id);
    },
    [toggle],
  );

  if (!isLoading && movies.length === 0) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <h2 className="text-2xl font-bold text-surface-100 text-glow-sm">Movies</h2>
          <SourceSwitcher selected={selectedSource} onSelect={setSelectedSource} />
        </div>
        <EmptyState
          icon="film"
          title="No movies"
          message="Add an IPTV source in Settings to see movies."
        />
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-2xl font-bold text-surface-100 text-glow-sm">Movies</h2>
        <div className="flex items-center gap-3">
          <SortDropdown value={sortBy} onChange={setSortBy} />
          <SourceSwitcher selected={selectedSource} onSelect={setSelectedSource} />
          <span className="text-sm text-surface-500">
            {filtered.length.toLocaleString()} movie{filtered.length !== 1 ? 's' : ''}
          </span>
        </div>
      </div>

      <div className="flex min-h-0 flex-1 gap-4">
        <CategorySidebar
          categories={categories}
          selected={selectedCategory}
          onSelect={setSelectedCategory}
          contentType="movie"
          isLoading={isLoading}
          categoryCounts={categoryCounts}
          totalCount={movies.length}
        />
        <div className="min-h-0 flex-1">
          <ContentGrid
            items={filtered}
            onItemClick={handleItemClick}
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
