import { useEffect, useState, useMemo, useCallback } from 'react';
import { ContentGrid, type ContentCardData } from '../components/ContentGrid';
import { CategorySidebar } from '../components/CategorySidebar';
import { EmptyState } from '../components/EmptyState';
import { SourceSwitcher } from '../components/SourceSwitcher';
import { usePlayerStore } from '../stores/player-store';

export function MoviesPage() {
  const [movies, setMovies] = useState<ContentCardData[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    if (!window.api) {
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    setSelectedCategory(null);

    Promise.all([
      window.api.content.getMovies(selectedSource ?? undefined),
      window.api.content.getCategories('movie'),
    ]).then(([movieData, cats]) => {
      setMovies(movieData);
      setCategories(cats);
      setIsLoading(false);
    });
  }, [selectedSource]);

  const filtered = useMemo(() => {
    if (!selectedCategory) return movies;
    return movies.filter((m) => m.groupName === selectedCategory);
  }, [movies, selectedCategory]);

  const play = usePlayerStore((s) => s.play);

  const handleItemClick = useCallback(
    (item: ContentCardData) => {
      play(item.streamUrl, item.cleanTitle || item.title);
    },
    [play],
  );

  if (!isLoading && movies.length === 0) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <h2 className="text-2xl font-bold text-surface-100">Movies</h2>
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
        <h2 className="text-2xl font-bold text-surface-100">Movies</h2>
        <div className="flex items-center gap-4">
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
          isLoading={isLoading}
        />
        <div className="min-h-0 flex-1">
          <ContentGrid items={filtered} onItemClick={handleItemClick} isLoading={isLoading} />
        </div>
      </div>
    </div>
  );
}
