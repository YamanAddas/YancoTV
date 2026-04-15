import { VirtuosoGrid, Virtuoso } from 'react-virtuoso';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useSettingsStore } from '../stores/settings-store';
import type { NowNextMap } from '../../shared/types/epg';

export interface ContentCardData {
  id: string;
  title: string;
  cleanTitle?: string;
  groupName?: string;
  logoUrl?: string;
  streamUrl: string;
  tvgId?: string;
  type: string;
}

interface ContentGridProps {
  items: ContentCardData[];
  onItemClick: (item: ContentCardData) => void;
  onFavoriteToggle?: (item: ContentCardData) => void;
  favoriteIds?: Set<string>;
  lockedIds?: Set<string>;
  isLoading?: boolean;
  nowNextMap?: NowNextMap;
  onLockToggle?: (item: ContentCardData) => void;
  onHideChannel?: (item: ContentCardData) => void;
  /** Override list style — defaults to the global ui_list_style setting */
  viewMode?: 'grid' | 'list' | 'compact';
}

export function ContentGrid({
  items,
  onItemClick,
  onFavoriteToggle,
  favoriteIds,
  lockedIds,
  isLoading,
  nowNextMap,
  onLockToggle,
  onHideChannel,
  viewMode: viewModeProp,
}: ContentGridProps) {
  // ── All hooks must be called unconditionally ──────────────────────────────
  const settingsViewMode = useSettingsStore((s) => s.get('ui_list_style')) as 'grid' | 'list' | 'compact';
  const showLogos = useSettingsStore((s) => s.getBool('ui_channel_logos'));
  const viewMode = viewModeProp ?? settingsViewMode ?? 'grid';

  // One stable callback for grid item rendering
  const gridItemContent = useCallback(
    (index: number) => {
      const item = items[index];
      const nowNext = item.tvgId && nowNextMap ? nowNextMap[item.tvgId] : undefined;
      return (
        <GridCard
          item={item}
          onClick={() => onItemClick(item)}
          onFavoriteToggle={onFavoriteToggle ? () => onFavoriteToggle(item) : undefined}
          isFavorite={favoriteIds ? favoriteIds.has(item.id) : false}
          isLocked={lockedIds ? lockedIds.has(item.id) : false}
          showLogo={showLogos}
          nowNext={nowNext}
          onLockToggle={onLockToggle ? () => onLockToggle(item) : undefined}
          onHideChannel={onHideChannel ? () => onHideChannel(item) : undefined}
        />
      );
    },
    [items, onItemClick, onFavoriteToggle, favoriteIds, lockedIds, nowNextMap, showLogos, onLockToggle, onHideChannel],
  );

  // One stable callback for list/compact row rendering
  const rowItemContent = useCallback(
    (index: number) => {
      const item = items[index];
      const nowNext = item.tvgId && nowNextMap ? nowNextMap[item.tvgId] : undefined;
      const props = {
        item,
        onClick: () => onItemClick(item),
        onFavoriteToggle: onFavoriteToggle ? () => onFavoriteToggle(item) : undefined,
        isFavorite: favoriteIds ? favoriteIds.has(item.id) : false,
        isLocked: lockedIds ? lockedIds.has(item.id) : false,
        showLogo: showLogos,
        nowNext,
        onLockToggle: onLockToggle ? () => onLockToggle(item) : undefined,
        onHideChannel: onHideChannel ? () => onHideChannel(item) : undefined,
      };
      return viewMode === 'compact' ? <CompactRow {...props} /> : <ListRow {...props} />;
    },
    [items, viewMode, onItemClick, onFavoriteToggle, favoriteIds, lockedIds, nowNextMap, showLogos, onLockToggle, onHideChannel],
  );
  // ── End hooks ─────────────────────────────────────────────────────────────

  if (isLoading) return <SkeletonGrid />;
  if (items.length === 0) return null;

  if (viewMode === 'grid') {
    return (
      <VirtuosoGrid
        totalCount={items.length}
        overscan={200}
        listClassName="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3"
        itemContent={gridItemContent}
        style={{ height: '100%' }}
      />
    );
  }

  return (
    <Virtuoso
      totalCount={items.length}
      overscan={400}
      itemContent={rowItemContent}
      style={{ height: '100%' }}
    />
  );
}

// ---------------------------------------------------------------------------
// Shared card props type
// ---------------------------------------------------------------------------

