import React, { useCallback } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Polygon } from 'react-native-svg';
import type { ContentItem } from '@yancotv/core';
import { colors, radii, spacing } from '../styles/theme';
import { CachedImage } from '../image/CachedImage';
import { QualityBadgePills } from './QualityBadgePills';

// M4R.D.3 — Live TV row with a hex-outlined logo frame.
//
// Layout (left → right):
//   [ hex-outlined 64×64 logo ]  [ title + subtitle ]        [ quality pills ]
//
// Row stays rectangular (1px accent-18 border). Only the logo container is
// hex — **outline only** via a stroked SVG Polygon. No MaskedView, no child
// clipping. That was the 2026-04-12 GPU regression; the M4R.D non-goals list
// forbids reintroducing it.

const HEX_SIZE = 64;
const LOGO_IMAGE = 40;

interface Props {
  item: ContentItem;
  active: boolean;
  onPress: (item: ContentItem) => void;
}

export function HexChannelRow({ item, active, onPress }: Props) {
  const handlePress = useCallback(() => onPress(item), [item, onPress]);
  const displayTitle = item.cleanTitle || item.title;
  return (
    <Pressable
      onPress={handlePress}
      style={({ focused }) => [
        styles.row,
        active && styles.rowActive,
        focused && styles.rowFocused,
      ]}
    >
      <View style={styles.hexSlot}>
        <Svg
          width={HEX_SIZE}
          height={HEX_SIZE}
          viewBox="0 0 100 100"
          style={StyleSheet.absoluteFill}
        >
          <Polygon
            points="25,4 75,4 96,50 75,96 25,96 4,50"
            fill="none"
            stroke={colors.accent}
            strokeWidth={3}
          />
        </Svg>
        {item.logoUrl ? (
          <CachedImage
            uri={item.logoUrl}
            style={styles.logoImage}
            resizeMode="contain"
          />
        ) : (
          <Text style={styles.logoFallback}>
            {(displayTitle || '?').charAt(0).toUpperCase()}
          </Text>
        )}
      </View>
      <View style={styles.text}>
        <Text style={styles.title} numberOfLines={1}>
          {displayTitle}
        </Text>
        {item.groupName && (
          <Text style={styles.subtitle} numberOfLines={1}>
            {item.groupName}
          </Text>
        )}
      </View>
      <QualityBadgePills title={item.title} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: spacing.lg,
    marginBottom: spacing.sm,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radii.md,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: colors.accentBorder18,
  },
  rowActive: {
    borderColor: colors.accent,
    backgroundColor: colors.glass,
  },
  rowFocused: {
    borderColor: colors.focus,
    backgroundColor: colors.glass,
  },
  hexSlot: {
    width: HEX_SIZE,
    height: HEX_SIZE,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoImage: {
    width: LOGO_IMAGE,
    height: LOGO_IMAGE,
  },
  logoFallback: {
    color: colors.surface200,
    fontSize: 22,
    fontWeight: '800',
  },
  text: {
    flex: 1,
    marginHorizontal: spacing.md,
  },
  title: {
    color: colors.white,
    fontSize: 15,
    fontWeight: '700',
  },
  subtitle: {
    color: colors.surface400,
    fontSize: 12,
    marginTop: 2,
  },
});
