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
  isLoading?: boolean;
}

export function ContentGrid({ items, onItemClick, isLoading }: ContentGridProps) {
  const ItemContent = useCallback(
    (index: number) => {
      const item = items[index];
      return <ContentCard item={item} onClick={() => onItemClick(item)} />;
    },
    [items, onItemClick],
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

function ContentCard({ item, onClick }: { item: ContentCardData; onClick: () => void }) {
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
