import React, { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { CachedImage } from '../../image/CachedImage';
import { colors, radii } from '../../styles/theme';

// Card variants:
//   • `hex`     — flat rectangular tile for live channels. Name kept for API
//                 compat with desktop; on mobile the hex SVG+MaskedView cost
//                 ~20 ms/card to paint, which kills scroll on Android. Flat
//                 tiles are what TiviMate / Smarters / Stremio ship.
//   • `poster`  — 2:3 art card for movies/series
//   • `landscape` — 16:9 poster (reserved for future)
export type CardVariant = 'hex' | 'poster' | 'landscape';

interface Props {
  title: string;
  subtitle?: string;
  imageUrl?: string;
  badge?: string;
  variant?: CardVariant;
  width: number;
  onPress: () => void;
  onLongPress?: () => void;
  /** 0..1 — renders a thin progress bar across the bottom of the card art. */
  progress?: number;
}

function ContentCardImpl(props: Props) {
  if (props.variant === 'hex') {
    return <ChannelTile {...props} />;
  }
  return <PosterCard {...props} />;
}

function ChannelTile({
  title,
  subtitle,
  imageUrl,
  width,
  onPress,
  onLongPress,
  progress,
}: Props) {
  const [focused, setFocused] = useState(false);
  const [imgError, setImgError] = useState(false);
  const showImage = !!imageUrl && !imgError;
  const letter = (title || '?').charAt(0).toUpperCase();
  const tileHeight = Math.round(width * 0.72);

  const clampedProgress =
    typeof progress === 'number'
      ? Math.max(0, Math.min(1, progress))
      : undefined;

  return (
    <Pressable
      onPress={onPress}
      onLongPress={onLongPress}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      onHoverIn={() => setFocused(true)}
      onHoverOut={() => setFocused(false)}
      style={({ pressed }) => [
        tileStyles.root,
        { width },
        pressed && tileStyles.pressed,
      ]}
    >
      <View
        style={[
          tileStyles.box,
          { width, height: tileHeight },
          focused && tileStyles.boxFocused,
        ]}
      >
        {showImage ? (
          <CachedImage
            uri={imageUrl}
            style={tileStyles.logo}
            resizeMode="contain"
            onError={() => setImgError(true)}
          />
        ) : (
          <Text style={tileStyles.fallbackLetter}>{letter}</Text>
        )}
        {clampedProgress !== undefined ? (
          <View style={tileStyles.progressTrack} pointerEvents="none">
            <View
              style={[
                tileStyles.progressFill,
                { width: `${Math.round(clampedProgress * 100)}%` },
              ]}
            />
          </View>
        ) : null}
      </View>
      <Text
        style={[tileStyles.title, focused && tileStyles.titleFocused]}
        numberOfLines={1}
      >
        {title}
      </Text>
      {subtitle ? (
        <Text style={tileStyles.subtitle} numberOfLines={1}>
          {subtitle}
        </Text>
      ) : null}
    </Pressable>
  );
}

// Memo lets FlatList/FlashList skip re-rendering cards whose props haven't
// changed during a parent re-render (filter/sort changes that don't touch
// this row, focus changes elsewhere, etc).
export const ContentCard = React.memo(ContentCardImpl);

function PosterCard({
  title,
  subtitle,
  imageUrl,
  badge,
  variant = 'poster',
  width,
  onPress,
  onLongPress,
  progress,
}: Props) {
  const [focused, setFocused] = useState(false);
  const [imgError, setImgError] = useState(false);

  // poster = 2:3, landscape = 16:9
  const aspect = variant === 'poster' ? 2 / 3 : 16 / 9;
  const imgHeight = Math.round(width / aspect);
  const showImage = !!imageUrl && !imgError;
  const letter = (title || '?').charAt(0).toUpperCase();

  const clampedProgress =
    typeof progress === 'number'
      ? Math.max(0, Math.min(1, progress))
      : undefined;

  return (
    <Pressable
      onPress={onPress}
      onLongPress={onLongPress}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      onHoverIn={() => setFocused(true)}
      onHoverOut={() => setFocused(false)}
      style={({ pressed }) => [
        styles.root,
        { width },
        pressed && styles.pressed,
      ]}
    >
      <View
        style={[
          styles.imageBox,
          { width, height: imgHeight },
          focused && styles.imageBoxFocused,
        ]}
      >
        {showImage ? (
          <CachedImage
            uri={imageUrl}
            style={styles.image}
            resizeMode="cover"
            onError={() => setImgError(true)}
          />
        ) : (
          <View style={styles.fallback}>
            <Text style={styles.fallbackLetter}>{letter}</Text>
          </View>
        )}
        {badge ? (
          <View style={styles.badge}>
            <Text style={styles.badgeText}>{badge}</Text>
          </View>
        ) : null}
        <View style={styles.bottomFade} pointerEvents="none" />
        {clampedProgress !== undefined ? (
          <View style={styles.progressTrack} pointerEvents="none">
            <View
              style={[
                styles.progressFill,
                { width: `${Math.round(clampedProgress * 100)}%` },
              ]}
            />
          </View>
        ) : null}
      </View>

      <Text
        style={[styles.title, focused && styles.titleFocused]}
        numberOfLines={2}
      >
        {title}
      </Text>
      {subtitle ? (
        <Text style={styles.subtitle} numberOfLines={1}>
          {subtitle}
        </Text>
      ) : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: {
    marginBottom: 8,
  },
  pressed: {
    opacity: 0.9,
  },
  imageBox: {
    borderRadius: radii.md,
    overflow: 'hidden',
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: 'rgba(0, 255, 170, 0.08)',
  },
  imageBoxFocused: {
    borderColor: colors.accent,
    shadowColor: colors.accent,
    shadowOpacity: 0.6,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 0 },
    elevation: 12,
  },
  image: {
    width: '100%',
    height: '100%',
  },
  fallback: {
    width: '100%',
    height: '100%',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surface800,
  },
  fallbackLetter: {
    color: colors.accent,
    fontSize: 44,
    fontWeight: '900',
    fontStyle: 'italic',
    opacity: 0.55,
  },
  bottomFade: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: '30%',
    backgroundColor: 'rgba(0,0,0,0.45)',
  },
  progressTrack: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: 3,
    backgroundColor: 'rgba(255,255,255,0.18)',
  },
  progressFill: {
    height: '100%',
    backgroundColor: colors.accent,
  },
  badge: {
    position: 'absolute',
    top: 6,
    left: 6,
    backgroundColor: 'rgba(0, 255, 170, 0.15)',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: radii.sm,
  },
  badgeText: {
    color: colors.accent,
    fontSize: 9,
    fontWeight: '700',
    letterSpacing: 1,
    textTransform: 'uppercase',
  },
  title: {
    marginTop: 6,
    color: colors.surface100,
    fontSize: 12,
    fontWeight: '600',
    lineHeight: 15,
  },
  titleFocused: {
    color: colors.accent,
  },
  subtitle: {
    marginTop: 2,
    color: colors.surface500,
    fontSize: 10,
  },
});

const tileStyles = StyleSheet.create({
  root: {
    marginBottom: 8,
  },
  pressed: {
    opacity: 0.9,
  },
  box: {
    borderRadius: radii.md,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(0, 255, 170, 0.1)',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  boxFocused: {
    borderColor: colors.accent,
    backgroundColor: 'rgba(0, 255, 170, 0.06)',
  },
  logo: {
    width: '78%',
    height: '78%',
  },
  fallbackLetter: {
    color: colors.accent,
    fontSize: 32,
    fontWeight: '900',
    fontStyle: 'italic',
    opacity: 0.55,
  },
  progressTrack: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: 3,
    backgroundColor: 'rgba(255,255,255,0.18)',
  },
  progressFill: {
    height: '100%',
    backgroundColor: colors.accent,
  },
  title: {
    marginTop: 6,
    paddingHorizontal: 4,
    color: colors.surface100,
    fontSize: 12,
    fontWeight: '600',
    textAlign: 'center',
  },
  titleFocused: {
    color: colors.accent,
  },
  subtitle: {
    marginTop: 2,
    color: colors.surface500,
    fontSize: 10,
    textAlign: 'center',
  },
});
