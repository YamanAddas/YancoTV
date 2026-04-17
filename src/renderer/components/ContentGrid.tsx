import { VirtuosoGrid, Virtuoso } from 'react-virtuoso';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useSettingsStore } from '../stores/settings-store';
import { HexCard } from './HexCard';
import { ChannelHexRow } from './ChannelHexRow';
import { prettifyGroupName } from '../utils/group-parser';
import hexFrameSrc from '../assets/hex-frames/hex-frame.svg';
import hexFrameLockedSrc from '../assets/hex-frames/hex-frame-locked.svg';
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
  /** 'channel' = compact hex rows (Live TV); 'poster' = tall hex artwork cards (Movies/Series) */
  cardStyle?: 'channel' | 'poster';
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
  cardStyle = 'channel',
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
          cardStyle={cardStyle}
        />
      );
    },
    [items, onItemClick, onFavoriteToggle, favoriteIds, lockedIds, nowNextMap, showLogos, onLockToggle, onHideChannel, cardStyle],
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

  if (isLoading) return <SkeletonGrid cardStyle={cardStyle} />;
  if (items.length === 0) return null;

  if (viewMode === 'grid') {
    const listCls = cardStyle === 'poster' ? 'hex-grid' : 'hex-card-grid';
    const itemCls = cardStyle === 'poster' ? 'hex-grid-item' : 'hex-card-grid-item';
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
  cardStyle = 'channel',
}: CardProps & { cardStyle?: 'channel' | 'poster' }) {
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
      {cardStyle === 'poster' ? (
        <HexCard
          title={item.cleanTitle || item.title}
          subtitle={prettifyGroupName(item.groupName)}
          imageUrl={showLogo ? item.logoUrl : undefined}
          fallbackLetter={(item.cleanTitle || item.title).charAt(0).toUpperCase()}
          isFavorite={isFavorite}
          isLocked={isLocked}
          nowPlaying={nowTitle}
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
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// HexThumb — pure visual hex (div, not button) for use inside other buttons
// ---------------------------------------------------------------------------

function HexThumb({
  imageUrl,
  letter,
  isLocked,
}: {
  imageUrl?: string;
  letter: string;
  isLocked?: boolean;
}) {
  const [imgError, setImgError] = useState(false);
  const showImage = imageUrl && !imgError;

  return (
    <div className="relative w-full" style={{ aspectRatio: '200 / 230' }}>
      {/* Glow */}
      <img
        src={isLocked ? hexFrameLockedSrc : hexFrameSrc}
        alt=""
        aria-hidden
        className="pointer-events-none absolute inset-[-12%] h-[124%] w-[124%]"
        style={{ filter: 'blur(8px)', opacity: 0.25 }}
      />
      {/* Frame */}
      <img
        src={isLocked ? hexFrameLockedSrc : hexFrameSrc}
        alt=""
        aria-hidden
        className="pointer-events-none absolute inset-0 h-full w-full"
      />
      {/* Content */}
      <div className="absolute inset-[7%] clip-hex-tall overflow-hidden">
        {showImage ? (
          <img
            src={imageUrl}
            alt=""
            className="h-full w-full object-cover"
            loading="lazy"
            onError={() => setImgError(true)}
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center bg-surface-900/90">
            <span className="text-xs font-bold text-surface-500">{letter}</span>
          </div>
        )}
        {/* Vignette */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{ background: 'radial-gradient(ellipse at 50% 40%, transparent 35%, rgba(0,0,0,0.55) 100%)' }}
        />
        {/* Glass shine */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{ background: 'linear-gradient(170deg, rgba(255,255,255,0.08) 0%, transparent 35%)' }}
        />
        {isLocked && (
          <div className="absolute inset-0 flex items-center justify-center bg-surface-950/60">
            <svg className="h-4 w-4 text-amber-400/80" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5}>
              <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
              <path d="M7 11V7a5 5 0 0 1 10 0v4" />
            </svg>
          </div>
        )}
      </div>
    </div>
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
      <div className="hex-grid">
        {Array.from({ length: 18 }).map((_, i) => (
          <div key={i} className="hex-grid-item">
            <div className="relative w-full animate-pulse" style={{ aspectRatio: '200 / 230' }}>
              <div
                className="absolute inset-[7%]"
                style={{
                  clipPath: 'polygon(50% 3.5%, 94% 26%, 94% 74%, 50% 96.5%, 6% 74%, 6% 26%)',
                  background: 'rgba(var(--surface-800), 0.4)',
                }}
              />
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
