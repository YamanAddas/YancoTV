import { useState } from 'react';
import { motion } from 'motion/react';
import type { ContentItem, ContentMetadata, Episode } from '../../shared/types';

interface DetailHeroProps {
  item: ContentItem;
  metadata: ContentMetadata;
  watchPosition?: { positionSeconds: number; durationSeconds?: number };
  episodes: Episode[];
  isFavorite: boolean;
  onBack: () => void;
  onPlay: () => void;
  onFavoriteToggle: () => void;
}

export function DetailHero({
  item,
  metadata,
  watchPosition,
  episodes,
  isFavorite,
  onBack,
  onPlay,
  onFavoriteToggle,
}: DetailHeroProps) {
  const [imgError, setImgError] = useState(false);
  const showImage = item.logoUrl && !imgError;
  const letter = (item.cleanTitle || item.title).charAt(0).toUpperCase();
  const isSeries = item.type === 'series';

  // Build metadata line items
  const metaItems: string[] = [];
  if (metadata.releaseDate) {
    const year = metadata.releaseDate.match(/\d{4}/)?.[0];
    if (year) metaItems.push(year);
  }
  if (metadata.rating) {
    metaItems.push(`★ ${metadata.rating}${!metadata.rating.includes('/') ? '/10' : ''}`);
  }
  if (metadata.genre) {
    const genres = metadata.genre.split(/[,/]/).map((g) => g.trim()).filter(Boolean);
    metaItems.push(...genres.slice(0, 2));
  }
  if (item.groupName && metaItems.length < 4) {
    metaItems.push(item.groupName);
  }
  if (isSeries && episodes.length > 0) {
    const seasonCount = new Set(episodes.map((e) => e.seasonNumber ?? 1)).size;
    metaItems.push(`${seasonCount} Season${seasonCount !== 1 ? 's' : ''}`);
    metaItems.push(`${episodes.length} Episode${episodes.length !== 1 ? 's' : ''}`);
  }

  // Short description for the hero (series info or short movie description)
  const description = metadata.plot || metadata.description;
  const showHeroDescription = isSeries && description;

  // Resume state
  let playLabel = 'Play';
  if (watchPosition && watchPosition.positionSeconds > 30) {
    const mins = Math.floor(watchPosition.positionSeconds / 60);
    playLabel = `Resume at ${mins}m`;
  }
  const progressPct =
    watchPosition?.durationSeconds && watchPosition.durationSeconds > 0
      ? Math.min((watchPosition.positionSeconds / watchPosition.durationSeconds) * 100, 100)
      : 0;

  return (
    <div className="relative mb-6 min-h-[340px]">
      {/* Backdrop */}
      <motion.div
        className="absolute inset-0 overflow-hidden"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.3 }}
      >
        {showImage ? (
          <img
            src={item.logoUrl}
            alt=""
            className="h-full w-full object-cover"
            style={{ filter: 'blur(20px) brightness(0.3)', transform: 'scale(1.1)' }}
            onError={() => setImgError(true)}
          />
        ) : (
          <div className="h-full w-full bg-gradient-to-b from-surface-900 to-surface-950" />
        )}
        <div className="absolute inset-0 bg-gradient-to-b from-transparent via-surface-950/60 to-surface-950" />
        <div className="absolute inset-0 bg-gradient-to-r from-surface-950/80 via-transparent to-transparent" />
      </motion.div>

      {/* Content */}
      <div className="relative z-10 flex gap-6 px-6 pb-6 pt-4">
        {/* Back button */}
        <button
          onClick={onBack}
          className="absolute left-4 top-4 flex items-center gap-1.5 rounded-lg bg-surface-950/40 px-3 py-1.5 text-sm text-surface-400 backdrop-blur-sm transition-colors hover:bg-surface-800/60 hover:text-surface-200"
        >
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
          </svg>
          Back
        </button>

        {/* Poster */}
        <motion.div
          className="mt-10 flex-shrink-0"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4, type: 'spring', stiffness: 300, damping: 25 }}
        >
          <div
            className="relative h-[280px] w-[190px] overflow-hidden rounded-2xl shadow-glow-sm"
            style={{
              clipPath: 'polygon(50% 0%, 100% 6%, 100% 94%, 50% 100%, 0% 94%, 0% 6%)',
            }}
          >
            {showImage ? (
              <img
                src={item.logoUrl}
                alt={item.title}
                className="h-full w-full object-cover"
                onError={() => setImgError(true)}
              />
            ) : (
              <div className="flex h-full w-full items-center justify-center bg-surface-800">
                <span className="text-4xl font-bold text-surface-500">{letter}</span>
              </div>
            )}
            <div
              className="pointer-events-none absolute inset-0"
              style={{
                background: 'linear-gradient(170deg, rgba(255,255,255,0.08) 0%, transparent 35%)',
              }}
            />
          </div>
        </motion.div>

        {/* Title + Meta + Actions */}
        <div className="mt-12 flex flex-1 flex-col justify-end">
          <motion.h1
            className="text-3xl font-bold text-surface-50"
            initial={{ opacity: 0, x: -10 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.3, delay: 0.1 }}
          >
            {item.cleanTitle || item.title}
          </motion.h1>

          {/* Metadata line */}
          {metaItems.length > 0 && (
            <motion.div
              className="mt-2 flex flex-wrap items-center gap-1.5 text-sm text-surface-400"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.3, delay: 0.15 }}
            >
              {metaItems.map((item, i) => (
                <span key={i} className="flex items-center gap-1.5">
                  {i > 0 && <span className="text-surface-600">·</span>}
                  <span className={item.startsWith('★') ? 'text-accent font-medium' : ''}>
                    {item}
                  </span>
                </span>
              ))}
            </motion.div>
          )}

          {/* Short description in hero (for series, or short movie descriptions) */}
          {showHeroDescription && (
            <motion.p
              className="mt-3 line-clamp-3 max-w-xl text-sm leading-relaxed text-surface-400"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.3, delay: 0.2 }}
            >
              {description}
            </motion.p>
          )}

          {/* Cast / Director quick line */}
          {isSeries && (metadata.cast || metadata.director) && (
            <motion.div
              className="mt-2 max-w-xl text-xs text-surface-500"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.3, delay: 0.22 }}
            >
              {metadata.director && <span>Dir. {metadata.director}</span>}
              {metadata.director && metadata.cast && <span> · </span>}
              {metadata.cast && <span className="line-clamp-1">Cast: {metadata.cast}</span>}
            </motion.div>
          )}

          {/* Action buttons */}
          <motion.div
            className="mt-4 flex items-center gap-3"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.3, delay: 0.25 }}
          >
            {/* Play button */}
            <button
              onClick={onPlay}
              className="relative flex items-center gap-2 overflow-hidden rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-surface-950 shadow-glow-sm transition-all hover:bg-accent-hover hover:shadow-glow"
              style={{
                clipPath: 'polygon(4% 0%, 96% 0%, 100% 50%, 96% 100%, 4% 100%, 0% 50%)',
                padding: '10px 24px',
              }}
            >
              <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor">
                <path d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.348a1.125 1.125 0 010 1.971l-11.54 6.347a1.125 1.125 0 01-1.667-.985V5.653z" />
              </svg>
              {playLabel}
              {progressPct > 0 && (
                <div className="absolute bottom-0 left-0 h-0.5 bg-surface-950/30" style={{ width: '100%' }}>
                  <div className="h-full bg-surface-950/60" style={{ width: `${progressPct}%` }} />
                </div>
              )}
            </button>

            {/* Favorite button */}
            <button
              onClick={onFavoriteToggle}
              className={`flex items-center gap-1.5 rounded-lg border px-4 py-2.5 text-sm font-medium transition-all ${
                isFavorite
                  ? 'border-red-500/30 bg-red-500/10 text-red-400 hover:bg-red-500/20'
                  : 'border-surface-700 bg-surface-800/40 text-surface-300 hover:border-surface-600 hover:text-surface-200'
              }`}
            >
              <svg
                className="h-4 w-4"
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
              {isFavorite ? 'Favorited' : 'Favorite'}
            </button>
          </motion.div>
        </div>
      </div>
    </div>
  );
}