interface CardProps {
  item: ContentCardData;
  onClick: () => void;
  onFavoriteToggle?: () => void;
  isFavorite: boolean;
  isLocked: boolean;
  showLogo: boolean;
  nowNext?: { now?: { title?: string }; next?: { title?: string } };
  onLockToggle?: () => void;
  onHideChannel?: () => void;
}

// ---------------------------------------------------------------------------
// Grid card
// ---------------------------------------------------------------------------

function GridCard({
  item,
  onClick,
  onFavoriteToggle,
  isFavorite,
  isLocked,
  showLogo,
  nowNext,
  onLockToggle,
  onHideChannel,
}: CardProps) {
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null);

  const handleContextMenu = useCallback(
    (e: React.MouseEvent) => {
      if (!onLockToggle && !onHideChannel && !onFavoriteToggle) return;
      e.preventDefault();
      e.stopPropagation();
      setContextMenu({ x: e.clientX, y: e.clientY });
    },
    [onLockToggle, onHideChannel, onFavoriteToggle],
  );

  useEffect(() => {
    if (!contextMenu) return;
    const handler = () => setContextMenu(null);
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [contextMenu]);

  const nowTitle = nowNext?.now?.title;
  const nextTitle = nowNext?.next?.title;

  return (
    <>
      <button
        onClick={onClick}
        onContextMenu={handleContextMenu}
        className="group flex w-full flex-col overflow-hidden rounded-lg border border-surface-800 bg-surface-900 text-left transition-all hover:border-accent/50 hover:shadow-lg hover:shadow-accent/5 focus:outline-none focus:ring-2 focus:ring-accent/50"
      >
        <div className="relative aspect-video w-full overflow-hidden bg-surface-800">
          {showLogo && item.logoUrl ? (
            <img
              src={item.logoUrl}
              alt={item.title}
              className="h-full w-full object-contain p-2 transition-transform group-hover:scale-105"
              loading="lazy"
              onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
            />
          ) : (
            <div className="flex h-full w-full items-center justify-center">
              <span className="text-2xl font-bold text-surface-600">
                {(item.cleanTitle || item.title).charAt(0).toUpperCase()}
              </span>
            </div>
          )}

          {isLocked && (
            <div className="absolute left-1.5 top-1.5 flex h-6 w-6 items-center justify-center rounded-full bg-amber-500/90">
              <LockIcon size="sm" />
            </div>
          )}

          {onFavoriteToggle && (
            <button
              onClick={(e) => { e.stopPropagation(); onFavoriteToggle(); }}
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
          {item.groupName && !nowTitle && (
            <p className="mt-1 truncate text-xs text-surface-500">{item.groupName}</p>
          )}
          {nowTitle && (
            <p className="mt-1 truncate text-xs text-green-400" title={nowTitle}>{nowTitle}</p>
          )}
          {nextTitle && (
            <p className="truncate text-xs text-surface-500" title={nextTitle}>Next: {nextTitle}</p>
          )}
        </div>
      </button>

      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          isFavorite={isFavorite}
          isLocked={isLocked}
          onFavoriteToggle={onFavoriteToggle ? () => { onFavoriteToggle(); setContextMenu(null); } : undefined}
          onLockToggle={onLockToggle ? () => { onLockToggle(); setContextMenu(null); } : undefined}
          onHideChannel={onHideChannel ? () => { onHideChannel(); setContextMenu(null); } : undefined}
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// List row
// ---------------------------------------------------------------------------

function ListRow({
  item,
  onClick,
  onFavoriteToggle,
  isFavorite,
  isLocked,
  showLogo,
  nowNext,
  onLockToggle,
  onHideChannel,
}: CardProps) {
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null);

  const handleContextMenu = useCallback(
    (e: React.MouseEvent) => {
      if (!onLockToggle && !onHideChannel && !onFavoriteToggle) return;
      e.preventDefault();
      e.stopPropagation();
      setContextMenu({ x: e.clientX, y: e.clientY });
    },
    [onLockToggle, onHideChannel, onFavoriteToggle],
  );

  useEffect(() => {
    if (!contextMenu) return;
    const handler = () => setContextMenu(null);
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [contextMenu]);

  const nowTitle = nowNext?.now?.title;
  const nextTitle = nowNext?.next?.title;

  return (
    <>
      <button
        onClick={onClick}
        onContextMenu={handleContextMenu}
        className="group mb-1 flex w-full items-center gap-3 rounded-lg border border-surface-800 bg-surface-900 px-3 py-2.5 text-left transition-all hover:border-accent/50 hover:bg-surface-800/60 focus:outline-none focus:ring-2 focus:ring-accent/50"
      >
        <div className="relative h-10 w-16 flex-shrink-0 overflow-hidden rounded-md bg-surface-800">
          {showLogo && item.logoUrl ? (
            <img
              src={item.logoUrl}
              alt={item.title}
              className="h-full w-full object-contain p-1"
              loading="lazy"
              onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
            />
          ) : (
            <div className="flex h-full w-full items-center justify-center">
              <span className="text-sm font-bold text-surface-600">
                {(item.cleanTitle || item.title).charAt(0).toUpperCase()}
              </span>
            </div>
          )}
          {isLocked && (
            <div className="absolute left-0.5 top-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-amber-500/90">
              <LockIcon size="xs" />
            </div>
          )}
        </div>

        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-surface-200 group-hover:text-surface-100">
            {item.cleanTitle || item.title}
          </p>
          {item.groupName && (
            <p className="truncate text-xs text-surface-500">{item.groupName}</p>
          )}
        </div>

        {(nowTitle || nextTitle) && (
          <div className="hidden w-64 flex-shrink-0 text-right md:block">
            {nowTitle && (
              <p className="truncate text-xs text-green-400" title={nowTitle}>{nowTitle}</p>
            )}
            {nextTitle && (
              <p className="truncate text-xs text-surface-500" title={nextTitle}>Next: {nextTitle}</p>
            )}
          </div>
        )}

        {onFavoriteToggle && (
          <button
            onClick={(e) => { e.stopPropagation(); onFavoriteToggle(); }}
            className={`flex-shrink-0 rounded-full p-1 transition-colors ${
              isFavorite ? 'text-red-500' : 'text-surface-600 opacity-0 group-hover:opacity-100 hover:text-red-400'
            }`}
          >
            <HeartIcon filled={isFavorite} />
          </button>
        )}
      </button>

      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          isFavorite={isFavorite}
          isLocked={isLocked}
          onFavoriteToggle={onFavoriteToggle ? () => { onFavoriteToggle(); setContextMenu(null); } : undefined}
          onLockToggle={onLockToggle ? () => { onLockToggle(); setContextMenu(null); } : undefined}
          onHideChannel={onHideChannel ? () => { onHideChannel(); setContextMenu(null); } : undefined}
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Compact row
// ---------------------------------------------------------------------------

function CompactRow({
  item,
  onClick,
  onFavoriteToggle,
  isFavorite,
  isLocked,
  nowNext,
  onLockToggle,
  onHideChannel,
}: CardProps) {
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null);

  const handleContextMenu = useCallback(
    (e: React.MouseEvent) => {
      if (!onLockToggle && !onHideChannel && !onFavoriteToggle) return;
      e.preventDefault();
      e.stopPropagation();
      setContextMenu({ x: e.clientX, y: e.clientY });
    },
    [onLockToggle, onHideChannel, onFavoriteToggle],
  );

  useEffect(() => {
    if (!contextMenu) return;
    const handler = () => setContextMenu(null);
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [contextMenu]);

  const nowTitle = nowNext?.now?.title;

  return (
    <>
      <button
        onClick={onClick}
        onContextMenu={handleContextMenu}
        className="group flex w-full items-center gap-2.5 border-b border-surface-800/60 px-2 py-1.5 text-left transition-colors hover:bg-surface-800/40 focus:outline-none"
      >
        {isLocked && <LockIcon size="xs" className="flex-shrink-0 text-amber-500" />}

        <p className="min-w-0 flex-1 truncate text-sm text-surface-200 group-hover:text-surface-100">
          {item.cleanTitle || item.title}
        </p>

        {item.groupName && (
          <span className="hidden flex-shrink-0 text-xs text-surface-600 md:inline">
            {item.groupName}
          </span>
        )}

        {nowTitle && (
          <span className="hidden w-48 flex-shrink-0 truncate text-right text-xs text-green-400 md:inline">
            {nowTitle}
          </span>
        )}

        {onFavoriteToggle && (
          <button
            onClick={(e) => { e.stopPropagation(); onFavoriteToggle(); }}
            className={`flex-shrink-0 transition-colors ${
              isFavorite ? 'text-red-500' : 'text-surface-700 opacity-0 group-hover:opacity-100 hover:text-red-400'
            }`}
          >
            <HeartIcon filled={isFavorite} />
          </button>
        )}
      </button>

      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          isFavorite={isFavorite}
          isLocked={isLocked}
          onFavoriteToggle={onFavoriteToggle ? () => { onFavoriteToggle(); setContextMenu(null); } : undefined}
          onLockToggle={onLockToggle ? () => { onLockToggle(); setContextMenu(null); } : undefined}
          onHideChannel={onHideChannel ? () => { onHideChannel(); setContextMenu(null); } : undefined}
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Context Menu
// ---------------------------------------------------------------------------

function ContextMenu({
  x,
  y,
  isFavorite,
  isLocked,
  onFavoriteToggle,
  onLockToggle,
  onHideChannel,
}: {
  x: number;
  y: number;
  isFavorite: boolean;
  isLocked: boolean;
  onFavoriteToggle?: () => void;
  onLockToggle?: () => void;
  onHideChannel?: () => void;
}) {
  return (
    <div
      className="fixed z-50 min-w-[160px] overflow-hidden rounded-lg border border-surface-700 bg-surface-800 py-1 shadow-xl"
      style={{ left: x, top: y }}
    >
      {onFavoriteToggle && (
        <ContextMenuItem
          onClick={onFavoriteToggle}
          icon={<HeartIcon filled={isFavorite} />}
          label={isFavorite ? 'Remove Favorite' : 'Add Favorite'}
        />
      )}
      {onLockToggle && (
        <ContextMenuItem
          onClick={onLockToggle}
          icon={isLocked ? <UnlockIcon /> : <LockIcon size="sm" />}
          label={isLocked ? 'Unlock Channel' : 'Lock Channel'}
        />
      )}
      {onHideChannel && (
        <ContextMenuItem onClick={onHideChannel} icon={<EyeOffIcon />} label="Hide Channel" danger />
      )}
    </div>
  );
}

function ContextMenuItem({
  onClick,
  icon,
  label,
  danger,
}: {
  onClick: () => void;
  icon: React.ReactNode;
  label: string;
  danger?: boolean;
}) {
  return (
    <button
      onClick={(e) => { e.stopPropagation(); onClick(); }}
      className={`flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm transition-colors ${
        danger ? 'text-red-400 hover:bg-red-500/10' : 'text-surface-300 hover:bg-surface-700'
      }`}
    >
      <span className="flex h-4 w-4 items-center justify-center">{icon}</span>
      {label}
    </button>
  );
}

// ---------------------------------------------------------------------------
// Horizontal scrollable row — used for search result zones
// ---------------------------------------------------------------------------

interface HorizontalContentRowProps {
  items: ContentCardData[];
  onItemClick: (item: ContentCardData) => void;
  onFavoriteToggle?: (item: ContentCardData) => void;
  favoriteIds?: Set<string>;
}

export function HorizontalContentRow({
  items,
  onItemClick,
  onFavoriteToggle,
  favoriteIds,
}: HorizontalContentRowProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const showLogos = useSettingsStore((s) => s.getBool('ui_channel_logos'));

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (el.scrollWidth <= el.clientWidth) return;
      e.preventDefault();
      el.scrollLeft += e.deltaY || e.deltaX;
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, []);

  if (items.length === 0) return null;

  return (
    <div ref={scrollRef} className="overflow-x-auto overflow-y-hidden pb-2">
      <div className="flex w-max gap-3">
        {items.map((item) => (
          <div key={item.id} className="w-40 flex-shrink-0">
            <GridCard
              item={item}
              onClick={() => onItemClick(item)}
              onFavoriteToggle={onFavoriteToggle ? () => onFavoriteToggle(item) : undefined}
              isFavorite={favoriteIds ? favoriteIds.has(item.id) : false}
              isLocked={false}
              showLogo={showLogos}
            />
          </div>
        ))}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Icons
// ---------------------------------------------------------------------------

function HeartIcon({ filled }: { filled: boolean }) {
  return (
    <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12z" />
    </svg>
  );
}

function LockIcon({ size = 'sm', className = 'text-white' }: { size?: 'xs' | 'sm'; className?: string }) {
  const cls = size === 'xs' ? `h-2.5 w-2.5 ${className}` : `h-3 w-3 ${className}`;
  return (
    <svg className={cls} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5}>
      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </svg>
  );
}

function UnlockIcon() {
  return (
    <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
      <path d="M7 11V7a5 5 0 0 1 9.9-1" />
    </svg>
  );
}

function EyeOffIcon() {
  return (
    <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
      <line x1="1" y1="1" x2="23" y2="23" />
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
