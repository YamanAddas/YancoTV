import React, { useCallback, useEffect } from 'react';
import {
  BackHandler,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { usePlayerStore } from '../stores/player-store';
import type { RootStackParamList } from '../navigation/RootNavigator';
import { colors, spacing } from '../styles/theme';

// Transparent-modal route that layers controls over the persistent video
// rendered by PersistentPlayerHost (which expands to fullscreen via
// isFullscreen state). This route never owns a <Video> — that would
// unmount the persistent surface and bring back MB-13 (double-back).
//
// Real controls (seek, track selection, resume) land in M4R.7 part 2 or
// deferred panels. For now: back → exit fullscreen + pop; Enter/center →
// toggle pause.

type Nav = NativeStackNavigationProp<RootStackParamList, 'FullscreenPlayer'>;

export function FullscreenPlayer() {
  const navigation = useNavigation<Nav>();
  const track = usePlayerStore((s) => s.track);
  const isPaused = usePlayerStore((s) => s.isPaused);
  const togglePause = usePlayerStore((s) => s.togglePause);
  const exitFullscreen = usePlayerStore((s) => s.exitFullscreen);
  const stop = usePlayerStore((s) => s.stop);

  const close = useCallback(() => {
    exitFullscreen();
    navigation.goBack();
  }, [exitFullscreen, navigation]);

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      close();
      return true;
    });
    return () => sub.remove();
  }, [close]);

  // Safety: if the store has no track (e.g. stop() was called from
  // elsewhere), bounce back to the shell — there's nothing to control.
  useEffect(() => {
    if (!track) {
      exitFullscreen();
      navigation.goBack();
    }
  }, [track, exitFullscreen, navigation]);

  return (
    <View style={styles.root}>
      <StatusBar hidden />
      <Pressable
        style={styles.tapLayer}
        onPress={togglePause}
        hasTVPreferredFocus
      >
        <View style={styles.topBar} pointerEvents="box-none">
          <Text style={styles.title} numberOfLines={1}>
            {track?.title ?? ''}
          </Text>
        </View>
        <View style={styles.bottomBar} pointerEvents="box-none">
          <ControlButton label={isPaused ? 'Play' : 'Pause'} onPress={togglePause} />
          <ControlButton
            label="Stop"
            onPress={() => {
              stop();
              navigation.goBack();
            }}
          />
          <ControlButton label="Back" onPress={close} />
        </View>
      </Pressable>
    </View>
  );
}

function ControlButton({
  label,
  onPress,
}: {
  label: string;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={({ focused }) => [styles.btn, focused && styles.btnFocused]}
    >
      {({ focused }) => (
        <Text style={[styles.btnLabel, focused && styles.btnLabelFocused]}>
          {label}
        </Text>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: 'transparent',
  },
  tapLayer: {
    flex: 1,
  },
  topBar: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    paddingTop: spacing.lg,
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.md,
    backgroundColor: 'rgba(0, 0, 0, 0.55)',
  },
  title: {
    color: colors.white,
    fontSize: 20,
    fontWeight: '800',
  },
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    padding: spacing.lg,
    gap: spacing.md,
    backgroundColor: 'rgba(0, 0, 0, 0.55)',
  },
  btn: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    borderWidth: 1,
    borderColor: colors.glassBorder,
    borderRadius: 6,
    backgroundColor: colors.glassStrong,
  },
  btnFocused: {
    borderColor: colors.focus,
    backgroundColor: colors.glass,
  },
  btnLabel: {
    color: colors.surface200,
    fontSize: 14,
    fontWeight: '700',
  },
  btnLabelFocused: {
    color: colors.focus,
  },
});
