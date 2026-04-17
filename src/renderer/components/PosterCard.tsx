import { useState } from 'react';
import { motion } from 'motion/react';

// ---------------------------------------------------------------------------
// PosterCard — art-forward card for Movies & Series.
// 2:3 aspect, full-bleed artwork, serif title below.
// ---------------------------------------------------------------------------

export interface PosterCardProps {
  title: string;
  imageUrl?: string;
  /** e.g. "2024" or "8 Seasons" */
  meta?: string;
  rating?: string;
  isFavorite?: boolean;
  isLocked?: boolean;
  onClick?: () => void;
  onFavoriteToggle?: () => void;
  onContextMenu?: (e: React.MouseEvent) => void;
}

// Pull a year out of a title like "The Matrix (1999)" or "Inception 2010"
function extractYear(title: string): string | null {
  const paren = title.match(/\((\d{4})\)/);
  if (paren) return paren[1];
  const trailing = title.match(/\b(19|20)\d{2}\b/);
  return trailing ? trailing[0] : null;
}

// Strip "(2010)" or trailing year from title for cleaner display
function stripYear(title: string): string {
  return title
    .replace(/\s*\(\d{4}\)\s*$/, '')
    .replace(/\s+(19|20)\d{2}\s*$/, '')
    .trim();
}

export function PosterCard({
  title,
  imageUrl,
  meta,
  rating,
  isFavorite = false,
  isLocked = false,
  onClick,
  onFavoriteToggle,
  onContextMenu,
}: PosterCardProps) {
  const [hovered, setHovered] = useState(false);
  const [imgError, setImgError] = useState(false);

  const showImage = !!imageUrl && !imgError;
  const year = meta ?? extractYear(title) ?? undefined;
  const displayTitle = stripYear(title);
  const fallbackLetter = displayTitle.charAt(0).toUpperCase();

  return (
    <motion.button
      className="group relative flex w-full flex-col items-stretch focus:outline-none"
      onClick={onClick}
      onContextMenu={onContextMenu}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      whileHover={{ y: -4 }}
      transition={{ type: 'spring', stiffness: 400, damping: 28 }}
    >
      {/* ── Poster frame ─────────────────────────────────────────────── */}
      <div
        className={`relative w-full overflow-hidden rounded-md bg-surface-900 shadow-lg transition-shadow duration-300 ${
          hovered && !isLocked ? 'shadow-glow' : ''
        }`}
        style={{ aspectRatio: '2 / 3' }}
      >
        {/* Artwork */}
        {showImage ? (
          <img
            src={imageUrl}
            alt={title}
            className={`h-full w-full object-cover transition-transform duration-500 ${
              hovered ? 'scale-[1.04]' : 'scale-100'
            }`}
            loading="lazy"
            onError={() => setImgError(true)}
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-surface-800 to-surface-950">
            <span className="font-serif text-5xl italic text-surface-600">{fallbackLetter}</span>
          </div>
        )}

        {/* Accent ring on hover */}
        <div
          className="pointer-events-none absolute inset-0 rounded-md ring-1 ring-inset transition-all duration-300"
          style={{
            boxShadow: hovered && !isLocked ? 'inset 0 0 0 1px rgba(0,255,170,0.4)' : 'inset 0 0 0 1px rgba(255,255,255,0.04)',
          }}
        />

        {/* Bottom metadata gradient — fades in on hover */}
        <div
          className="pointer-events-none absolute inset-x-0 bottom-0 p-3 transition-opacity duration-300"
          style={{
            opacity: hovered ? 1 : 0,
            background:
              'linear-gradient(to top, rgba(3,6,14,0.95) 0%, rgba(3,6,14,0.7) 40%, transparent 100%)',
          }}
        >
          {(year || rating) && (
            <div className="flex items-center gap-2 font-mono text-[10px] uppercase tracking-widest-plus text-surface-200">
              {year && <span className="tabular-nums">{year}</span>}
              {year && rating && <span className="text-surface-500">•</span>}
              {rating && (
                <span className="inline-flex items-center gap-1 text-accent">
                  <svg className="h-2.5 w-2.5" viewBox="0 0 20 20" fill="currentColor">
                    <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                  </svg>
                  {rating}
                </span>
              )}
            </div>
          )}
        </div>

        {/* Favorite button */}
        {onFavoriteToggle && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              onFavoriteToggle();
            }}
            className={`absolute right-2 top-2 z-10 flex h-7 w-7 items-center justify-center rounded-full backdrop-blur-sm transition-all ${
              isFavorite
                ? 'bg-red-500/90 text-white opacity-100'
                : 'bg-surface-950/50 text-surface-300 opacity-0 group-hover:opacity-100 hover:text-red-400'
            }`}
            title={isFavorite ? 'Remove from favorites' : 'Add to favorites'}
          >
            <svg
              className="h-3.5 w-3.5"
              viewBox="0 0 24 24"
              fill={isFavorite ? 'currentColor' : 'none'}
              stroke="currentColor"
              strokeWidth={2}
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12z"
              />
            </svg>
          </button>
        )}

        {/* Locked badge */}
        {isLocked && (
          <div className="absolute inset-0 flex items-center justify-center bg-surface-950/70">
            <svg
              className="h-8 w-8 text-amber-400/80"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
            >
              <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
              <path d="M7 11V7a5 5 0 0 1 10 0v4" />
            </svg>
          </div>
        )}
      </div>

      {/* ── Title below poster — serif ───────────────────────────────── */}
      <div className="mt-2 px-0.5 text-left">
        <p
          className={`line-clamp-2 font-serif text-[15px] leading-tight transition-colors ${
            hovered ? 'text-accent' : 'text-surface-100'
          }`}
        >
          {displayTitle}
        </p>
        {year && (
          <p className="mt-0.5 font-mono text-[10px] uppercase tracking-widest-plus text-surface-500 tabular-nums">
            {year}
          </p>
        )}
      </div>
    </motion.button>
  );
}
