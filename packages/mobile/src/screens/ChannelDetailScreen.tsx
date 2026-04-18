import React, { useMemo } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import type { EpisodeInfo } from '@yancotv/core';
import { useNavStore } from '../stores/nav-store';
import { useSourcesStore } from '../stores/sources-store';
import { useContentDetail } from '../hooks/use-content-detail';
import { colors, radii, spacing } from '../styles/theme';

const TYPE_LABEL: Record<string, string> = {
  live: 'Live channel',
  movie: 'Movie',
  series: 'Series',
};

export function ChannelDetailScreen() {
  const back = useNavStore((s) => s.back);
  const openPlayer = useNavStore((s) => s.openPlayer);
  const selectedId = useNavStore((s) => s.selectedChannelId);
  const channel = useSourcesStore((s) =>
    s.channels.find((c) => c.id === selectedId),
  );
  const source = useSourcesStore((s) =>
    channel ? s.sources.find((src) => src.id === channel.sourceId) : undefined,
  );

  const { loading, error, metadata, episodes } = useContentDetail(selectedId);

  const seasonGroups = useMemo(() => groupBySeason(episodes), [episodes]);

  if (!channel) {
    return (
      <View style={styles.missing}>
        <Text style={styles.missingTitle}>Channel not found</Text>
        <Pressable
          onPress={back}
          style={({ pressed }) => [styles.primaryBtn, pressed && { opacity: 0.8 }]}
        >
          <Text style={styles.primaryBtnText}>Back</Text>
        </Pressable>
      </View>
    );
  }

  const poster = metadata.backdropUrl || channel.logoUrl;
  const canPlay = channel.streamUrl.length > 0 || episodes.length > 0;
  const subsCount = metadata.subtitles?.length ?? 0;
  const primaryLabel = (() => {
    if (channel.type === 'series' && episodes.length > 0) return 'Play S01E01';
    if (channel.type === 'series') return loading ? 'Loading episodes…' : 'No episodes';
    return canPlay ? 'Play' : 'No stream URL';
  })();

  function onPlayPrimary() {
    if (channel!.type === 'series') {
      const first = episodes
        .slice()
        .sort(
          (a, b) =>
            a.seasonNumber - b.seasonNumber || a.episodeNumber - b.episodeNumber,
        )[0];
      if (first) openPlayer(channel!.id, first.id);
      return;
    }
    if (canPlay) openPlayer(channel!.id);
  }

  const metaChips = [
    metadata.releaseDate ? yearOf(metadata.releaseDate) : null,
    metadata.duration ? formatDuration(metadata.duration) : null,
    metadata.rating || null,
    metadata.genre || null,
  ].filter(Boolean) as string[];

  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.heroRow}>
        <Pressable
          onPress={back}
          style={({ pressed, focused }) => [
            styles.backBtn,
            (pressed || focused) && styles.backBtnFocus,
          ]}
        >
          <Text style={styles.backBtnText}>← Back</Text>
        </Pressable>
      </View>

      <View style={styles.hero}>
        {poster ? (
          <Image source={{ uri: poster }} style={styles.logo} resizeMode="cover" />
        ) : (
          <View style={styles.logoPlaceholder}>
            <Text style={styles.logoPlaceholderText}>
              {channel.title.charAt(0).toUpperCase()}
            </Text>
          </View>
        )}

        <View style={styles.heroText}>
          <Text style={styles.eyebrow}>
            {TYPE_LABEL[channel.type] ?? channel.type}
          </Text>
          <Text style={styles.title} numberOfLines={3}>
            {channel.title}
          </Text>
          {metadata.tagline ? (
            <Text style={styles.tagline} numberOfLines={2}>
              {metadata.tagline}
            </Text>
          ) : null}
          {channel.groupName ? (
            <Text style={styles.group}>{channel.groupName}</Text>
          ) : null}

          {metaChips.length > 0 ? (
            <View style={styles.metaRow}>
              {metaChips.map((chip) => (
                <View key={chip} style={styles.metaChip}>
                  <Text style={styles.metaChipText}>{chip}</Text>
                </View>
              ))}
            </View>
          ) : null}

          <View style={styles.actions}>
            <Pressable
              onPress={onPlayPrimary}
              disabled={!canPlay}
              style={({ pressed, focused }) => [
                styles.primaryBtn,
                !canPlay && styles.primaryBtnDisabled,
                focused && canPlay && styles.primaryBtnFocus,
                pressed && { opacity: 0.85 },
              ]}
            >
              <Text
                style={[
                  styles.primaryBtnText,
                  !canPlay && styles.primaryBtnTextDisabled,
                ]}
              >
                {primaryLabel}
              </Text>
            </Pressable>
            {subsCount > 0 ? (
              <View style={styles.subsBadge}>
                <Text style={styles.subsBadgeText}>
                  {subsCount} subtitle{subsCount === 1 ? '' : 's'}
                </Text>
              </View>
            ) : null}
          </View>
        </View>
      </View>

      {loading && !metadata.detailFetchedAt ? (
        <View style={styles.loadingBox}>
          <ActivityIndicator color={colors.accent} />
          <Text style={styles.loadingText}>Loading details…</Text>
        </View>
      ) : null}
      {error ? (
        <View style={styles.errorBox}>
          <Text style={styles.errorText}>Details unavailable: {error}</Text>
        </View>
      ) : null}

      {metadata.plot ? (
        <View style={styles.block}>
          <Text style={styles.blockHeader}>Plot</Text>
          <Text style={styles.plotText}>{metadata.plot}</Text>
        </View>
      ) : null}

      {(metadata.cast || metadata.director) && (
        <View style={styles.block}>
          {metadata.director ? (
            <DetailRow label="Director" value={metadata.director} />
          ) : null}
          {metadata.cast ? (
            <DetailRow label="Cast" value={metadata.cast} />
          ) : null}
        </View>
      )}

      {channel.type === 'series' && seasonGroups.length > 0 ? (
        <View style={styles.block}>
          <Text style={styles.blockHeader}>Episodes</Text>
          {seasonGroups.map((group) => (
            <View key={group.seasonNumber} style={styles.seasonSection}>
              <Text style={styles.seasonTitle}>Season {group.seasonNumber}</Text>
              <FlatList
                horizontal
                data={group.episodes}
                keyExtractor={(ep) => ep.id}
                showsHorizontalScrollIndicator={false}
                renderItem={({ item }) => (
                  <Pressable
                    onPress={() => openPlayer(channel.id, item.id)}
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
      ) : null}

      <View style={styles.detailsBox}>
        <Text style={styles.blockHeader}>Technical</Text>
        <DetailRow label="Source" value={source?.name ?? '—'} />
        <DetailRow label="Group" value={channel.groupName ?? '—'} />
        <DetailRow label="TVG-ID" value={channel.tvgId ?? '—'} />
        {channel.streamUrl ? (
          <DetailRow label="Stream URL" value={channel.streamUrl} mono />
        ) : null}
      </View>
    </ScrollView>
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

function yearOf(date: string): string {
  const m = date.match(/\d{4}/);
  return m ? m[0] : date;
}

function formatDuration(raw: string): string {
  // Xtream ships either "HH:MM:SS" or duration_secs as a number string.
  if (/^\d{1,2}:\d{2}(?::\d{2})?$/.test(raw)) return raw;
  const secs = Number(raw);
  if (!Number.isFinite(secs) || secs <= 0) return raw;
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function DetailRow({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <View style={styles.detailRow}>
      <Text style={styles.detailLabel}>{label}</Text>
      <Text
        style={[styles.detailValue, mono && styles.detailValueMono]}
        selectable
      >
        {value}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  content: {
    padding: spacing.xl,
    paddingBottom: spacing.xxl,
  },
  missing: {
    flex: 1,
    padding: spacing.xl,
    alignItems: 'flex-start',
  },
  missingTitle: {
    marginBottom: spacing.md,
    fontSize: 24,
    fontWeight: '700',
    color: colors.white,
  },
  heroRow: {
    marginBottom: spacing.md,
  },
  backBtn: {
    alignSelf: 'flex-start',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.06)',
  },
  backBtnFocus: { borderColor: colors.accent },
  backBtnText: {
    color: colors.surface200,
    fontSize: 12,
    fontWeight: '700',
  },
  hero: {
    flexDirection: 'row',
    gap: spacing.lg,
    padding: spacing.lg,
    borderRadius: radii.lg,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
    marginBottom: spacing.lg,
  },
  logo: {
    height: 180,
    width: 130,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
  },
  logoPlaceholder: {
    height: 180,
    width: 130,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoPlaceholderText: {
    fontSize: 60,
    color: colors.surface500,
    fontWeight: '900',
    fontStyle: 'italic',
  },
  heroText: { flex: 1 },
  eyebrow: {
    color: colors.accent,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 2,
    textTransform: 'uppercase',
  },
  title: {
    marginTop: 6,
    fontSize: 28,
    fontWeight: '800',
    color: colors.white,
    lineHeight: 32,
  },
  tagline: {
    marginTop: 6,
    fontSize: 13,
    fontStyle: 'italic',
    color: colors.surface300,
  },
  group: {
    marginTop: 6,
    fontSize: 13,
    color: colors.surface400,
  },
  metaRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
    marginTop: spacing.sm,
  },
  metaChip: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    backgroundColor: colors.surface800,
    borderRadius: radii.pill,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
  },
  metaChipText: {
    color: colors.surface200,
    fontSize: 11,
    fontWeight: '700',
  },
  actions: {
    marginTop: spacing.md,
    flexDirection: 'row',
    gap: spacing.sm,
    alignItems: 'center',
  },
  primaryBtn: {
    paddingHorizontal: 22,
    paddingVertical: 12,
    backgroundColor: colors.accent,
    borderRadius: radii.md,
  },
  primaryBtnDisabled: { backgroundColor: colors.surface700 },
  primaryBtnFocus: {
    shadowColor: colors.accent,
    shadowOpacity: 0.7,
    shadowRadius: 14,
    shadowOffset: { width: 0, height: 0 },
    elevation: 10,
  },
  primaryBtnText: {
    color: colors.bg,
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 1,
  },
  primaryBtnTextDisabled: { color: colors.surface500 },
  subsBadge: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: colors.glassBorder,
  },
  subsBadgeText: {
    color: colors.accent,
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  loadingBox: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    padding: spacing.md,
  },
  loadingText: {
    color: colors.surface300,
    fontSize: 13,
  },
  errorBox: {
    padding: spacing.md,
    borderRadius: radii.md,
    backgroundColor: 'rgba(248, 113, 113, 0.08)',
    borderWidth: 1,
    borderColor: 'rgba(248, 113, 113, 0.3)',
    marginBottom: spacing.md,
  },
  errorText: {
    color: colors.red300,
    fontSize: 12,
  },
  block: {
    padding: spacing.lg,
    borderRadius: radii.lg,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
    marginBottom: spacing.lg,
  },
  blockHeader: {
    marginBottom: spacing.md,
    fontSize: 11,
    fontWeight: '800',
    color: colors.surface400,
    letterSpacing: 2,
    textTransform: 'uppercase',
  },
  plotText: {
    fontSize: 14,
    color: colors.surface100,
    lineHeight: 22,
  },
  seasonSection: {
    marginBottom: spacing.md,
  },
  seasonTitle: {
    fontSize: 14,
    fontWeight: '800',
    color: colors.white,
    marginBottom: spacing.sm,
  },
  episodeCard: {
    width: 180,
    marginRight: 12,
    padding: spacing.md,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.06)',
  },
  episodeCardFocus: {
    borderColor: colors.accent,
    ...{
      shadowColor: colors.accent,
      shadowOpacity: 0.5,
      shadowRadius: 12,
      shadowOffset: { width: 0, height: 0 },
      elevation: 8,
    },
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
  detailsBox: {
    padding: spacing.lg,
    borderRadius: radii.lg,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
  },
  detailRow: { marginBottom: spacing.md },
  detailLabel: {
    marginBottom: 4,
    fontSize: 10,
    textTransform: 'uppercase',
    color: colors.surface500,
    letterSpacing: 1,
    fontWeight: '700',
  },
  detailValue: {
    fontSize: 13,
    color: colors.white,
  },
  detailValueMono: {
    fontFamily: 'monospace',
    color: colors.surface300,
    fontSize: 11,
  },
});
