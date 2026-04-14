import { useEffect, useState, useMemo, useCallback } from 'react';
import { ContentGrid, type ContentCardData } from '../components/ContentGrid';
import { CategorySidebar } from '../components/CategorySidebar';
import { EmptyState } from '../components/EmptyState';
import { SourceSwitcher } from '../components/SourceSwitcher';
import { usePlayerStore } from '../stores/player-store';
import { useFavoritesStore } from '../stores/favorites-store';

export function LiveTvPage() {
  const [channels, setChannels] = useState<ContentCardData[]>([]);
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
      window.api.content.getLive(selectedSource ?? undefined),
      window.api.content.getCategories('live'),
    ]).then(([liveData, cats]) => {
      setChannels(liveData);
      setCategories(cats);
      setIsLoading(false);
    });
  }, [selectedSource]);

  const filtered = useMemo(() => {
    if (!selectedCategory) return channels;
    return channels.filter((ch) => ch.groupName === selectedCategory);
  }, [channels, selectedCategory]);

  const play = usePlayerStore((s) => s.play);
  const toggle = useFavoritesStore((s) => s.toggle);
  const favoriteIds = useFavoritesStore((s) => s.favoriteIds);

  const handleItemClick = useCallback(
    (item: ContentCardData) => {
      play(item.streamUrl, item.cleanTitle || item.title, item.id);
    },
    [play],
  );

  const handleFavoriteToggle = useCallback(
    (item: ContentCardData) => {
      toggle(item.id);
    },
    [toggle],
  );

  if (!isLoading && channels.length === 0) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <h2 className="text-2xl font-bold text-surface-100">Live TV</h2>
          <SourceSwitcher selected={selectedSource} onSelect={setSelectedSource} />
        </div>
        <EmptyState
          icon="tv"
          title="No live channels"
          message="Add an IPTV source in Settings to see live channels."
        />
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-2xl font-bold text-surface-100">Live TV</h2>
        <div className="flex items-center gap-4">
          <SourceSwitcher selected={selectedSource} onSelect={setSelectedSource} />
          <span className="text-sm text-surface-500">
            {filtered.length.toLocaleString()} channel{filtered.length !== 1 ? 's' : ''}
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
          <ContentGrid
            items={filtered}
            onItemClick={handleItemClick}
            onFavoriteToggle={handleFavoriteToggle}
            favoriteIds={favoriteIds}
            isLoading={isLoading}
          />
        </div>
      </div>
    </div>
  );
}
