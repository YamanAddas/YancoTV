import { useState, memo } from 'react';

const HEX_ROW_CLIP =
  'polygon(22px 0, calc(100% - 22px) 0, 100% 50%, calc(100% - 22px) 100%, 22px 100%, 0 50%)';
const HEX_BADGE_CLIP =
  'polygon(50% 0%, 100% 25%, 100% 75%, 50% 100%, 0% 75%, 0% 25%)';

export { HEX_ROW_CLIP };

export interface ChannelHexRowProps {
  title: string;
  cleanTitle?: string;
  groupName?: string;
  logoUrl?: string;
  isFavorite: boolean;
  isLocked: boolean;
  showLogo: boolean;
  nowPlaying?: string;
  nextProgram?: string;
  onClick: () => void;
  onFavoriteToggle?: () => void;
  onContextMenu?: (e: React.MouseEvent) => void;
  /** 'list' = full-width row; 'grid' = compact card for multi-column grid */
  variant?: 'list' | 'grid';
}

/* ── Helpers ─────────────────────────────────────────────────────────────── */

function extractQuality(title: string): string | null {
  if (/\b4K\b/i.test(title) || /\b2160p?\b/i.test(title) || /\bUHD\b/i.test(title))
    return '2160p';
  if (/\bFHD\b/i.test(title) || /\b1080p?\b/i.test(title)) return '1080p';
  if (/\b1680p?\b/i.test(title)) return '1680p';
  if (/\bHD\b/i.test(title) || /\b720p?\b/i.test(title)) return '720p';
  if (/\bSD\b/i.test(title) || /\b480p?\b/i.test(title)) return '480p';
  return null;
}

function extractBadgeText(title: string): string {
  const numMatch = title.match(/^(\d{1,4})\s*[\|\-.:]/);
  if (numMatch) return numMatch[1];
  const words = title
    .replace(/[^a-zA-Z\s]/g, '')
    .trim()
    .split(/\s+/)
    .filter(Boolean);
  if (words.length >= 2) return (words[0][0] + words[1][0]).toUpperCase();
  if (words.length === 1) return words[0].slice(0, 3).toUpperCase();
  return title.slice(0, 2).toUpperCase();
}

/* ── Component ───────────────────────────────────────────────────────────── */

