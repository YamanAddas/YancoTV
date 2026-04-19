import React from 'react';
import { Platform, StyleSheet, View } from 'react-native';
import Video, { ViewType } from 'react-native-video';
import { usePlayerStore } from '../stores/player-store';
import { spacing } from '../styles/theme';

// The single <Video> instance in the mobile app. Lives as a root-level
// sibling of the HomeShell tree (see HomeShell.tsx), so it survives route
// transitions into FullscreenPlayer — which is itself a transparentModal
// over the shell, not a separate surface (M4R rule 5, fixes MB-13).
//
// The mini geometry MUST match HomeShell's playerSlot (right column,
// spacing.md padding, 16:9). Keep those two in sync if HomeShell changes.
//
// TextureView (ViewType.TEXTURE) is required because SurfaceView renders in
// a separate compositor layer that can't be overlaid by transparent JS
// views — fullscreen controls + transparent modal both need regular view
// tree compositing to paint on top.

function detectStreamType(url: string): 'm3u8' | 'mpd' | 'ism' | undefined {
  const u = url.toLowerCase().split('?')[0];
  if (u.includes('.m3u8')) return 'm3u8';
  if (u.includes('.mpd')) return 'mpd';
  if (u.includes('.ism')) return 'ism';
  return undefined;
}

const RIGHT_WIDTH = 360;

export function PersistentPlayerHost() {
  const track = usePlayerStore((s) => s.track);
  const isFullscreen = usePlayerStore((s) => s.isFullscreen);
  const isPaused = usePlayerStore((s) => s.isPaused);

  if (!track) return null;

  const wrapperStyle = isFullscreen
    ? styles.fullscreen
    : Platform.isTV
      ? styles.miniTv
      : styles.miniPhone;

  return (
    <View style={wrapperStyle} pointerEvents="none">
      <Video
        source={{ uri: track.url, type: detectStreamType(track.url) }}
        style={StyleSheet.absoluteFill}
        resizeMode="contain"
        controls={false}
        paused={isPaused}
        viewType={ViewType.TEXTURE}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  // Mirrors HomeShell.tvRoot → rightColumn → playerSlot geometry so the
  // <Video> visually fills the mini slot while actually being a root-level
  // absolute overlay.
  miniTv: {
    position: 'absolute',
    top: spacing.md,
    right: spacing.md,
    width: RIGHT_WIDTH - 2 * spacing.md,
    aspectRatio: 16 / 9,
    backgroundColor: '#000',
    overflow: 'hidden',
  },
  miniPhone: {
    position: 'absolute',
    left: spacing.sm,
    right: spacing.sm,
    bottom: spacing.sm,
    aspectRatio: 16 / 9,
    backgroundColor: '#000',
    overflow: 'hidden',
  },
  fullscreen: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    zIndex: 100,
    elevation: 100,
    backgroundColor: '#000',
  },
});
