import React, { useState } from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { colors, radii } from '../../styles/theme';
import { HexCard } from './HexCard';

// Two card variants ship:
//   • `hex`     — honeycomb card for live channels, matching desktop HexCard
//   • `poster`  — 2:3 art-forward card for movies/series, matching desktop
//                 PosterCard used in the VOD grids
export type CardVariant = 'hex' | 'poster' | 'landscape';

interface Props {
  title: string;
  subtitle?: string;
  imageUrl?: string;
  badge?: string;
  variant?: CardVariant;
  width: number;
  onPress: () => void;
}

export function ContentCard({
  title,
  subtitle,
  imageUrl,
  badge,
  variant = 'poster',
  width,
  onPress,
}: Props) {
  if (variant === 'hex') {
    return (
      <HexCard
        title={title}
        subtitle={subtitle}
        imageUrl={imageUrl}
        width={width}
        onPress={onPress}
      />
    );
  }

  const [focused, setFocused] = useState(false);
  const [imgError, setImgError] = useState(false);

  // poster = 2:3, landscape = 16:9
  const aspect = variant === 'poster' ? 2 / 3 : 16 / 9;
  const imgHeight = Math.round(width / aspect);
  const showImage = !!imageUrl && !imgError;
  const letter = (title || '?').charAt(0).toUpperCase();

  return (
    <Pressable
      onPress={onPress}
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
          <Image
            source={{ uri: imageUrl }}
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
