import { useEffect, useState, useMemo, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ContentGrid, REORDER_ITEM_CAP, type ContentCardData } from '../components/ContentGrid';
import { CategorySidebar } from '../components/CategorySidebar';
import { EmptyState } from '../components/EmptyState';
import { SourceSwitcher } from '../components/SourceSwitcher';
import { SortDropdown, type SortOption } from '../components/SortDropdown';
import { PinModal } from '../components/PinModal';
import { usePlayerStore } from '../stores/player-store';
import { useFavoritesStore } from '../stores/favorites-store';
import { useParentalStore } from '../stores/parental-store';
import { useSettingsStore } from '../stores/settings-store';
import { useRecentChannelsStore } from '../stores/recent-channels-store';
import { useToastStore } from '../stores/toast-store';
import { useNowNextBatch } from '../hooks/use-epg';

const EMPTY_ITEMS: ContentCardData[] = [];
const EMPTY_CATS: string[] = [];

export function LiveTvPage() {
  const [selectedCategory, setSelectedCategory] = useState<string | string[] | null>(null);
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<SortOption>('provider');

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
    setSelectedCategory(null);
  }, [selectedSource, sortBy]);

  const channelsQuery = useQuery<ContentCardData[]>({
    queryKey: ['content', 'live', selectedSource, sortBy],
    queryFn: () => window.api.content.getLive(selectedSource ?? undefined, sortBy),
    enabled: !!window.api,
    staleTime: 5 * 60_000,
    placeholderData: (prev) => prev,
  });

  const catsQuery = useQuery<string[]>({
    queryKey: ['categories', 'live'],
    queryFn: () => window.api.content.getCategories('live'),
    enabled: !!window.api,
    staleTime: 5 * 60_000,
    placeholderData: (prev) => prev,
  });

  const channels = channelsQuery.data ?? EMPTY_ITEMS;
  const categories = catsQuery.data ?? EMPTY_CATS;
  const isLoading =
    (channelsQuery.isLoading || catsQuery.isLoading) &&
    (!channelsQuery.data || !catsQuery.data);

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

  // Per-category channel counts for the sidebar
  const categoryCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const ch of visibleChannels) {
      if (ch.groupName) {
        counts[ch.groupName] = (counts[ch.groupName] || 0) + 1;
      }
    }
    return counts;
  }, [visibleChannels]);

  // Channel order is stored in settings as JSON. Per-group when a single group is
  // selected, otherwise a global key scoped by source+sort.
  const channelOrderKey =
    typeof selectedCategory === 'string'
      ? `channel_order:${selectedCategory}`
      : `channel_order:__all__:${selectedSource ?? 'merged'}:${sortBy}`;
  const channelOrderRaw = useSettingsStore((s) => s.data[channelOrderKey]);
  const setSetting = useSettingsStore((s) => s.set);

  // Explicit reorder-mode toggle. When on, drag is active; click still plays.
  const [reorderMode, setReorderMode] = useState(false);

  const filtered = useMemo(() => {
    let result: ContentCardData[];
    if (!selectedCategory) {
      result = visibleChannels;
    } else if (Array.isArray(selectedCategory)) {
      const set = new Set(selectedCategory);
      result = visibleChannels.filter((ch) => ch.groupName != null && set.has(ch.groupName));
    } else {
      result = visibleChannels.filter((ch) => ch.groupName === selectedCategory);
    }

    // Apply saved order if present — always applies, per-group or global scope
    if (channelOrderRaw) {
      try {
        const orderedIds: string[] = JSON.parse(channelOrderRaw);
        const rank = new Map<string, number>();
        orderedIds.forEach((id, i) => rank.set(id, i));
        result = [...result].sort((a, b) => {
          const ra = rank.get(a.id) ?? Number.MAX_SAFE_INTEGER;
          const rb = rank.get(b.id) ?? Number.MAX_SAFE_INTEGER;
          return ra - rb;
        });
      } catch {
        // Ignore bad JSON — fall back to natural order
      }
    }

    return result;
  }, [visibleChannels, selectedCategory, channelOrderRaw]);

  const handleReorder = useCallback(
    (ids: string[]) => {
      void setSetting(channelOrderKey, JSON.stringify(ids));
    },
    [channelOrderKey, setSetting],
  );

  // Collect tvg IDs from visible channels for EPG now/next lookup
  const tvgIds = useMemo(
    () => filtered.map((ch) => ch.tvgId).filter((id): id is string => !!id),
    [filtered],
  );
  const { data: nowNextMap } = useNowNextBatch(tvgIds);

  const play = usePlayerStore((s) => s.play);
  const toggle = useFavoritesStore((s) => s.toggle);
  const favoriteIds = useFavoritesStore((s) => s.favoriteIds);

  // Recent channels strip (19.7). Look up each recorded ID in the loaded
  // channel list, preserving recency order. Keeps at most 8 so the strip
  // doesn't crowd the main grid.
  const recentIds = useRecentChannelsStore((s) => s.ids);
  const recentChannels = useMemo(() => {
    if (recentIds.length === 0 || visibleChannels.length === 0) return [] as ContentCardData[];
    const byId = new Map(visibleChannels.map((c) => [c.id, c] as const));
    const out: ContentCardData[] = [];
    for (const id of recentIds) {
      const ch = byId.get(id);
      if (ch) out.push(ch);
      if (out.length >= 8) break;
    }
    return out;
  }, [recentIds, visibleChannels]);

  const handleItemClick = useCallback(
    (item: ContentCardData) => {
      // If channel is locked, prompt for PIN before playing
      if (lockedIds.has(item.id) && parentalSettings.pinEnabled) {
        setPinModalTarget(item);
        return;
      }
      play(item.streamUrl, item.cleanTitle || item.title, item.id, undefined, 'live');
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
          undefined,
          'live',
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

  const handleRecord = useCallback(async (item: ContentCardData) => {
    const res = await window.api.recording.start({
      contentId: item.id,
      title: item.cleanTitle || item.title,
      streamUrl: item.streamUrl,
    });
    if (!res.ok) {
      useToastStore.getState().push({
        kind: 'error',
        message: `Could not start recording: ${res.error}`,
      });
    }
  }, []);

  if (!isLoading && !parentalLoaded) {
    // Still loading parental settings — show spinner briefly
    return null;
  }

  if (!isLoading && channels.length === 0) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <h2 className="font-serif text-4xl italic tracking-tight text-surface-100">Live TV</h2>
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
      <div className="mb-4 flex items-baseline justify-between">
        <div className="flex items-baseline gap-3">
          <h2 className="font-serif text-4xl italic tracking-tight text-surface-100">Live TV</h2>
          <span className="font-mono text-[11px] uppercase tracking-widest-plus text-surface-500 tabular-nums">
            {filtered.length.toLocaleString()} channels
          </span>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => setReorderMode((v) => !v)}
            className={`flex items-center gap-1.5 rounded-md px-2.5 py-1 font-display text-[11px] uppercase tracking-widest-plus transition-colors ${
              reorderMode
                ? 'bg-accent/20 text-accent'
                : 'bg-surface-800/40 text-surface-400 hover:bg-surface-700/50 hover:text-surface-200'
            }`}
            title={reorderMode ? 'Exit reorder mode' : 'Reorder channels'}
          >
            <svg className="h-3.5 w-3.5" viewBox="0 0 10 16" fill="currentColor">
              <circle cx="3" cy="2" r="1.2" />
              <circle cx="7" cy="2" r="1.2" />
              <circle cx="3" cy="6" r="1.2" />
              <circle cx="7" cy="6" r="1.2" />
              <circle cx="3" cy="10" r="1.2" />
              <circle cx="7" cy="10" r="1.2" />
              <circle cx="3" cy="14" r="1.2" />
              <circle cx="7" cy="14" r="1.2" />
            </svg>
            {reorderMode ? 'Done' : 'Reorder'}
          </button>
          <SortDropdown value={sortBy} onChange={setSortBy} />
          <SourceSwitcher selected={selectedSource} onSelect={setSelectedSource} />
        </div>
      </div>

      {reorderMode && (
        filtered.length > REORDER_ITEM_CAP ? (
          <div className="mb-3 flex items-center justify-between rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-300">
            <span className="font-display uppercase tracking-widest-plus">
              Too many channels to reorder ({filtered.length.toLocaleString()}). Pick a category to narrow the list.
            </span>
            <span className="font-mono text-[10px] text-amber-300/70">
              cap {REORDER_ITEM_CAP.toLocaleString()}
            </span>
          </div>
        ) : (
          <div className="mb-3 flex items-center justify-between rounded-md border border-accent/30 bg-accent/10 px-3 py-2 text-xs text-accent">
            <span className="font-display uppercase tracking-widest-plus">
              Reorder mode — drag any card to move it
            </span>
            <span className="font-mono text-[10px] text-accent/70">
              {typeof selectedCategory === 'string' ? `in "${selectedCategory}"` : 'global order'}
            </span>
          </div>
        )
      )}

      {!reorderMode && recentChannels.length > 0 && (
        <div className="mb-4">
          <div className="mb-2 flex items-center gap-2">
            <h3 className="font-display text-[11px] uppercase tracking-widest-plus text-surface-400">
              Recently watched
            </h3>
            <span className="rounded-full bg-surface-800/60 px-2 py-0.5 font-mono text-[10px] tabular-nums text-surface-500">
              {recentChannels.length}
            </span>
          </div>
          <div className="flex gap-2 overflow-x-auto pb-2">
            {recentChannels.map((ch) => (
              <button
                key={ch.id}
                onClick={() => handleItemClick(ch)}
                className="group flex shrink-0 items-center gap-2 rounded-lg border border-surface-700 bg-surface-800/60 px-3 py-2 text-left transition-colors hover:border-accent/50 hover:bg-surface-700/60"
                title={ch.cleanTitle || ch.title}
              >
                {ch.logoUrl ? (
                  <img
                    src={ch.logoUrl}
                    alt=""
                    className="h-8 w-8 shrink-0 rounded object-contain"
                    onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
                  />
                ) : (
                  <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded bg-surface-700 text-[10px] font-bold text-surface-400">
                    {(ch.cleanTitle || ch.title)?.[0]?.toUpperCase() ?? '?'}
                  </div>
                )}
                <span className="max-w-[140px] truncate text-xs font-medium text-surface-200 group-hover:text-accent">
                  {ch.cleanTitle || ch.title}
                </span>
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="flex min-h-0 flex-1 gap-4">
        <CategorySidebar
          categories={categories}
          selected={selectedCategory}
          onSelect={setSelectedCategory}
          contentType="live"
          isLoading={isLoading}
          categoryCounts={categoryCounts}
          totalCount={visibleChannels.length}
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
            onRecord={handleRecord}
            reorderable={reorderMode}
            onReorder={handleReorder}
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
