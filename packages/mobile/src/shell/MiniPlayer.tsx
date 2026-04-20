import React, { useCallback } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { usePlayerStore } from '../stores/player-store';
import { colors, spacing } from '../styles/theme';

// Re-entry tile for the last-played track. Tapping it re-launches the
// native PlayerActivity for the same stream. RN never mounts <Video>.

export function MiniPlayer() {
  const track = usePlayerStore((s) => s.track);
  const play = usePlayerStore((s) => s.play);

  const onPress = useCallback(() => {
    if (!track) return;
    play(track);
  }, [track, play]);

  return (
    <Pressable
      onPress={onPress}
      disabled={!track}
      style={({ focused }) => [styles.root, focused && styles.rootFocused]}
    >
      {track ? (
        <>
          <View style={styles.badge} pointerEvents="none">
            <Text style={styles.badgeText}>Tap to expand</Text>
          </View>
          <View style={styles.titleBar} pointerEvents="none">
            <Text style={styles.title} numberOfLines={1}>
              {track.title}
            </Text>
          </View>
        </>
      ) : (
        <View style={styles.empty}>
          <Text style={styles.emptyText}>No media</Text>
        </View>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#000',
    borderWidth: 1,
    borderColor: 'transparent',
  },
  rootFocused: {
    borderColor: colors.focus,
  },
  empty: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  emptyText: {
    color: colors.surface400,
    fontSize: 13,
    fontWeight: '600',
    letterSpacing: 0.5,
  },
  badge: {
    position: 'absolute',
    top: spacing.sm,
    right: spacing.sm,
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
    backgroundColor: 'rgba(0, 0, 0, 0.55)',
    borderRadius: 4,
  },
  badgeText: {
    color: colors.surface200,
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  titleBar: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    backgroundColor: 'rgba(0, 0, 0, 0.55)',
  },
  title: {
    color: colors.white,
    fontSize: 13,
    fontWeight: '700',
  },
});
