import React, { useEffect } from 'react';
import { BackHandler, StatusBar, StyleSheet, View } from 'react-native';
import Video, { ViewType } from 'react-native-video';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type {
  FullscreenPlayerProps,
  RootStackParamList,
} from '../navigation/RootNavigator';

type Nav = NativeStackNavigationProp<RootStackParamList, 'FullscreenPlayer'>;

// Stub player for M4R.1/M4R.2 — just proves the route works.
// The real player (persistent MiniPlayer surface, controls, audio track
// selection, subtitles, history, resume) lands in M4R.7.
function detectStreamType(url: string): 'm3u8' | 'mpd' | 'ism' | undefined {
  const u = url.toLowerCase().split('?')[0];
  if (u.includes('.m3u8')) return 'm3u8';
  if (u.includes('.mpd')) return 'mpd';
  if (u.includes('.ism')) return 'ism';
  return undefined;
}

export function FullscreenPlayer() {
  const navigation = useNavigation<Nav>();
  const route = useRoute<FullscreenPlayerProps['route']>();
  const { url } = route.params;

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      navigation.goBack();
      return true;
    });
    return () => sub.remove();
  }, [navigation]);

  return (
    <View style={styles.root}>
      <StatusBar hidden />
      <Video
        source={{ uri: url, type: detectStreamType(url) }}
        style={styles.video}
        resizeMode="contain"
        controls={false}
        paused={false}
        viewType={ViewType.SURFACE}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#000',
  },
  video: {
    flex: 1,
  },
});
