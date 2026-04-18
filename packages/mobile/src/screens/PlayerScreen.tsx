import React, { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  BackHandler,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import Video, { type VideoRef } from 'react-native-video';

function detectStreamType(url: string): 'm3u8' | 'mpd' | undefined {
  const u = url.toLowerCase().split('?')[0];
  if (u.includes('.m3u8')) return 'm3u8';
  if (u.includes('.mpd')) return 'mpd';
  return undefined;
}
import { TvButton } from '../components/tv/TvButton';
import { useNavStore } from '../stores/nav-store';
import { useSourcesStore } from '../stores/sources-store';

const CONTROLS_HIDE_MS = 4000;

export function PlayerScreen() {
  const back = useNavStore((s) => s.back);
  const selectedId = useNavStore((s) => s.selectedChannelId);
  const channel = useSourcesStore((s) =>
    s.channels.find((c) => c.id === selectedId),
  );

  const videoRef = useRef<VideoRef>(null);
  const [buffering, setBuffering] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [tracksInfo, setTracksInfo] = useState<string>('');
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      back();
      return true;
    });
    return () => sub.remove();
  }, [back]);

  useEffect(() => {
    scheduleHide();
    return () => {
      if (hideTimer.current) clearTimeout(hideTimer.current);
    };
  }, []);

  function scheduleHide() {
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => setControlsVisible(false), CONTROLS_HIDE_MS);
  }

  function showControls() {
    setControlsVisible(true);
    scheduleHide();
  }

  if (!channel || !channel.streamUrl) {
    return (
      <View style={styles.centered}>
        <StatusBar barStyle="light-content" backgroundColor="#000" hidden />
        <Text style={styles.title}>No stream available</Text>
        <View style={styles.backBtn}>
          <TvButton label="Back" onSelect={back} autoFocus active />
        </View>
      </View>
    );
  }

  const streamType = detectStreamType(channel.streamUrl);

  return (
    // IMPORTANT: root is a plain View, not Pressable. Wrapping <Video> inside
    // a Pressable on Android causes the SurfaceView to be obscured, producing
    // the "black video but audio plays" symptom. The tap-to-show-controls
    // surface is a sibling overlay above the Video instead.
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#000" hidden />

      <Video
        ref={videoRef}
        source={{
          uri: channel.streamUrl,
          ...(streamType ? { type: streamType } : {}),
          headers: { 'User-Agent': 'VLC/3.0.20 LibVLC/3.0.20' },
        }}
        style={StyleSheet.absoluteFill}
        resizeMode="contain"
        paused={false}
        controls={false}
        // TextureView plays nicer with React Native's view hierarchy on older
        // Android devices — avoids SurfaceView z-order / blank-frame issues.
        viewType={1}
        onLoadStart={() => {
          setBuffering(true);
          setError(null);
          setTracksInfo('');
        }}
        onBuffer={({ isBuffering }) => setBuffering(isBuffering)}
        onLoad={(data) => {
          setBuffering(false);
          const videoTracks = (data as { videoTracks?: unknown[] }).videoTracks ?? [];
          const audioTracks = (data as { audioTracks?: unknown[] }).audioTracks ?? [];
          setTracksInfo(`V:${videoTracks.length} A:${audioTracks.length}`);
        }}
        onError={(e) => {
          const err = e?.error ?? {};
          const parts = [
            err.errorString,
            err.localizedDescription,
            err.localizedFailureReason,
            err.errorCode ? `code=${err.errorCode}` : null,
          ].filter(Boolean);
          setError(parts.join(' | ') || 'Playback failed');
          setBuffering(false);
        }}
        bufferConfig={{
          minBufferMs: 15000,
          maxBufferMs: 50000,
          bufferForPlaybackMs: 2500,
          bufferForPlaybackAfterRebufferMs: 5000,
        }}
        ignoreSilentSwitch="ignore"
        progressUpdateInterval={1000}
      />

      {/* Invisible tap catcher sibling — toggles controls without wrapping Video */}
      <Pressable
        style={StyleSheet.absoluteFill}
        onPress={showControls}
        android_disableSound
      />

      {buffering && !error ? (
        <View style={styles.overlayCenter} pointerEvents="none">
          <ActivityIndicator size="large" color="#fff" />
        </View>
      ) : null}

      {error ? (
        <View style={styles.errorBox}>
          <Text style={styles.errorTitle}>Playback error</Text>
          <Text style={styles.errorDetail} numberOfLines={3}>
            {error}
          </Text>
          <View style={styles.backBtn}>
            <TvButton label="Back" onSelect={back} autoFocus active />
          </View>
        </View>
      ) : null}

      {controlsVisible && !error ? (
        <>
          <View style={styles.topBar} pointerEvents="none">
            <Text style={styles.channelName} numberOfLines={1}>
              {channel.title}
              {tracksInfo ? ` · ${tracksInfo}` : ''}
            </Text>
            {channel.groupName ? (
              <Text style={styles.group} numberOfLines={1}>
                {channel.groupName}
              </Text>
            ) : null}
          </View>
          <View style={styles.bottomBar}>
            <View style={styles.backBtn}>
              <TvButton label="Back" onSelect={back} autoFocus active />
            </View>
          </View>
        </>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#000',
  },
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#0a0a0f',
    padding: 48,
  },
  title: {
    color: '#fff',
    fontSize: 22,
    fontWeight: '700',
    marginBottom: 16,
  },
  overlayCenter: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
  },
  topBar: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    padding: 24,
    backgroundColor: 'rgba(0,0,0,0.6)',
  },
  channelName: {
    color: '#fff',
    fontSize: 22,
    fontWeight: '700',
  },
  group: {
    color: '#9ca3af',
    fontSize: 14,
    marginTop: 4,
  },
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    padding: 24,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.6)',
  },
  errorBox: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 32,
    backgroundColor: 'rgba(0,0,0,0.85)',
  },
  errorTitle: {
    color: '#f87171',
    fontSize: 20,
    fontWeight: '700',
    marginBottom: 8,
  },
  errorDetail: {
    color: '#d1d5db',
    fontSize: 14,
    textAlign: 'center',
    marginBottom: 24,
  },
  backBtn: {
    width: 160,
  },
});
