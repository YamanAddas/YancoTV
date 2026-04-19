import React, { useCallback } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { usePlayerStore } from '../stores/player-store';
import type { RootStackParamList } from '../navigation/RootNavigator';
import { colors, spacing } from '../styles/theme';

// Pressable slot inside HomeShell that lives at the same geometry as
// PersistentPlayerHost's mini wrapper. The <Video> itself is rendered OVER
// this slot by the root-level host with pointerEvents="none", so taps on
// the video region bubble through to this Pressable and trigger fullscreen
// expand.
//
// When no track is playing, shows an empty placeholder so the rest of the
// shell layout remains stable.

type Nav = NativeStackNavigationProp<RootStackParamList>;

export function MiniPlayer() {
  const track = usePlayerStore((s) => s.track);
  const enterFullscreen = usePlayerStore((s) => s.enterFullscreen);
  const navigation = useNavigation<Nav>();

  const onPress = useCallback(() => {
    if (!track) return;
    enterFullscreen();
    navigation.navigate('FullscreenPlayer', {
      url: track.url,
      title: track.title,
      contentId: track.contentId,
    });
  }, [track, enterFullscreen, navigation]);

  return (
    <Pressable
      onPress={onPress}
      disabled={!track}
      style={({ focused }) => [styles.root, focused && styles.rootFocused]}
    >
      {track ? (
        <View style={styles.titleBar} pointerEvents="none">
          <Text style={styles.title} numberOfLines={1}>
            {track.title}
          </Text>
        </View>
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
    backgroundColor: 'transparent',
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
