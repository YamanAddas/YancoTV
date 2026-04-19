import React, { useEffect, useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { EpisodeInfo } from '@yancotv/core';
import { getPositionsBatch } from '../../db/history-store';
import { SeasonPicker } from './SeasonPicker';
import { colors, radii, spacing } from '../../styles/theme';

interface Props {
  contentId: string;
  episodes: EpisodeInfo[];
  onEpisodePlay: (episodeId: string) => void;
  emptyLabel?: string;
}

interface PositionMap {
  [episodeId: string]: { positionSeconds: number; durationSeconds?: number };
}

/**
 * Episodes tab. Season picker at top (hidden when only one season),
 * selected season renders as a vertical list that matches the desktop
 * layout. Each row shows an episode-number hex badge, title, duration,
 * and a thin progress bar sourced from watch_history via
 * getPositionsBatch — gives the user a "you left off here" signal without
 * waiting for the full PlayerScreen to open.
 */
export function EpisodesTab({
  contentId,
  episodes,
  onEpisodePlay,
  emptyLabel,
}: Props) {
  const seasons = useMemo(() => groupBySeason(episodes), [episodes]);
  const [selectedSeason, setSelectedSeason] = useState<number>(
    seasons[0]?.seasonNumber ?? 1,
  );
  const [positions, setPositions] = useState<PositionMap>({});

  // Keep the selected season in sync when the episodes list changes
  // (e.g. lazy detail fetch completes after mount).
  useEffect(() => {
    if (seasons.length === 0) return;
    const hit = seasons.find((s) => s.seasonNumber === selectedSeason);
    if (!hit) setSelectedSeason(seasons[0].seasonNumber);
  }, [seasons, selectedSeason]);

  // Batch-load watch positions for every episode on this content. One SQL
  // round trip regardless of episode count, then we render progress bars
  // off the in-memory map.
  useEffect(() => {
    if (!contentId || episodes.length === 0) {
      setPositions({});
      return;
    }
    let cancelled = false;
    getPositionsBatch(
      contentId,
      episodes.map((e) => e.id),
    )
      .then((map) => {
        if (!cancelled) setPositions(map);
      })
      .catch(() => {
        if (!cancelled) setPositions({});
      });
    return () => {
      cancelled = true;
    };
  }, [contentId, episodes]);

  if (seasons.length === 0) {
    return (
      <View style={styles.root}>
        <View style={styles.empty}>
          <Text style={styles.emptyText}>
            {emptyLabel ?? 'No episodes available'}
          </Text>
        </View>
      </View>
    );
  }

  const seasonOptions = seasons.map((s) => ({
    seasonNumber: s.seasonNumber,
    count: s.episodes.length,
  }));
  const currentEpisodes =
    seasons.find((s) => s.seasonNumber === selectedSeason)?.episodes ??
    seasons[0].episodes;

  return (
    <View style={styles.root}>
      {seasons.length > 1 ? (
        <View style={styles.headerRow}>
          <SeasonPicker
            seasons={seasonOptions}
            value={selectedSeason}
            onChange={setSelectedSeason}
          />
        </View>
      ) : null}

      <View style={styles.list}>
        {currentEpisodes.map((ep) => {
          const pos = positions[ep.id];
          const progressPct =
            pos && pos.durationSeconds && pos.durationSeconds > 0
              ? Math.min((pos.positionSeconds / pos.durationSeconds) * 100, 100)
              : 0;
          return (
            <Pressable
              key={ep.id}
              onPress={() => onEpisodePlay(ep.id)}
              style={({ pressed, focused }) => [
                styles.row,
                focused && styles.rowFocused,
                pressed && styles.rowPressed,
              ]}
            >
              <View style={styles.numberBadge}>
                <Text style={styles.numberBadgeText}>
                  {String(ep.episodeNumber).padStart(2, '0')}
                </Text>
              </View>
              <View style={styles.rowBody}>
                <Text style={styles.rowTitle} numberOfLines={1}>
                  {ep.title || `Episode ${ep.episodeNumber}`}
                </Text>
                {progressPct > 0 ? (
                  <Text style={styles.rowProgressLabel}>
                    {Math.round(progressPct)}% watched
                  </Text>
                ) : null}
              </View>
              {ep.duration ? (
                <Text style={styles.rowDuration}>{ep.duration}</Text>
              ) : null}
              {progressPct > 0 ? (
                <View style={styles.progressTrack}>
                  <View
                    style={[styles.progressFill, { width: `${progressPct}%` }]}
                  />
                </View>
              ) : null}
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

interface SeasonGroup {
  seasonNumber: number;
  episodes: EpisodeInfo[];
}

function groupBySeason(eps: EpisodeInfo[]): SeasonGroup[] {
  const map = new Map<number, EpisodeInfo[]>();
  for (const e of eps) {
    const arr = map.get(e.seasonNumber) ?? [];
    arr.push(e);
    map.set(e.seasonNumber, arr);
  }
  return Array.from(map.entries())
    .sort((a, b) => a[0] - b[0])
    .map(([seasonNumber, list]) => ({
      seasonNumber,
      episodes: list.sort((a, b) => a.episodeNumber - b.episodeNumber),
    }));
}

const styles = StyleSheet.create({
  root: {
    paddingHorizontal: spacing.xl,
  },
  headerRow: {
    marginBottom: spacing.md,
  },
  empty: {
    padding: spacing.xl,
    borderRadius: radii.lg,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: colors.surface700,
    backgroundColor: 'rgba(15, 20, 28, 0.5)',
    alignItems: 'center',
  },
  emptyText: {
    color: colors.surface500,
    fontSize: 13,
  },
  list: {
    gap: 8,
  },
  row: {
    position: 'relative',
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: 12,
    paddingHorizontal: spacing.md,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.06)',
    overflow: 'hidden',
  },
  rowFocused: {
    borderColor: colors.accent,
    shadowColor: colors.accent,
    shadowOpacity: 0.4,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 0 },
    elevation: 6,
  },
  rowPressed: {
    opacity: 0.85,
  },
  numberBadge: {
    width: 36,
    height: 36,
    borderRadius: radii.sm,
    backgroundColor: colors.surface700,
    alignItems: 'center',
    justifyContent: 'center',
  },
  numberBadgeText: {
    color: colors.accent,
    fontSize: 12,
    fontWeight: '800',
    letterSpacing: 1,
  },
  rowBody: {
    flex: 1,
    minWidth: 0,
  },
  rowTitle: {
    color: colors.white,
    fontSize: 14,
    fontWeight: '600',
  },
  rowProgressLabel: {
    color: colors.accent,
    fontSize: 11,
    fontWeight: '700',
    marginTop: 2,
  },
  rowDuration: {
    color: colors.surface400,
    fontSize: 12,
    fontWeight: '600',
  },
  progressTrack: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: 2,
    backgroundColor: 'rgba(255,255,255,0.05)',
  },
  progressFill: {
    height: '100%',
    backgroundColor: colors.accent,
    opacity: 0.75,
  },
});