export const ChannelHexRow = memo(function ChannelHexRow({
  title,
  cleanTitle,
  groupName,
  logoUrl,
  isFavorite,
  isLocked,
  showLogo,
  nowPlaying,
  nextProgram,
  onClick,
  onFavoriteToggle,
  onContextMenu,
  variant = 'list',
}: ChannelHexRowProps) {
  const [imgError, setImgError] = useState(false);
  const isGrid = variant === 'grid';
  const displayTitle = cleanTitle || title;
  const quality = extractQuality(title);
  const badgeText = extractBadgeText(displayTitle);
  const showImage = showLogo && logoUrl && !imgError;

  const rowHeight = isGrid ? '68px' : '80px';
  const badgeSize = isGrid ? '36px' : '44px';

  return (
    <button
      onClick={onClick}
      onContextMenu={onContextMenu}
      className={`hex-row group relative block w-full text-left focus:outline-none ${isGrid ? '' : 'mb-2.5'}`}
      style={{ height: rowHeight }}
    >
      {/* ── Outer glow (hover only) ──────────────────────────────────────── */}
      <div
        className="pointer-events-none absolute -inset-1.5 opacity-0 transition-opacity duration-300 group-hover:opacity-100"
        style={{
          clipPath: HEX_ROW_CLIP,
          background:
            'linear-gradient(90deg, rgba(0,255,170,0.1), rgba(89,240,230,0.05), rgba(0,255,170,0.1))',
          filter: 'blur(10px)',
        }}
      />

      {/* ── Border frame ─────────────────────────────────────────────────── */}
      <div
        className="pointer-events-none absolute inset-0 opacity-50 transition-opacity duration-300 group-hover:opacity-100"
        style={{
          clipPath: HEX_ROW_CLIP,
          background:
            'linear-gradient(90deg, rgba(0,255,170,0.4), rgba(89,240,230,0.08), rgba(0,255,170,0.15), rgba(89,240,230,0.08), rgba(0,255,170,0.4))',
        }}
      />

      {/* ── Inner panel ──────────────────────────────────────────────────── */}
      <div
        className="absolute"
        style={{
          inset: '1.5px',
          clipPath: HEX_ROW_CLIP,
          background: 'linear-gradient(145deg, rgba(10,16,22,0.94), rgba(6,10,16,0.98))',
        }}
      >
        {/* Subtle top-left sheen */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              'linear-gradient(160deg, rgba(0,255,170,0.035) 0%, transparent 22%, transparent 100%)',
          }}
        />

        {/* ── Content row ────────────────────────────────────────────────── */}
        <div
          className={`relative flex h-full items-center ${
            isGrid ? 'gap-2.5 pl-6 pr-4' : 'gap-3.5 pl-8 pr-6'
          }`}
        >
          {/* ── Hex badge ──────────────────────────────────────────────── */}
          <div
            className="relative flex-shrink-0"
            style={{ width: badgeSize, height: badgeSize }}
          >
            {/* Badge glow */}
            <div
              className="pointer-events-none absolute -inset-1.5 opacity-30 transition-opacity duration-300 group-hover:opacity-60"
              style={{
                clipPath: HEX_BADGE_CLIP,
                background: 'rgba(0,255,170,0.3)',
                filter: 'blur(6px)',
              }}
            />
            {/* Badge border */}
            <div
              className="absolute inset-0"
              style={{
                clipPath: HEX_BADGE_CLIP,
                background:
                  'linear-gradient(180deg, rgba(0,255,170,0.5), rgba(89,240,230,0.2))',
              }}
            />
            {/* Badge fill */}
            <div
              className="absolute flex items-center justify-center overflow-hidden"
              style={{
                inset: '1.5px',
                clipPath: HEX_BADGE_CLIP,
                background: 'rgba(6,10,16,0.95)',
              }}
            >
              {showImage ? (
                <img
                  src={logoUrl}
                  alt=""
                  className="h-full w-full object-cover"
                  loading="lazy"
                  onError={() => setImgError(true)}
                />
              ) : (
                <span
                  className={`font-bold tracking-wide text-emerald-400/80 ${
                    isGrid ? 'text-[10px]' : 'text-[11px]'
                  }`}
                >
                  {badgeText}
                </span>
              )}
            </div>
            {/* Lock overlay */}
            {isLocked && (
              <div
                className="absolute inset-0 flex items-center justify-center"
                style={{ clipPath: HEX_BADGE_CLIP, background: 'rgba(0,0,0,0.6)' }}
              >
                <svg
                  className={`text-amber-400/80 ${isGrid ? 'h-3 w-3' : 'h-3.5 w-3.5'}`}
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2.5}
                >
                  <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                  <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                </svg>
              </div>
            )}
          </div>

          {/* ── Title + metadata tags ─────────────────────────────────── */}
          <div className="min-w-0 flex-1">
            <p
              className={`hex-row-title truncate font-semibold uppercase tracking-wide text-surface-100 transition-colors duration-200 group-hover:text-accent ${
                isGrid ? 'text-[13px]' : 'text-[14px]'
              }`}
            >
              {displayTitle}
            </p>
            <div className="mt-0.5 flex flex-wrap items-center gap-1">
              {quality && (
                <span
                  className={`inline-flex items-center rounded px-1.5 py-px font-semibold text-emerald-400 ${
                    isGrid ? 'text-[9px]' : 'text-[10px]'
                  }`}
                  style={{ background: 'rgba(0,255,170,0.1)' }}
                >
                  {quality}
                </span>
              )}
              {groupName &&
                groupName.split(/\s*[/|]\s*/).map((tag, i) => (
                  <span
                    key={i}
                    className={`inline-flex items-center rounded px-1.5 py-px text-surface-400 ${
                      isGrid ? 'text-[9px]' : 'text-[10px]'
                    }`}
                    style={{ background: 'rgba(255,255,255,0.04)' }}
                  >
                    {tag}
                  </span>
                ))}
            </div>
          </div>

          {/* ── EPG now / next (list only) ────────────────────────────── */}
          {!isGrid && (nowPlaying || nextProgram) && (
            <div className="hidden w-52 flex-shrink-0 text-right lg:block">
              {nowPlaying && (
                <p
                  className="truncate text-xs font-medium text-green-400"
                  title={nowPlaying}
                >
                  {nowPlaying}
                </p>
              )}
              {nextProgram && (
                <p
                  className="mt-0.5 truncate text-[11px] text-surface-500"
                  title={nextProgram}
                >
                  Next: {nextProgram}
                </p>
              )}
            </div>
          )}

          {/* ── Decorative chevrons (list only) ──────────────────────── */}
          {!isGrid && (
            <div className="flex flex-shrink-0 items-center gap-px opacity-20 transition-opacity duration-300 group-hover:opacity-50">
              <svg width="10" height="24" viewBox="0 0 10 24" fill="none">
                <path
                  d="M2 2L8 12L2 22"
                  stroke="rgba(0,255,170,0.7)"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                />
              </svg>
              <svg width="10" height="24" viewBox="0 0 10 24" fill="none">
                <path
                  d="M2 4L7 12L2 20"
                  stroke="rgba(0,255,170,0.45)"
                  strokeWidth="1"
                  strokeLinecap="round"
                />
              </svg>
              <svg width="8" height="24" viewBox="0 0 8 24" fill="none">
                <path
                  d="M2 6L6 12L2 18"
                  stroke="rgba(0,255,170,0.25)"
                  strokeWidth="0.75"
                  strokeLinecap="round"
                />
              </svg>
            </div>
          )}

          {/* ── Favorite star ─────────────────────────────────────────── */}
          {onFavoriteToggle && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onFavoriteToggle();
              }}
              className={`flex flex-shrink-0 items-center justify-center transition-all duration-200 ${
                isGrid ? 'h-6 w-6' : 'h-7 w-7'
              } ${
                isFavorite
                  ? 'text-yellow-400 drop-shadow-[0_0_6px_rgba(250,204,21,0.4)]'
                  : 'text-surface-600 opacity-0 group-hover:opacity-100 hover:text-yellow-400'
              }`}
              title={isFavorite ? 'Remove from favorites' : 'Add to favorites'}
            >
              <svg
                className={isGrid ? 'h-3.5 w-3.5' : 'h-4 w-4'}
                viewBox="0 0 24 24"
                fill={isFavorite ? 'currentColor' : 'none'}
                stroke="currentColor"
                strokeWidth={2}
              >
                <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
              </svg>
            </button>
          )}
        </div>
      </div>
    </button>
  );
});
