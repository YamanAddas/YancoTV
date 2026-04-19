import { VirtuosoGrid, Virtuoso } from 'react-virtuoso';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
  rectSortingStrategy,
  useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useSettingsStore } from '../stores/settings-store';
import { HexCard } from './HexCard';
import { PosterCard } from './PosterCard';
import { ChannelHexRow } from './ChannelHexRow';
import { prettifyGroupName } from '@yancotv/core';
import type { NowNextMap } from '../../shared/types/epg';

/**
 * Maximum items the reorder path will render non-virtualized. Over this size
 * the list falls back to the virtualized view (drag disabled) to keep paint
 * time reasonable. Callers (e.g. LiveTvPage) use this to show a hint when a
 * filter selection is too broad to reorder.
 */
export const REORDER_ITEM_CAP = 15000;

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
  onRecord?: (item: ContentCardData) => void;
  /** Override list style — defaults to the global ui_list_style setting */
  viewMode?: 'grid' | 'list' | 'compact';
  /** 'channel' = compact hex rows (Live TV); 'poster' = tall hex artwork cards (Movies/Series) */
  cardStyle?: 'channel' | 'poster';
  /** When true, list view becomes non-virtualized and drag-reorderable */
  reorderable?: boolean;
  /** Called when user drops a row; receives the new ordered list of ids */
  onReorder?: (ids: string[]) => void;
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
  onRecord,
  viewMode: viewModeProp,
  cardStyle = 'channel',
  reorderable = false,
  onReorder,
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
        <HexGridItem
          item={item}
          onClick={() => onItemClick(item)}
          onFavoriteToggle={onFavoriteToggle ? () => onFavoriteToggle(item) : undefined}
          isFavorite={favoriteIds ? favoriteIds.has(item.id) : false}
          isLocked={lockedIds ? lockedIds.has(item.id) : false}
          showLogo={showLogos}
          nowNext={nowNext}
          onLockToggle={onLockToggle ? () => onLockToggle(item) : undefined}
          onHideChannel={onHideChannel ? () => onHideChannel(item) : undefined}
          onRecord={onRecord ? () => onRecord(item) : undefined}
          cardStyle={cardStyle}
        />
      );
    },
    [items, onItemClick, onFavoriteToggle, favoriteIds, lockedIds, nowNextMap, showLogos, onLockToggle, onHideChannel, onRecord, cardStyle],
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
        onRecord: onRecord ? () => onRecord(item) : undefined,
      };
      return viewMode === 'compact' ? <CompactRow {...props} /> : <ListRow {...props} />;
    },
    [items, viewMode, onItemClick, onFavoriteToggle, favoriteIds, lockedIds, nowNextMap, showLogos, onLockToggle, onHideChannel, onRecord],
  );
  // ── End hooks ─────────────────────────────────────────────────────────────

  if (isLoading) return <SkeletonGrid cardStyle={cardStyle} />;
  if (items.length === 0) return null;

  // Reorderable — non-virtualized, covers both list and grid views. Capped
  // so the DOM doesn't balloon past what the browser can paint smoothly; over
  // the cap we fall back to the virtualized view (drag disabled).
  if (reorderable && onReorder && items.length <= REORDER_ITEM_CAP && viewMode !== 'compact') {
    return (
      <ReorderableChannels
        items={items}
        viewMode={viewMode}
        cardStyle={cardStyle}
        onItemClick={onItemClick}
        onFavoriteToggle={onFavoriteToggle}
        favoriteIds={favoriteIds}
        lockedIds={lockedIds}
        nowNextMap={nowNextMap}
        showLogos={showLogos}
        onLockToggle={onLockToggle}
        onHideChannel={onHideChannel}
        onRecord={onRecord}
        onReorder={onReorder}
      />
    );
  }

  if (viewMode === 'grid') {
    const listCls = cardStyle === 'poster' ? 'poster-grid' : 'hex-card-grid';
    const itemCls = cardStyle === 'poster' ? 'poster-grid-item' : 'hex-card-grid-item';
    return (
      <VirtuosoGrid
        totalCount={items.length}
        overscan={600}
        listClassName={listCls}
        itemClassName={itemCls}
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
// ReorderableChannelList — non-virtualized list with drag handles
// ---------------------------------------------------------------------------

interface ReorderableProps {
  items: ContentCardData[];
  viewMode: 'grid' | 'list' | 'compact';
  cardStyle: 'channel' | 'poster';
  onItemClick: (item: ContentCardData) => void;
  onFavoriteToggle?: (item: ContentCardData) => void;
  favoriteIds?: Set<string>;
  lockedIds?: Set<string>;
  nowNextMap?: NowNextMap;
  showLogos: boolean;
  onLockToggle?: (item: ContentCardData) => void;
  onHideChannel?: (item: ContentCardData) => void;
  onRecord?: (item: ContentCardData) => void;
  onReorder: (ids: string[]) => void;
}

function ReorderableChannels({
  items,
  viewMode,
  cardStyle,
  onItemClick,
  onFavoriteToggle,
  favoriteIds,
  lockedIds,
  nowNextMap,
  showLogos,
  onLockToggle,
  onHideChannel,
  onRecord,
  onReorder,
}: ReorderableProps) {
  // Reorder is an explicit mode (toggle in UI), so drag activates immediately
  // on any movement. Clicks still work because dnd-kit suppresses click when
  // distance >= activation threshold.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      if (!over || active.id === over.id) return;
      const ids = items.map((i) => i.id);
      const oldIdx = ids.indexOf(active.id as string);
      const newIdx = ids.indexOf(over.id as string);
      if (oldIdx === -1 || newIdx === -1) return;
      const next = [...ids];
      next.splice(oldIdx, 1);
      next.splice(newIdx, 0, active.id as string);
      onReorder(next);
    },
    [items, onReorder],
  );

  const itemIds = items.map((i) => i.id);

  if (viewMode === 'list') {
    return (
      <div className="h-full overflow-y-auto">
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          <SortableContext items={itemIds} strategy={verticalListSortingStrategy}>
            <div className="space-y-1 px-1 pb-4">
              {items.map((item) => {
                const nowNext = item.tvgId && nowNextMap ? nowNextMap[item.tvgId] : undefined;
                return (
                  <SortableChannelRow
                    key={item.id}
                    item={item}
                    onClick={() => onItemClick(item)}
                    onFavoriteToggle={onFavoriteToggle ? () => onFavoriteToggle(item) : undefined}
                    isFavorite={favoriteIds ? favoriteIds.has(item.id) : false}
                    isLocked={lockedIds ? lockedIds.has(item.id) : false}
                    showLogo={showLogos}
                    nowNext={nowNext}
                    onLockToggle={onLockToggle ? () => onLockToggle(item) : undefined}
                    onHideChannel={onHideChannel ? () => onHideChannel(item) : undefined}
                    onRecord={onRecord ? () => onRecord(item) : undefined}
                  />
                );
              })}
            </div>
          </SortableContext>
        </DndContext>
      </div>
    );
  }

  // Grid view (channel hex rows or poster cards)
  const listCls = cardStyle === 'poster' ? 'poster-grid' : 'hex-card-grid';
  const itemCls = cardStyle === 'poster' ? 'poster-grid-item' : 'hex-card-grid-item';

  return (
    <div className="h-full overflow-y-auto">
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext items={itemIds} strategy={rectSortingStrategy}>
          <div className={listCls}>
            {items.map((item) => {
              const nowNext = item.tvgId && nowNextMap ? nowNextMap[item.tvgId] : undefined;
              return (
                <SortableGridItem key={item.id} id={item.id} className={itemCls}>
                  <HexGridItem
                    item={item}
                    onClick={() => onItemClick(item)}
                    onFavoriteToggle={onFavoriteToggle ? () => onFavoriteToggle(item) : undefined}
                    isFavorite={favoriteIds ? favoriteIds.has(item.id) : false}
                    isLocked={lockedIds ? lockedIds.has(item.id) : false}
                    showLogo={showLogos}
                    nowNext={nowNext}
                    onLockToggle={onLockToggle ? () => onLockToggle(item) : undefined}
                    onHideChannel={onHideChannel ? () => onHideChannel(item) : undefined}
                    onRecord={onRecord ? () => onRecord(item) : undefined}
                    cardStyle={cardStyle}
                  />
                </SortableGridItem>
              );
            })}
          </div>
        </SortableContext>
      </DndContext>
    </div>
  );
}

