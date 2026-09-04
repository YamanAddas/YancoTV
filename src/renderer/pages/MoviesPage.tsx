import { useEffect, useState, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ContentGrid, type ContentCardData } from '../components/ContentGrid';
import { CategorySidebar } from '../components/CategorySidebar';
import { EmptyState } from '../components/EmptyState';
import { SourceSwitcher } from '../components/SourceSwitcher';
import { SortDropdown, type SortOption } from '../components/SortDropdown';
import { useFavoritesStore } from '../stores/favorites-store';
import { useT } from '../i18n';

const EMPTY_ITEMS: ContentCardData[] = [];
const EMPTY_CATS: string[] = [];

export function MoviesPage() {
  const t = useT();
  const [selectedCategory, setSelectedCategory] = useState<string | string[] | null>(null);
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<SortOption>('provider');

  // Clear category filter when source/sort changes (fresh dataset)
  useEffect(() => {
    setSelectedCategory(null);
  }, [selectedSource, sortBy]);

  const moviesQuery = useQuery<ContentCardData[]>({
    queryKey: ['content', 'movie', selectedSource, sortBy],
    queryFn: () => window.api.content.getMovies(selectedSource ?? undefined, sortBy),
    enabled: !!window.api,
    staleTime: 5 * 60_000,
    placeholderData: (prev) => prev,
  });

  const catsQuery = useQuery<string[]>({
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
          <h2 className="font-serif text-4xl italic tracking-tight text-surface-100">Movies</h2>
          <SourceSwitcher selected={selectedSource} onSelect={setSelectedSource} />
        </div>
        <EmptyState
          icon="film"
          title={t('empty.noMovies')}
          message={t('empty.noMoviesHint')}
        />
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="mb-4 flex items-baseline justify-between">
        <div className="flex items-baseline gap-3">
          <h2 className="font-serif text-4xl italic tracking-tight text-surface-100">Movies</h2>
          <span className="font-mono text-[11px] uppercase tracking-widest-plus text-surface-500 tabular-nums">
            {filtered.length.toLocaleString()} titles
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
            viewMode="grid"
          />
        </div>
      </div>
    </div>
  );
}
