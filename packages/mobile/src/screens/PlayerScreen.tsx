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
import Video, { ViewType, type VideoRef } from 'react-native-video';
import { TvButton } from '../components/tv/TvButton';
import { useNavStore } from '../stores/nav-store';
import { useSourcesStore } from '../stores/sources-store';

// In react-native-video v6 the `type` prop only accepts these MIME hints.
// For MPEG-TS (.ts) and file containers (.mp4/.mkv/.avi/.mov) we intentionally
// return undefined — Media3's extractor chain sniffs the container and picks
// the right decoder. Forcing `type` on a non-HLS/DASH stream misconfigures
// the player and produces the classic black-with-audio symptom.
function detectStreamType(url: string): 'm3u8' | 'mpd' | 'ism' | undefined {
  const u = url.toLowerCase().split('?')[0];
  if (u.includes('.m3u8')) return 'm3u8';
  if (u.includes('.mpd')) return 'mpd';
  if (u.includes('.ism')) return 'ism';
  return undefined;
}

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
  const [firstFrameAt, setFirstFrameAt] = useState<number | null>(null);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [tracksInfo, setTracksInfo] = useState<string>('');
  const [useTextureFallback, setUseTextureFallback] = useState(false);
  const loadStartRef = useRef<number>(0);
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
        // Default is SurfaceView (Media3-preferred: faster, lower battery).
        // If playback errors out we flip to TextureView as a fallback — some
        // older Fire TV / Android TV boxes have SurfaceView z-order bugs.
        viewType={useTextureFallback ? ViewType.TEXTURE : ViewType.SURFACE}
        // Default shutter is opaque black, which can mask frames after load
        // on slower decoders. Transparent keeps the root View's background.
        shutterColor="transparent"
        onLoadStart={() => {
          loadStartRef.current = Date.now();
          setBuffering(true);
          setError(null);
          setFirstFrameAt(null);
          setTracksInfo('');
        }}
        onBuffer={({ isBuffering }) => setBuffering(isBuffering)}
        onLoad={(data) => {
          setBuffering(false);
          const videoTracks = (data as { videoTracks?: unknown[] }).videoTracks ?? [];
          const audioTracks = (data as { audioTracks?: unknown[] }).audioTracks ?? [];
          const textTracks = (data as { textTracks?: unknown[] }).textTracks ?? [];
          setTracksInfo(
            `V:${videoTracks.length} A:${audioTracks.length} S:${textTracks.length}`,
          );
        }}
        onReadyForDisplay={() => {
          // First actual decoded frame reached the surface. If this never
          // fires but onLoad did, we have audio-without-video — the usual
          // surface/codec problem.
          const elapsed = Date.now() - (loadStartRef.current || Date.now());
          setFirstFrameAt(elapsed);
          setBuffering(false);
        }}
        onError={(e) => {
          const err = (e?.error ?? {}) as {
            errorString?: string;
            errorException?: string;
            localizedDescription?: string;
            localizedFailureReason?: string;
            errorCode?: string | number;
          };
          const parts = [
            err.errorString,
            err.errorException,
            err.localizedDescription,
            err.localizedFailureReason,
            err.errorCode ? `code=${err.errorCode}` : null,
          ].filter(Boolean);
          const message = parts.join(' | ') || 'Playback failed';
          // Auto-recover once by swapping the surface. Many "black screen /
          // decoder init failed" cases on older Android TV boxes clear up on
          // TextureView. If the fallback ALSO fails we show the error.
          const codecLike = /decoder|codec|surface|mediacodec|no video track/i.test(
            message,
          );
          if (!useTextureFallback && codecLike) {
            setUseTextureFallback(true);
            setBuffering(true);
            return;
          }
          setError(message);
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
            </Text>
            {channel.groupName ? (
              <Text style={styles.group} numberOfLines={1}>
                {channel.groupName}
              </Text>
            ) : null}
            <Text style={styles.diag} numberOfLines={1}>
              {[
                streamType ? `type=${streamType}` : 'type=auto',
                useTextureFallback ? 'surface=texture' : 'surface=default',
                tracksInfo || null,
                firstFrameAt !== null ? `frame=${firstFrameAt}ms` : null,
              ]
                .filter(Boolean)
                .join(' · ')}
            </Text>
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
  diag: {
    color: '#6b7280',
    fontSize: 10,
    marginTop: 6,
    fontFamily: 'monospace',
    letterSpacing: 0.3,
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