function SortableGridItem({
  id,
  className,
  children,
}: {
  id: string;
  className: string;
  children: React.ReactNode;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id,
  });
  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.4 : 1,
    zIndex: isDragging ? 30 : undefined,
    touchAction: 'manipulation',
  };
  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      {...listeners}
      className={`relative cursor-grab select-none active:cursor-grabbing ${className}`}
    >
      {/* Blocks clicks on the inner hex so drag is the only thing that fires */}
      <div aria-hidden className="absolute inset-0 z-10" />
      {children}
      {/* Always-visible grip badge in reorder mode */}
      <div
        aria-hidden
        className="pointer-events-none absolute left-1 top-1 z-20 flex h-5 w-5 items-center justify-center rounded-md bg-accent/20 text-accent shadow-sm backdrop-blur-sm"
      >
        <svg className="h-3 w-3" viewBox="0 0 10 16" fill="currentColor">
          <circle cx="3" cy="2" r="1.2" />
          <circle cx="7" cy="2" r="1.2" />
          <circle cx="3" cy="6" r="1.2" />
          <circle cx="7" cy="6" r="1.2" />
          <circle cx="3" cy="10" r="1.2" />
          <circle cx="7" cy="10" r="1.2" />
          <circle cx="3" cy="14" r="1.2" />
          <circle cx="7" cy="14" r="1.2" />
        </svg>
      </div>
    </div>
  );
}

