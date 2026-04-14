import { VirtuosoGrid } from 'react-virtuoso';
import { useCallback } from 'react';

export interface ContentCardData {
  id: string;
  title: string;
  cleanTitle?: string;
  groupName?: string;
  logoUrl?: string;
  streamUrl: string;
  type: string;
}

interface ContentGridProps {
  items: ContentCardData[];
  onItemClick: (item: ContentCardData) => void;
  onFavoriteToggle?: (item: ContentCardData) => void;
  favoriteIds?: Set<string>;
  isLoading?: boolean;
}

export function ContentGrid({
  items,
  onItemClick,
  onFavoriteToggle,
  favoriteIds,
  isLoading,
}: ContentGridProps) {
  const ItemContent = useCallback(
    (index: number) => {
      const item = items[index];
      return (
        <ContentCard
          item={item}
          onClick={() => onItemClick(item)}
          onFavoriteToggle={onFavoriteToggle ? () => onFavoriteToggle(item) : undefined}
          isFavorite={favoriteIds ? favoriteIds.has(item.id) : false}
        />
      );
    },
    [items, onItemClick, onFavoriteToggle, favoriteIds],
  );

  if (isLoading) {
    return <SkeletonGrid />;
  }

  if (items.length === 0) {
    return null;
  }

  return (
    <VirtuosoGrid
      totalCount={items.length}
      overscan={200}
      listClassName="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3"
      itemContent={ItemContent}
      style={{ height: '100%' }}
    />
  );
}

function ContentCard({
  item,
  onClick,
  onFavoriteToggle,
  isFavorite,
}: {
  item: ContentCardData;
  onClick: () => void;
  onFavoriteToggle?: () => void;
  isFavorite: boolean;
}) {
  return (
    <button
      onClick={onClick}
      className="group flex flex-col overflow-hidden rounded-lg border border-surface-800 bg-surface-900 text-left transition-all hover:border-accent/50 hover:shadow-lg hover:shadow-accent/5 focus:outline-none focus:ring-2 focus:ring-accent/50"
    >
      <div className="relative aspect-video w-full overflow-hidden bg-surface-800">
        {item.logoUrl ? (
          <img
            src={item.logoUrl}
            alt={item.title}
            className="h-full w-full object-contain p-2 transition-transform group-hover:scale-105"
            loading="lazy"
            onError={(e) => {
              (e.target as HTMLImageElement).style.display = 'none';
            }}
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            <span className="text-2xl font-bold text-surface-600">
              {(item.cleanTitle || item.title).charAt(0).toUpperCase()}
            </span>
          </div>
        )}

        {onFavoriteToggle && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              onFavoriteToggle();
            }}
            className={`absolute right-1.5 top-1.5 flex h-7 w-7 items-center justify-center rounded-full transition-all ${
              isFavorite
                ? 'bg-red-500/90 text-white opacity-100'
                : 'bg-surface-950/70 text-surface-400 opacity-0 group-hover:opacity-100 hover:text-red-400'
            }`}
            title={isFavorite ? 'Remove from favorites' : 'Add to favorites'}
          >
            <HeartIcon filled={isFavorite} />
          </button>
        )}
      </div>
      <div className="flex flex-1 flex-col p-2.5">
        <p className="line-clamp-2 text-sm font-medium text-surface-200 group-hover:text-surface-100">
          {item.cleanTitle || item.title}
        </p>
        {item.groupName && (
          <p className="mt-1 truncate text-xs text-surface-500">{item.groupName}</p>
        )}
      </div>
    </button>
  );
}

function HeartIcon({ filled }: { filled: boolean }) {
  return (
    <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={2}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12z"
      />
    </svg>
  );
}

function SkeletonGrid() {
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
      {Array.from({ length: 18 }).map((_, i) => (
        <div key={i} className="animate-pulse rounded-lg border border-surface-800 bg-surface-900">
          <div className="aspect-video w-full bg-surface-800" />
          <div className="p-2.5 space-y-2">
            <div className="h-4 w-3/4 rounded bg-surface-800" />
            <div className="h-3 w-1/2 rounded bg-surface-800" />
          </div>
        </div>
      ))}
    </div>
  );
}
