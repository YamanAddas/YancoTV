import React, { useMemo } from 'react';
import { FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import type { EpisodeInfo } from '@yancotv/core';
import { colors, radii, spacing } from '../../styles/theme';

interface Props {
  episodes: EpisodeInfo[];
  onEpisodePlay: (episodeId: string) => void;
  emptyLabel?: string;
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

/**
 * Episodes tab — season-grouped horizontal rails. M4.6 layers in a season
 * picker dropdown and per-episode progress bars wired to the history store.
 * For now, every season's episodes are rendered as a scroll rail so power
 * users can see the full tree at a glance.
 */
export function EpisodesTab({ episodes, onEpisodePlay, emptyLabel }: Props) {
  const groups = useMemo(() => groupBySeason(episodes), [episodes]);

  if (groups.length === 0) {
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

  return (
    <View style={styles.root}>
      {groups.map((group) => (
        <View key={group.seasonNumber} style={styles.seasonSection}>
          <Text style={styles.seasonTitle}>Season {group.seasonNumber}</Text>
          <FlatList
            horizontal
            data={group.episodes}
            keyExtractor={(ep) => ep.id}
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.rail}
            renderItem={({ item }) => (
              <Pressable
                onPress={() => onEpisodePlay(item.id)}
                style={({ pressed, focused }) => [
                  styles.episodeCard,
                  focused && styles.episodeCardFocus,
                  pressed && { opacity: 0.8 },
                ]}
              >
                <Text style={styles.episodeNumber}>
                  E{String(item.episodeNumber).padStart(2, '0')}
                </Text>
                <Text style={styles.episodeTitle} numberOfLines={2}>
                  {item.title}
                </Text>
                {item.duration ? (
                  <Text style={styles.episodeDuration}>{item.duration}</Text>
                ) : null}
              </Pressable>
            )}
          />
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    paddingHorizontal: spacing.xl,
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
  seasonSection: {
    marginBottom: spacing.lg,
  },
  seasonTitle: {
    fontSize: 14,
    fontWeight: '800',
    color: colors.white,
    marginBottom: spacing.sm,
  },
  rail: {
    gap: 12,
  },
  episodeCard: {
    width: 180,
    padding: spacing.md,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.06)',
  },
  episodeCardFocus: {
    borderColor: colors.accent,
    shadowColor: colors.accent,
    shadowOpacity: 0.5,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 0 },
    elevation: 8,
  },
  episodeNumber: {
    color: colors.accent,
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 1.5,
    marginBottom: 6,
  },
  episodeTitle: {
    color: colors.white,
    fontSize: 13,
    fontWeight: '700',
    lineHeight: 18,
    minHeight: 36,
  },
  episodeDuration: {
    color: colors.surface400,
    fontSize: 11,
    marginTop: 6,
  },
});