function SortableChannelRow(props: CardProps) {
  const { item } = props;
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: item.id,
  });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
    zIndex: isDragging ? 30 : 'auto' as string | number,
  };

  return (
    <div ref={setNodeRef} style={style} className="group/row flex items-stretch gap-1">
      <button
        {...attributes}
        {...listeners}
        tabIndex={-1}
        className="flex w-4 flex-shrink-0 cursor-grab items-center justify-center text-surface-600 opacity-0 transition-opacity group-hover/row:opacity-100 active:cursor-grabbing"
        title="Drag to reorder"
      >
        <svg className="h-3 w-3" viewBox="0 0 10 16" fill="currentColor">
          <circle cx="3" cy="2" r="1.2" />
          <circle cx="7" cy="2" r="1.2" />
          <circle cx="3" cy="6" r="1.2" />
          <circle cx="7" cy="6" r="1.2" />
          <circle cx="3" cy="10" r="1.2" />
          <circle cx="7" cy="10" r="1.2" />
          <circle cx="3" cy="14" r="1.2" />
          <circle cx="7" cy="14" r="1.2" />
        </svg>
      </button>
      <div className="min-w-0 flex-1">
        <ListRow {...props} />
      </div>
    </div>
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
  onRecord?: () => void;
}

// ---------------------------------------------------------------------------
// Hex grid item — wraps HexCard with context menu
// ---------------------------------------------------------------------------

