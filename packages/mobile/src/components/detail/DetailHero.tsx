import React from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import Animated, { FadeIn } from 'react-native-reanimated';
import type { ContentItem, ContentMetadata } from '@yancotv/core';
import { colors, radii, spacing } from '../../styles/theme';
import { useFavoritesStore } from '../../stores/favorites-store';

const TYPE_LABEL: Record<string, string> = {
  live: 'Live channel',
  movie: 'Movie',
  series: 'Series',
};

interface Props {
  item: ContentItem;
  metadata: ContentMetadata;
  canPlay: boolean;
  primaryLabel: string;
  onBack: () => void;
  onPlay: () => void;
  subsCount?: number;
}

/**
 * Cinematic detail hero — matches the desktop Sprint 11B layout at mobile
 * density. Backdrop art bleeds behind a gradient panel that holds the title,
 * tagline, meta chips, and primary action. Reanimated's `FadeIn.entering`
 * runs once on mount so navigating from the grid feels like a reveal rather
 * than a hard cut.
 */
export function DetailHero({
  item,
  metadata,
  canPlay,
  primaryLabel,
  onBack,
  onPlay,
  subsCount = 0,
}: Props) {
  const favoriteIds = useFavoritesStore((s) => s.favoriteIds);
  const toggleFavorite = useFavoritesStore((s) => s.toggle);
  const isFavorite = favoriteIds.has(item.id);
  const onToggleFavorite = () => {
    void toggleFavorite(item.id);
  };
  const backdrop = metadata.backdropUrl || item.logoUrl;
  const metaChips = [
    metadata.releaseDate ? yearOf(metadata.releaseDate) : null,
    metadata.duration ? formatDuration(metadata.duration) : null,
    metadata.rating || null,
    metadata.genre || null,
  ].filter(Boolean) as string[];

  return (
    <Animated.View entering={FadeIn.duration(300)} style={styles.root}>
      <View style={styles.backdropWrap}>
        {backdrop ? (
          <Image
            source={{ uri: backdrop }}
            style={styles.backdropImage}
            resizeMode="cover"
          />
        ) : null}
        <View style={styles.backdropTint} />
        <View style={styles.backdropGradientTop} />
        <View style={styles.backdropGradientBottom} />

        <Pressable
          onPress={onBack}
          style={({ pressed, focused }) => [
            styles.backBtn,
            (pressed || focused) && styles.backBtnFocus,
          ]}
        >
          <Text style={styles.backBtnText}>{'\u2190  Back'}</Text>
        </Pressable>
      </View>

      <View style={styles.panel}>
        <View style={styles.row}>
          {item.logoUrl ? (
            <Image
              source={{ uri: item.logoUrl }}
              style={styles.poster}
              resizeMode="cover"
            />
          ) : (
            <View style={styles.posterPlaceholder}>
              <Text style={styles.posterPlaceholderText}>
                {(item.title || '?').charAt(0).toUpperCase()}
              </Text>
            </View>
          )}

          <View style={styles.textCol}>
            <Text style={styles.eyebrow}>
              {TYPE_LABEL[item.type] ?? item.type}
            </Text>
            <Text style={styles.title} numberOfLines={3}>
              {item.cleanTitle || item.title}
            </Text>
            {metadata.tagline ? (
              <Text style={styles.tagline} numberOfLines={2}>
                {metadata.tagline}
              </Text>
            ) : null}
            {item.groupName ? (
              <Text style={styles.group}>{item.groupName}</Text>
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
          </View>
        </View>

        <View style={styles.actions}>
          <Pressable
            onPress={onPlay}
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
          <Pressable
            onPress={onToggleFavorite}
            accessibilityLabel={
              isFavorite ? 'Remove from favorites' : 'Add to favorites'
            }
            style={({ pressed, focused }) => [
              styles.favBtn,
              isFavorite && styles.favBtnActive,
              focused && styles.favBtnFocus,
              pressed && { opacity: 0.85 },
            ]}
          >
            <Text
              style={[styles.favBtnText, isFavorite && styles.favBtnTextActive]}
            >
              {isFavorite ? '\u2665  Saved' : '\u2661  Save'}
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
    </Animated.View>
  );
}

function yearOf(date: string): string {
  const m = date.match(/\d{4}/);
  return m ? m[0] : date;
}

function formatDuration(raw: string): string {
  if (/^\d{1,2}:\d{2}(?::\d{2})?$/.test(raw)) return raw;
  const secs = Number(raw);
  if (!Number.isFinite(secs) || secs <= 0) return raw;
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

const styles = StyleSheet.create({
  root: {
    marginBottom: spacing.lg,
  },
  backdropWrap: {
    height: 240,
    backgroundColor: colors.surface900,
    overflow: 'hidden',
  },
  backdropImage: {
    ...StyleSheet.absoluteFill,
  },
  backdropTint: {
    ...StyleSheet.absoluteFill,
    backgroundColor: 'rgba(5, 8, 14, 0.35)',
  },
  backdropGradientTop: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    height: 100,
    backgroundColor: 'rgba(5, 8, 14, 0.55)',
  },
  backdropGradientBottom: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: 120,
    backgroundColor: 'rgba(5, 8, 14, 0.9)',
  },
  backBtn: {
    position: 'absolute',
    top: spacing.md,
    left: spacing.md,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: radii.md,
    backgroundColor: 'rgba(10, 14, 22, 0.7)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.1)',
  },
  backBtnFocus: {
    borderColor: colors.accent,
  },
  backBtnText: {
    color: colors.surface100,
    fontSize: 12,
    fontWeight: '700',
  },
  panel: {
    marginTop: -80,
    paddingHorizontal: spacing.xl,
  },
  row: {
    flexDirection: 'row',
    gap: spacing.lg,
  },
  poster: {
    height: 180,
    width: 130,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
  },
  posterPlaceholder: {
    height: 180,
    width: 130,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
  },
  posterPlaceholderText: {
    fontSize: 60,
    color: colors.surface500,
    fontWeight: '900',
    fontStyle: 'italic',
  },
  textCol: {
    flex: 1,
    paddingTop: 70,
  },
  eyebrow: {
    color: colors.accent,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 2,
    textTransform: 'uppercase',
  },
  title: {
    marginTop: 6,
    fontSize: 26,
    fontWeight: '800',
    color: colors.white,
    lineHeight: 30,
  },
  tagline: {
    marginTop: 6,
    fontSize: 13,
    fontStyle: 'italic',
    color: colors.surface300,
  },
  group: {
    marginTop: 6,
    fontSize: 12,
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
    backgroundColor: 'rgba(20, 25, 35, 0.85)',
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
  favBtn: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: colors.glassBorder,
  },
  favBtnActive: {
    backgroundColor: 'rgba(255, 80, 120, 0.12)',
    borderColor: 'rgba(255, 80, 120, 0.5)',
  },
  favBtnFocus: {
    shadowColor: colors.accent,
    shadowOpacity: 0.6,
    shadowRadius: 14,
    shadowOffset: { width: 0, height: 0 },
    elevation: 10,
  },
  favBtnText: {
    color: colors.surface200,
    fontSize: 13,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  favBtnTextActive: {
    color: '#ff91a4',
  },
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
});
