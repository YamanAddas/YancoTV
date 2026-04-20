import React, { useCallback, useRef } from 'react';
import { Platform, StyleSheet, View } from 'react-native';
import Video, {
  ViewType,
  type OnLoadData,
  type OnVideoErrorData,
  type OnPlaybackStateChangedData,
  type OnBufferData,
} from 'react-native-video';
import { usePlayerStore } from '../stores/player-store';
import { Sentry } from '../sentry';
import { spacing } from '../styles/theme';

// The single <Video> instance in the mobile app. Lives as a root-level
// sibling of the HomeShell tree (see HomeShell.tsx), so it survives route
// transitions into FullscreenPlayer — which is itself a transparentModal
// over the shell, not a separate surface (M4R rule 5, fixes MB-13).
//
// The mini geometry MUST match HomeShell's playerSlot (right column,
// spacing.md padding, 16:9). Keep those two in sync if HomeShell changes.
//
// viewType={TEXTURE} — upstream ExoPlayerView.updateSurfaceView is a no-op
// stub in react-native-video 6.19.1 (the 2026-04-20 audit verified this at
// ExoPlayerView.kt:142). Left here intentionally: the prop is forwarded to
// the native view manager and will become real once we land the
// patch-package fix (M4R.Audit.4). Until then Android falls back to
// SurfaceView, which is the source of part of MB-14.
//
// M4R.Audit.2 wires five diagnostic callbacks so the next black-screen
// report tells us WHICH layer broke — surface composition (no onLoad /
// no onReadyForDisplay), codec failure (onError with a specific
// errorCode), or buffer stall (onBuffer stuck true). Every log carries a
// redacted URL + stream-type so Sentry breadcrumbs correlate across a
// session.

function detectStreamType(url: string): 'm3u8' | 'mpd' | 'ism' | undefined {
  const u = url.toLowerCase().split('?')[0];
  if (u.includes('.m3u8')) return 'm3u8';
  if (u.includes('.mpd')) return 'mpd';
  if (u.includes('.ism')) return 'ism';
  return undefined;
}

// Xtream URLs are `http://host:port/<user>/<pass>/<stream>.ext`. Collapse
// user+pass to `***` so Sentry breadcrumbs never leak credentials.
function redactUrl(url: string): string {
  try {
    return url.replace(
      /^(https?:\/\/[^/]+)\/[^/]+\/[^/]+\/(.+)$/,
      '$1/***/***/$2',
    );
  } catch {
    return '[unparseable-url]';
  }
}

const RIGHT_WIDTH = 360;

export function PersistentPlayerHost() {
  const track = usePlayerStore((s) => s.track);
  const isFullscreen = usePlayerStore((s) => s.isFullscreen);
  const isPaused = usePlayerStore((s) => s.isPaused);

  // Only breadcrumb the first onReadyForDisplay per track — the event can
  // fire again on resize/track-change and floods Sentry otherwise.
  const readyLoggedRef = useRef<string | null>(null);

  const urlRef = useRef<string>('');
  urlRef.current = track?.url ?? '';
  const streamType = track ? detectStreamType(track.url) : undefined;

  const crumb = useCallback(
    (stage: string, data?: Record<string, unknown>) => {
      Sentry.addBreadcrumb({
        category: 'player',
        level: 'info',
        message: `player.${stage}`,
        data: { url: redactUrl(urlRef.current), streamType, ...data },
      });
      if (__DEV__) {
        // eslint-disable-next-line no-console
        console.log(`[player.${stage}]`, redactUrl(urlRef.current), data ?? '');
      }
    },
    [streamType],
  );

  const onLoad = useCallback(
    (e: OnLoadData) => {
      crumb('onLoad', {
        w: e.naturalSize.width,
        h: e.naturalSize.height,
        audioTracks: e.audioTracks.length,
        textTracks: e.textTracks.length,
        duration: e.duration,
      });
    },
    [crumb],
  );

  const onReadyForDisplay = useCallback(() => {
    const key = urlRef.current;
    if (readyLoggedRef.current === key) return;
    readyLoggedRef.current = key;
    crumb('onReadyForDisplay');
  }, [crumb]);

  const onError = useCallback(
    (e: OnVideoErrorData) => {
      const { errorCode, errorString, errorException } = e.error;
      crumb('onError', { errorCode, errorString });
      Sentry.captureException(new Error(errorString || 'Video error'), {
        tags: { scope: 'player', errorCode: errorCode ?? 'unknown' },
        extra: {
          url: redactUrl(urlRef.current),
          streamType,
          errorException,
        },
      });
    },
    [crumb, streamType],
  );

  const onPlaybackStateChanged = useCallback(
    (e: OnPlaybackStateChangedData) => {
      crumb('onPlaybackStateChanged', {
        isPlaying: e.isPlaying,
        isSeeking: e.isSeeking,
      });
    },
    [crumb],
  );

  const onBuffer = useCallback(
    (e: OnBufferData) => {
      crumb('onBuffer', { isBuffering: e.isBuffering });
    },
    [crumb],
  );

  if (!track) return null;

  const wrapperStyle = isFullscreen
    ? styles.fullscreen
    : Platform.isTV
      ? styles.miniTv
      : styles.miniPhone;

  return (
    <View style={wrapperStyle} pointerEvents="none">
      <Video
        source={{ uri: track.url, type: streamType }}
        style={StyleSheet.absoluteFill}
        resizeMode="contain"
        controls={false}
        paused={isPaused}
        viewType={ViewType.TEXTURE}
        onLoad={onLoad}
        onReadyForDisplay={onReadyForDisplay}
        onError={onError}
        onPlaybackStateChanged={onPlaybackStateChanged}
        onBuffer={onBuffer}
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