function HexGridItem({
  item,
  onClick,
  onFavoriteToggle,
  isFavorite,
  isLocked,
  showLogo,
  nowNext,
  onLockToggle,
  onHideChannel,
  onRecord,
  cardStyle = 'channel',
}: CardProps & { cardStyle?: 'channel' | 'poster' }) {
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null);

  const handleContextMenu = useCallback(
    (e: React.MouseEvent) => {
      if (!onLockToggle && !onHideChannel && !onFavoriteToggle && !onRecord) return;
      e.preventDefault();
      e.stopPropagation();
      setContextMenu({ x: e.clientX, y: e.clientY });
    },
    [onLockToggle, onHideChannel, onFavoriteToggle, onRecord],
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
      {cardStyle === 'poster' ? (
        <PosterCard
          title={item.cleanTitle || item.title}
          imageUrl={showLogo ? item.logoUrl : undefined}
          isFavorite={isFavorite}
          isLocked={isLocked}
          onClick={onClick}
          onFavoriteToggle={onFavoriteToggle}
          onContextMenu={handleContextMenu}
        />
      ) : (
        <ChannelHexRow
          title={item.title}
          cleanTitle={item.cleanTitle}
          groupName={prettifyGroupName(item.groupName)}
          logoUrl={item.logoUrl}
          isFavorite={isFavorite}
          isLocked={isLocked}
          showLogo={showLogo}
          nowPlaying={nowTitle}
          onClick={onClick}
          onFavoriteToggle={onFavoriteToggle}
          onContextMenu={handleContextMenu}
          variant="grid"
        />
      )}

      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          isFavorite={isFavorite}
          isLocked={isLocked}
          onFavoriteToggle={onFavoriteToggle ? () => { onFavoriteToggle(); setContextMenu(null); } : undefined}
          onLockToggle={onLockToggle ? () => { onLockToggle(); setContextMenu(null); } : undefined}
          onHideChannel={onHideChannel ? () => { onHideChannel(); setContextMenu(null); } : undefined}
          onRecord={onRecord ? () => { onRecord(); setContextMenu(null); } : undefined}
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// List row — glass styled
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
  onRecord,
}: CardProps) {
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null);

  const handleContextMenu = useCallback(
    (e: React.MouseEvent) => {
      if (!onLockToggle && !onHideChannel && !onFavoriteToggle && !onRecord) return;
      e.preventDefault();
      e.stopPropagation();
      setContextMenu({ x: e.clientX, y: e.clientY });
    },
    [onLockToggle, onHideChannel, onFavoriteToggle, onRecord],
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
      <ChannelHexRow
        title={item.title}
        cleanTitle={item.cleanTitle}
        groupName={prettifyGroupName(item.groupName)}
        logoUrl={item.logoUrl}
        isFavorite={isFavorite}
        isLocked={isLocked}
        showLogo={showLogo}
        nowPlaying={nowTitle}
        nextProgram={nextTitle}
        onClick={onClick}
        onFavoriteToggle={onFavoriteToggle}
        onContextMenu={handleContextMenu}
      />

      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          isFavorite={isFavorite}
          isLocked={isLocked}
          onFavoriteToggle={onFavoriteToggle ? () => { onFavoriteToggle(); setContextMenu(null); } : undefined}
          onLockToggle={onLockToggle ? () => { onLockToggle(); setContextMenu(null); } : undefined}
          onHideChannel={onHideChannel ? () => { onHideChannel(); setContextMenu(null); } : undefined}
          onRecord={onRecord ? () => { onRecord(); setContextMenu(null); } : undefined}
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Compact row — glass styled
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
  onRecord,
}: CardProps) {
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null);

  const handleContextMenu = useCallback(
    (e: React.MouseEvent) => {
      if (!onLockToggle && !onHideChannel && !onFavoriteToggle && !onRecord) return;
      e.preventDefault();
      e.stopPropagation();
      setContextMenu({ x: e.clientX, y: e.clientY });
    },
    [onLockToggle, onHideChannel, onFavoriteToggle, onRecord],
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
        className="group flex w-full items-center gap-2.5 border-b border-accent/5 px-2 py-1.5 text-left transition-colors hover:bg-surface-800/30 focus:outline-none"
      >
        {isLocked && <LockIcon size="xs" className="flex-shrink-0 text-amber-500" />}

        <p className="min-w-0 flex-1 truncate text-sm text-surface-200 transition-colors group-hover:text-accent">
          {item.cleanTitle || item.title}
        </p>

        {item.groupName && (
          <span
            className="hidden flex-shrink-0 text-xs text-surface-600 md:inline"
            title={item.groupName}
          >
            {prettifyGroupName(item.groupName)}
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
          onRecord={onRecord ? () => { onRecord(); setContextMenu(null); } : undefined}
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Context Menu — glass styled
// ---------------------------------------------------------------------------

function ContextMenu({
  x,
  y,
  isFavorite,
  isLocked,
  onFavoriteToggle,
  onLockToggle,
  onHideChannel,
  onRecord,
}: {
  x: number;
  y: number;
  isFavorite: boolean;
  isLocked: boolean;
  onFavoriteToggle?: () => void;
  onLockToggle?: () => void;
  onHideChannel?: () => void;
  onRecord?: () => void;
}) {
  return (
    <div
      className="glass-strong fixed z-50 min-w-[160px] overflow-hidden rounded-xl py-1 shadow-glass"
      style={{ left: x, top: y }}
    >
      {onFavoriteToggle && (
        <ContextMenuItem
          onClick={onFavoriteToggle}
          icon={<HeartIcon filled={isFavorite} />}
          label={isFavorite ? 'Remove Favorite' : 'Add Favorite'}
        />
      )}
      {onRecord && (
        <ContextMenuItem
          onClick={onRecord}
          icon={<RecordIcon />}
          label="Record"
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

function RecordIcon() {
  return (
    <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 24 24">
      <circle cx="12" cy="12" r="6" />
    </svg>
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
        danger ? 'text-red-400 hover:bg-red-500/10' : 'text-surface-300 hover:bg-accent/10 hover:text-accent'
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
      <div className="flex w-max gap-2">
        {items.map((item) => (
          <div key={item.id} className="w-[120px] flex-shrink-0">
            <HexCard
              title={item.cleanTitle || item.title}
              subtitle={prettifyGroupName(item.groupName)}
              imageUrl={showLogos ? item.logoUrl : undefined}
              fallbackLetter={(item.cleanTitle || item.title).charAt(0).toUpperCase()}
              isFavorite={favoriteIds ? favoriteIds.has(item.id) : false}
              onClick={() => onItemClick(item)}
              onFavoriteToggle={onFavoriteToggle ? () => onFavoriteToggle(item) : undefined}
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

function SkeletonGrid({ cardStyle = 'channel' }: { cardStyle?: 'channel' | 'poster' }) {
  if (cardStyle === 'poster') {
    return (
      <div className="poster-grid">
        {Array.from({ length: 18 }).map((_, i) => (
          <div key={i} className="poster-grid-item">
            <div className="w-full">
              <div
                className="animate-pulse rounded-md bg-surface-800/40"
                style={{ aspectRatio: '2 / 3' }}
              />
              <div className="mt-2 h-4 w-3/4 animate-pulse rounded bg-surface-800/30" />
              <div className="mt-1.5 h-2.5 w-1/3 animate-pulse rounded bg-surface-800/20" />
            </div>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="hex-card-grid">
      {Array.from({ length: 12 }).map((_, i) => (
        <div key={i} className="hex-card-grid-item">
          <div
            className="h-[68px] animate-pulse"
            style={{
              clipPath:
                'polygon(22px 0, calc(100% - 22px) 0, 100% 50%, calc(100% - 22px) 100%, 22px 100%, 0 50%)',
              background: 'rgba(var(--surface-800), 0.3)',
            }}
          />
        </div>
      ))}
    </div>
  );
}
