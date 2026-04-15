import { useState } from 'react';
import { motion } from 'motion/react';
import hexFrame from '../assets/hex-frames/hex-frame.svg';
import hexFrameHover from '../assets/hex-frames/hex-frame-hover.svg';
import hexFrameLocked from '../assets/hex-frames/hex-frame-locked.svg';

export interface HexCardProps {
  title: string;
  subtitle?: string;
  imageUrl?: string;
  fallbackLetter?: string;
  isLocked?: boolean;
  isFavorite?: boolean;
  nowPlaying?: string;
  onClick?: () => void;
  onFavoriteToggle?: () => void;
  onContextMenu?: (e: React.MouseEvent) => void;
  /** Render a smaller hex for list-view thumbnails */
  size?: 'normal' | 'small';
}

export function HexCard({
  title,
  subtitle,
  imageUrl,
  fallbackLetter,
  isLocked = false,
  isFavorite = false,
  nowPlaying,
  onClick,
  onFavoriteToggle,
  onContextMenu,
  size = 'normal',
}: HexCardProps) {
  const [hovered, setHovered] = useState(false);
  const [imgError, setImgError] = useState(false);

  const letter = fallbackLetter || title.charAt(0).toUpperCase();
  const showImage = imageUrl && !imgError;
  const activeFrame = isLocked ? hexFrameLocked : hovered ? hexFrameHover : hexFrame;
  const isSmall = size === 'small';

  return (
    <motion.button
      className="group relative flex flex-col items-center focus:outline-none"
      onClick={onClick}
      onContextMenu={onContextMenu}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      whileHover={{ scale: isSmall ? 1.08 : 1.05 }}
      whileTap={{ scale: 0.97 }}
      transition={{ type: 'spring', stiffness: 400, damping: 25 }}
    >
      {/* ── Hex orb container ─────────────────────────────────────────── */}
      <div className="relative w-full" style={{ aspectRatio: '200 / 230' }}>
        {/* Layer 1: Outer glow — large blurred copy of frame */}
        <img
          src={activeFrame}
          alt=""
          aria-hidden
          className="pointer-events-none absolute inset-[-12%] h-[124%] w-[124%] transition-opacity duration-300"
          style={{
            filter: hovered && !isLocked ? 'blur(14px)' : 'blur(10px)',
            opacity: hovered && !isLocked ? 0.5 : 0.2,
          }}
        />

        {/* Layer 2: Frame asset */}
        <img
          src={activeFrame}
          alt=""
          aria-hidden
          className="pointer-events-none absolute inset-0 h-full w-full transition-all duration-300"
        />

        {/* Layer 3: Content inside hex clip */}
        <div className="absolute inset-[7%] clip-hex-tall overflow-hidden">
          {showImage ? (
            <img
              src={imageUrl}
              alt={title}
              className={`h-full w-full object-cover transition-transform duration-300 ${
                hovered ? 'scale-110' : 'scale-100'
              }`}
              loading="lazy"
              onError={() => setImgError(true)}
            />
          ) : (
            <div className="flex h-full w-full items-center justify-center bg-surface-900/90">
              <span className={`font-bold transition-colors duration-300 ${
                isSmall ? 'text-base' : 'text-2xl'
              } ${hovered ? 'text-accent text-glow' : 'text-surface-500'}`}>
                {letter}
              </span>
            </div>
          )}

          {/* Inner depth vignette — darkens edges for recessed look */}
          <div
            className="pointer-events-none absolute inset-0"
            style={{
              background: 'radial-gradient(ellipse at 50% 40%, transparent 40%, rgba(0,0,0,0.5) 100%)',
            }}
          />

          {/* Top light reflection — glass shine */}
          <div
            className="pointer-events-none absolute inset-0"
            style={{
              background: 'linear-gradient(170deg, rgba(255,255,255,0.08) 0%, transparent 35%, transparent 100%)',
            }}
          />

          {/* Bottom edge shadow — depth cue */}
          <div
            className="pointer-events-none absolute inset-0"
            style={{
              background: 'linear-gradient(to top, rgba(0,0,0,0.4) 0%, transparent 25%)',
            }}
          />

          {/* Locked overlay */}
          {isLocked && (
            <div className="absolute inset-0 flex items-center justify-center bg-surface-950/60">
              <svg className={`${isSmall ? 'h-5 w-5' : 'h-8 w-8'} text-amber-400/70`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                <path d="M7 11V7a5 5 0 0 1 10 0v4" />
              </svg>
            </div>
          )}
        </div>

        {/* Drop shadow beneath hex — ground shadow */}
        <div
          className="pointer-events-none absolute bottom-[-4%] left-[15%] right-[15%] h-[10%] transition-opacity duration-300"
          style={{
            background: hovered && !isLocked
              ? 'radial-gradient(ellipse, rgba(0,255,170,0.2) 0%, transparent 70%)'
              : 'radial-gradient(ellipse, rgba(0,0,0,0.3) 0%, transparent 70%)',
            filter: 'blur(4px)',
          }}
        />

        {/* Favorite button */}
        {onFavoriteToggle && !isSmall && (
          <button
            onClick={(e) => { e.stopPropagation(); onFavoriteToggle(); }}
            className={`absolute right-[8%] top-[18%] z-10 flex h-6 w-6 items-center justify-center rounded-full transition-all ${
              isFavorite
                ? 'bg-red-500/90 text-white opacity-100'
                : 'bg-surface-950/60 text-surface-400 opacity-0 group-hover:opacity-100 hover:text-red-400'
            }`}
            title={isFavorite ? 'Remove from favorites' : 'Add to favorites'}
          >
            <svg className="h-3 w-3" viewBox="0 0 24 24" fill={isFavorite ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12z" />
            </svg>
          </button>
        )}
      </div>

      {/* ── Label below hex ───────────────────────────────────────────── */}
      {!isSmall && (
        <div className="mt-1 w-full px-1 text-center">
          <p className={`line-clamp-2 text-xs font-medium transition-colors duration-200 ${
            hovered ? 'text-accent text-glow-sm' : 'text-surface-200'
          }`}>
            {title}
          </p>
          {nowPlaying ? (
            <p className="mt-0.5 truncate text-[10px] text-green-400">{nowPlaying}</p>
          ) : subtitle ? (
            <p className="mt-0.5 truncate text-[10px] text-surface-500">{subtitle}</p>
          ) : null}
        </div>
      )}
    </motion.button>
  );
}
