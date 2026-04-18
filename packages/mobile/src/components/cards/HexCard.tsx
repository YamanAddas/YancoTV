import React, { useState } from 'react';
import {
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import MaskedView from '@react-native-masked-view/masked-view';
import Svg, { Polygon } from 'react-native-svg';
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

function scalePoints(points: string, w: number, h: number): string {
  return points
    .split(' ')
    .map((pair) => {
      const [x, y] = pair.split(',').map(Number);
      return `${(x / 200) * w},${(y / 230) * h}`;
    })
    .join(' ');
}

function HexMask({ width, height }: { width: number; height: number }) {
  const points = scalePoints(HEX_CLIP_POINTS, width, height);
  return (
    <Svg width={width} height={height}>
      <Polygon points={points} fill="#000" />
    </Svg>
  );
}

export function HexCard({ title, subtitle, imageUrl, width, onPress }: Props) {
  const [focused, setFocused] = useState(false);
  const [imgError, setImgError] = useState(false);
  const height = Math.round(width / HEX_ASPECT);
  const showImage = !!imageUrl && !imgError;
  const letter = (title || '?').charAt(0).toUpperCase();

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
        <MaskedView
          style={{ width, height }}
          maskElement={<HexMask width={width} height={height} />}
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
          <View style={styles.bottomFade} pointerEvents="none" />
        </MaskedView>

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
  image: {
    width: '100%',
    height: '100%',
    backgroundColor: colors.surface900,
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
