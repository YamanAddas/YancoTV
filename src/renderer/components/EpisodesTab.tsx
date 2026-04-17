import { useState, useMemo, useEffect, useCallback } from 'react';
import { motion } from 'motion/react';
import { usePlayerStore } from '../stores/player-store';
import type { Episode } from '../../shared/types';

interface EpisodesTabProps {
  episodes: Episode[];
  contentId: string;
  onEpisodePlay: (episode: Episode) => void;
  onEpisodeDownload?: (episode: Episode) => void;
}

export function EpisodesTab({
  episodes,
  contentId,
  onEpisodePlay,
  onEpisodeDownload,
}: EpisodesTabProps) {
  const [selectedSeason, setSelectedSeason] = useState<number>(1);
  const [episodePositions, setEpisodePositions] = useState<
    Record<string, { positionSeconds: number; durationSeconds?: number }>
  >({});

  const currentContentId = usePlayerStore((s) => s.currentContentId);
  const currentEpisodeId = usePlayerStore((s) => s.currentEpisodeId);
  const playerStatus = usePlayerStore((s) => s.status);

  // Group episodes by season
  const seasons = useMemo(() => {
    const map = new Map<number, Episode[]>();
    for (const ep of episodes) {
      const season = ep.seasonNumber ?? 1;
      if (!map.has(season)) map.set(season, []);
      map.get(season)!.push(ep);
    }
    return Array.from(map.entries()).sort(([a], [b]) => a - b);
  }, [episodes]);

  // Set initial season
  useEffect(() => {
    if (seasons.length > 0) {
      setSelectedSeason(seasons[0][0]);
    }
  }, [seasons]);

  // Fetch watch positions for all episodes in one batch IPC call
  useEffect(() => {
    if (!window.api || episodes.length === 0) return;

    const episodeIds = episodes.map((ep) => ep.id);
    window.api.history.getPositionsBatch(contentId, episodeIds).then(
      (positions: Record<string, { positionSeconds: number; durationSeconds?: number }>) => {
        setEpisodePositions(positions ?? {});
      },
    );
  }, [episodes, contentId]);

  const currentSeasonEpisodes = useMemo(() => {
    const found = seasons.find(([num]) => num === selectedSeason);
    if (!found) return [];
    return found[1].sort((a, b) => (a.episodeNumber ?? 0) - (b.episodeNumber ?? 0));
  }, [seasons, selectedSeason]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent, episode: Episode) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        onEpisodePlay(episode);
      }
    },
    [onEpisodePlay],
  );

  return (
    <div className="space-y-4">
      {/* Season selector */}
      {seasons.length > 1 && (
        <div className="flex items-center gap-2">
          <select
            value={selectedSeason}
            onChange={(e) => setSelectedSeason(Number(e.target.value))}
            className="rounded-lg border border-accent/10 bg-surface-800 px-3 py-2 text-sm font-medium text-surface-200 outline-none transition-colors focus:border-accent/30"
            style={{
              clipPath: 'polygon(4% 0%, 96% 0%, 100% 50%, 96% 100%, 4% 100%, 0% 50%)',
              padding: '8px 20px',
            }}
          >
            {seasons.map(([num, eps]) => (
              <option key={num} value={num}>
                Season {num} ({eps.length} ep{eps.length !== 1 ? 's' : ''})
              </option>
            ))}
          </select>
        </div>
      )}

      {/* Episode list */}
      <div className="space-y-1.5">
        {currentSeasonEpisodes.map((ep, index) => {
          const isPlaying =
            currentContentId === contentId &&
            currentEpisodeId === ep.id &&
            (playerStatus === 'playing' || playerStatus === 'buffering');
          const position = episodePositions[ep.id];
          const progressPct =
            position?.durationSeconds && position.durationSeconds > 0
              ? Math.min((position.positionSeconds / position.durationSeconds) * 100, 100)
              : 0;

          return (
            <div key={ep.id} className="group/row relative flex items-stretch gap-1.5">
            <motion.button
              onClick={() => onEpisodePlay(ep)}
              onKeyDown={(e) => handleKeyDown(e, ep)}
              className={`group relative flex flex-1 items-center gap-4 rounded-xl border px-4 py-3 text-left transition-all hover:-translate-y-0.5 hover:shadow-glow-sm ${
                isPlaying
                  ? 'border-accent/30 bg-accent/5 shadow-glow-sm'
                  : 'border-accent/5 bg-surface-900/30 hover:border-accent/20'
              }`}
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.15, delay: index * 0.03 }}
            >
              {/* Episode number hex badge */}
              <div
                className={`flex h-9 w-9 flex-shrink-0 items-center justify-center text-sm font-bold ${
                  isPlaying
                    ? 'bg-accent/20 text-accent animate-pulse-glow'
                    : 'bg-surface-800 text-surface-400'
                }`}
                style={{
                  clipPath: 'polygon(50% 0%, 100% 25%, 100% 75%, 50% 100%, 0% 75%, 0% 25%)',
                }}
              >
                {ep.episodeNumber ?? '?'}
              </div>

              {/* Currently playing indicator - left border */}
              {isPlaying && (
                <div className="absolute left-0 top-2 bottom-2 w-0.5 rounded-full bg-accent" />
              )}

              {/* Title + description */}
              <div className="min-w-0 flex-1">
                <p className={`truncate text-sm font-medium ${isPlaying ? 'text-accent' : 'text-surface-100'}`}>
                  {ep.title || `Episode ${ep.episodeNumber ?? '?'}`}
                </p>
              </div>

              {/* Duration */}
              {ep.duration && (
                <span className="flex-shrink-0 text-xs text-surface-500">
                  {formatDuration(ep.duration)}
                </span>
              )}

              {/* Play icon on hover */}
              <svg
                className="h-5 w-5 flex-shrink-0 text-surface-600 opacity-0 transition-opacity group-hover:opacity-100"
                viewBox="0 0 24 24"
                fill="currentColor"
              >
                <path d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.348a1.125 1.125 0 010 1.971l-11.54 6.347a1.125 1.125 0 01-1.667-.985V5.653z" />
              </svg>

              {/* Progress bar */}
              {progressPct > 0 && (
                <div className="absolute bottom-0 left-4 right-4 h-0.5 overflow-hidden rounded-full bg-surface-800">
                  <div
                    className="h-full rounded-full bg-accent/60"
                    style={{ width: `${progressPct}%` }}
                  />
                </div>
              )}
            </motion.button>

            {/* Download button — sibling so it doesn't bubble into the row click */}
            {onEpisodeDownload && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onEpisodeDownload(ep);
                }}
                className="flex w-11 flex-shrink-0 items-center justify-center rounded-xl border border-accent/5 bg-surface-900/30 text-surface-500 opacity-0 transition-all hover:border-accent/30 hover:bg-surface-800/60 hover:text-accent group-hover/row:opacity-100"
                title="Download this episode"
                aria-label="Download episode"
              >
                <svg
                  className="h-4 w-4"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M12 4v12m0 0l-4-4m4 4l4-4M4 20h16"
                  />
                </svg>
              </button>
            )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}
