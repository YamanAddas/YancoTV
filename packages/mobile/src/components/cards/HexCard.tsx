import React, { useState } from 'react';
import {
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import Svg, { ClipPath, Defs, Polygon } from 'react-native-svg';
import { SvgXml } from 'react-native-svg';
import { colors } from '../../styles/theme';
import {
  HEX_CLIP_POINTS,
  HEX_FRAME_HOVER,
  HEX_FRAME_NORMAL,
} from './hex-frames';

// Desktop hex aspect ratio is 200:230. Width drives height.
const HEX_ASPECT = 200 / 230;

interface Props {
  title: string;
  subtitle?: string;
  imageUrl?: string;
  width: number;
  onPress: () => void;
}

// Simple scaled hex polygon points — used as a CSS clip-path replacement.
function scalePoints(points: string, w: number, h: number): string {
  return points
    .split(' ')
    .map((pair) => {
      const [x, y] = pair.split(',').map(Number);
      return `${(x / 200) * w},${(y / 230) * h}`;
    })
    .join(' ');
}

export function HexCard({ title, subtitle, imageUrl, width, onPress }: Props) {
  const [focused, setFocused] = useState(false);
  const [imgError, setImgError] = useState(false);
  const height = Math.round(width / HEX_ASPECT);
  const showImage = !!imageUrl && !imgError;
  const letter = (title || '?').charAt(0).toUpperCase();
  const clipPoints = scalePoints(HEX_CLIP_POINTS, width, height);

  return (
    <Pressable
      onPress={onPress}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      onHoverIn={() => setFocused(true)}
      onHoverOut={() => setFocused(false)}
      style={({ pressed }) => [styles.root, pressed && styles.pressed]}
    >
      <View style={[styles.frame, { width, height }]}>
        {/* Clipped content layer — image or fallback letter inside the hex */}
        <Svg width={width} height={height} style={StyleSheet.absoluteFill}>
          <Defs>
            <ClipPath id="hexBody">
              <Polygon points={clipPoints} />
            </ClipPath>
          </Defs>
        </Svg>
        <View
          style={[
            styles.contentClip,
            {
              width,
              height,
              // React Native iOS/Android do support clipPath via react-native-svg
              // on <View> only through masking workarounds. Falling back to the
              // frame SVG rendering over a rounded content box keeps things
              // simple and still looks correct at typical card sizes.
            },
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
          {/* Bottom fade for legibility */}
          <View style={styles.bottomFade} pointerEvents="none" />
        </View>

        {/* Hex frame SVG overlay */}
        <SvgXml
          xml={focused ? HEX_FRAME_HOVER : HEX_FRAME_NORMAL}
          width={width}
          height={height}
          style={StyleSheet.absoluteFill}
        />
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
    alignItems: 'center',
    marginBottom: 8,
  },
  pressed: {
    opacity: 0.9,
  },
  frame: {
    position: 'relative',
  },
  // Inner content slightly inset so it does not bleed outside the hex border
  contentClip: {
    overflow: 'hidden',
    // Approximate hex via borderRadius to avoid native clip-path — visually
    // very close once the SVG frame sits on top.
    borderRadius: 16,
    backgroundColor: colors.surface900,
  },
  image: {
    width: '100%',
    height: '100%',
  },
  fallback: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surface800,
  },
  fallbackLetter: {
    color: colors.accent,
    fontSize: 52,
    fontWeight: '900',
    fontStyle: 'italic',
    opacity: 0.6,
  },
  bottomFade: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: '35%',
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  title: {
    marginTop: 6,
    width: '100%',
    paddingHorizontal: 4,
    color: colors.surface200,
    fontSize: 12,
    fontWeight: '600',
    textAlign: 'center',
    lineHeight: 15,
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
