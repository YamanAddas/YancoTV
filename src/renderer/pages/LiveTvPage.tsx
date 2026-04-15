import { useEffect, useState, useMemo, useCallback } from 'react';
import { ContentGrid, type ContentCardData } from '../components/ContentGrid';
import { CategorySidebar } from '../components/CategorySidebar';
import { EmptyState } from '../components/EmptyState';
import { SourceSwitcher } from '../components/SourceSwitcher';
import { SortDropdown, type SortOption } from '../components/SortDropdown';
import { PinModal } from '../components/PinModal';
import { usePlayerStore } from '../stores/player-store';
import { useFavoritesStore } from '../stores/favorites-store';
import { useParentalStore } from '../stores/parental-store';
import { useNowNextBatch } from '../hooks/use-epg';

export function LiveTvPage() {
  const [channels, setChannels] = useState<ContentCardData[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<SortOption>('provider');
  const [isLoading, setIsLoading] = useState(true);

  // Parental controls
  const parentalSettings = useParentalStore((s) => s.settings);
  const hiddenIds = useParentalStore((s) => s.hiddenIds);
  const lockedIds = useParentalStore((s) => s.lockedIds);
  const parentalLoaded = useParentalStore((s) => s.loaded);
  const parentalLoad = useParentalStore((s) => s.load);
  const lockChannel = useParentalStore((s) => s.lockChannel);
  const unlockChannel = useParentalStore((s) => s.unlockChannel);
  const hideChannel = useParentalStore((s) => s.hideChannel);

  // PIN modal state
  const [pinModalTarget, setPinModalTarget] = useState<ContentCardData | null>(null);

  useEffect(() => {
    parentalLoad();
  }, [parentalLoad]);

  useEffect(() => {
    if (!window.api) {
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    setSelectedCategory(null);

    Promise.all([
      window.api.content.getLive(selectedSource ?? undefined, sortBy),
      window.api.content.getCategories('live'),
    ]).then(([liveData, cats]) => {
      setChannels(liveData);
      setCategories(cats);
      setIsLoading(false);
    });
  }, [selectedSource, sortBy]);

  // Filter hidden channels and optionally adult content
  const visibleChannels = useMemo(() => {
    let result = channels;

    // Filter hidden channels
    if (hiddenIds.size > 0) {
      result = result.filter((ch) => !hiddenIds.has(ch.id));
    }

    // Filter adult content if enabled
    if (parentalSettings.hideAdultContent) {
      result = result.filter((ch) => {
        const name = (ch.groupName || '').toLowerCase();
        const title = (ch.title || '').toLowerCase();
        return !(
          name.includes('adult') ||
          name.includes('xxx') ||
          name.includes('18+') ||
          title.includes('xxx')
        );
      });
    }

    return result;
  }, [channels, hiddenIds, parentalSettings.hideAdultContent]);

  const filtered = useMemo(() => {
    if (!selectedCategory) return visibleChannels;
    return visibleChannels.filter((ch) => ch.groupName === selectedCategory);
  }, [visibleChannels, selectedCategory]);

  // Collect tvg IDs from visible channels for EPG now/next lookup
  const tvgIds = useMemo(
    () => filtered.map((ch) => ch.tvgId).filter((id): id is string => !!id),
    [filtered],
  );
  const { data: nowNextMap } = useNowNextBatch(tvgIds);

  const play = usePlayerStore((s) => s.play);
  const toggle = useFavoritesStore((s) => s.toggle);
  const favoriteIds = useFavoritesStore((s) => s.favoriteIds);

  const handleItemClick = useCallback(
    (item: ContentCardData) => {
      // If channel is locked, prompt for PIN before playing
      if (lockedIds.has(item.id) && parentalSettings.pinEnabled) {
        setPinModalTarget(item);
        return;
      }
      play(item.streamUrl, item.cleanTitle || item.title, item.id);
    },
    [play, lockedIds, parentalSettings.pinEnabled],
  );

  const handlePinResult = useCallback(
    (verified: boolean) => {
      if (verified && pinModalTarget) {
        play(
          pinModalTarget.streamUrl,
          pinModalTarget.cleanTitle || pinModalTarget.title,
          pinModalTarget.id,
        );
      }
      setPinModalTarget(null);
    },
    [play, pinModalTarget],
  );

  const handleFavoriteToggle = useCallback(
    (item: ContentCardData) => {
      toggle(item.id);
    },
    [toggle],
  );

  const handleLockToggle = useCallback(
    (item: ContentCardData) => {
      if (lockedIds.has(item.id)) {
        unlockChannel(item.id);
      } else {
        lockChannel(item.id);
      }
    },
    [lockedIds, lockChannel, unlockChannel],
  );

  const handleHideChannel = useCallback(
    (item: ContentCardData) => {
      hideChannel(item.id);
    },
    [hideChannel],
  );

  if (!isLoading && !parentalLoaded) {
    // Still loading parental settings — show spinner briefly
    return null;
  }

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
        <div className="flex items-center gap-3">
          <SortDropdown value={sortBy} onChange={setSortBy} />
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
            lockedIds={lockedIds}
            isLoading={isLoading}
            nowNextMap={nowNextMap}
            onLockToggle={handleLockToggle}
            onHideChannel={handleHideChannel}
          />
        </div>
      </div>

      {/* PIN verification modal for locked channels */}
      {pinModalTarget && (
        <PinModal
          title={`Unlock "${pinModalTarget.cleanTitle || pinModalTarget.title}"`}
          onResult={handlePinResult}
        />
      )}
    </div>
  );
}
