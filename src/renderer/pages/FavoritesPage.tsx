import { useEffect, useState, useCallback } from 'react';
import { ContentGrid, type ContentCardData } from '../components/ContentGrid';
import { EmptyState } from '../components/EmptyState';
import { usePlayerStore } from '../stores/player-store';
import { useFavoritesStore } from '../stores/favorites-store';
import type { FavoriteEntry } from '../../main/services/favorites-store';

export function FavoritesPage() {
  const [favorites, setFavorites] = useState<FavoriteEntry[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  const play = usePlayerStore((s) => s.play);
  const toggle = useFavoritesStore((s) => s.toggle);
  const favoriteIds = useFavoritesStore((s) => s.favoriteIds);

  const loadFavorites = useCallback(async () => {
    if (!window.api) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    const data: FavoriteEntry[] = await window.api.favorites.getAll();
    setFavorites(data);
    setIsLoading(false);
  }, []);

  useEffect(() => {
    loadFavorites();
  }, [loadFavorites]);

  // Re-load when favoriteIds changes (toggled from this page)
  useEffect(() => {
    if (!isLoading) loadFavorites();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [favoriteIds]);

  const items: ContentCardData[] = favorites.map((f) => ({
    id: f.content.id,
    title: f.content.title,
    cleanTitle: f.content.cleanTitle,
    groupName: f.content.groupName,
    logoUrl: f.content.logoUrl,
    streamUrl: f.content.streamUrl,
    type: f.content.type,
  }));

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

  if (!isLoading && favorites.length === 0) {
    return (
      <div className="space-y-6">
        <h2 className="text-2xl font-bold text-surface-100">Favorites</h2>
        <EmptyState
          icon="heart"
          title="No favorites yet"
          message="Browse your content and tap the heart to save favorites here."
        />
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-2xl font-bold text-surface-100">Favorites</h2>
        <span className="text-sm text-surface-500">
          {items.length} item{items.length !== 1 ? 's' : ''}
        </span>
      </div>
      <div className="min-h-0 flex-1">
        <ContentGrid
          items={items}
          onItemClick={handleItemClick}
          onFavoriteToggle={handleFavoriteToggle}
          favoriteIds={favoriteIds}
          isLoading={isLoading}
        />
      </div>
    </div>
  );
}
