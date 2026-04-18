import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  BackHandler,
  FlatList,
  Modal,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import Video, {
  SelectedTrackType,
  TextTrackType,
  ViewType,
  type OnAudioTracksData,
  type OnTextTracksData,
  type SelectedTrack,
  type TextTracks,
  type VideoRef,
} from 'react-native-video';
import type { ContentMetadata, SubtitleTrack } from '@yancotv/core';
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

function subtitleMime(url: string): TextTrackType {
  const u = url.toLowerCase().split('?')[0];
  if (u.endsWith('.vtt')) return TextTrackType.VTT;
  if (u.endsWith('.ttml') || u.endsWith('.dfxp') || u.endsWith('.xml')) {
    return TextTrackType.TTML;
  }
  return TextTrackType.SUBRIP;
}

function parseMetadata(json: string | undefined): ContentMetadata {
  if (!json) return {};
  try {
    return JSON.parse(json) as ContentMetadata;
  } catch {
    return {};
  }
}

function buildTextTracks(subs: SubtitleTrack[] | undefined): TextTracks {
  if (!subs || !subs.length) return [];
  return subs.map((s, i) => ({
    title: s.language || `Subtitle ${i + 1}`,
    language: (s.language || 'und').slice(0, 2).toLowerCase() as TextTracks[number]['language'],
    type: subtitleMime(s.url),
    uri: s.url,
  }));
}

const CONTROLS_HIDE_MS = 4000;
const DEFAULT_TRACK: SelectedTrack = { type: SelectedTrackType.SYSTEM };
const DEFAULT_AUDIO: SelectedTrack = { type: SelectedTrackType.SYSTEM };

export function PlayerScreen() {
  const back = useNavStore((s) => s.back);
  const selectedId = useNavStore((s) => s.selectedChannelId);
  const selectedEpisodeId = useNavStore((s) => s.selectedEpisodeId);
  const channel = useSourcesStore((s) =>
    s.channels.find((c) => c.id === selectedId),
  );

  const metadata = useMemo(() => parseMetadata(channel?.metadataJson), [
    channel?.metadataJson,
  ]);

  // Resolve effective stream: episode (if series) or main item streamUrl.
  const { streamUrl, displayTitle, displaySubtitle } = useMemo(() => {
    if (!channel) {
      return { streamUrl: undefined, displayTitle: '', displaySubtitle: '' };
    }
    if (selectedEpisodeId && metadata.episodes?.length) {
      const ep = metadata.episodes.find((e) => e.id === selectedEpisodeId);
      if (ep) {
        return {
          streamUrl: ep.streamUrl,
          displayTitle: channel.title,
          displaySubtitle: `S${ep.seasonNumber} E${ep.episodeNumber} · ${ep.title}`,
        };
      }
    }
    return {
      streamUrl: channel.streamUrl,
      displayTitle: channel.title,
      displaySubtitle: channel.groupName || '',
    };
  }, [channel, selectedEpisodeId, metadata.episodes]);

  const textTracks = useMemo<TextTracks>(
    () => buildTextTracks(metadata.subtitles),
    [metadata.subtitles],
  );

  const videoRef = useRef<VideoRef>(null);
  const [buffering, setBuffering] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [firstFrameAt, setFirstFrameAt] = useState<number | null>(null);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [useTextureFallback, setUseTextureFallback] = useState(false);
  const [audioTracks, setAudioTracks] = useState<OnAudioTracksData['audioTracks']>([]);
  const [subTracks, setSubTracks] = useState<OnTextTracksData['textTracks']>([]);
  const [selectedAudio, setSelectedAudio] = useState<SelectedTrack>(DEFAULT_AUDIO);
  const [selectedText, setSelectedText] = useState<SelectedTrack>(DEFAULT_TRACK);
  const [pickerOpen, setPickerOpen] = useState<null | 'audio' | 'subs'>(null);
  const loadStartRef = useRef<number>(0);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (pickerOpen) {
        setPickerOpen(null);
        return true;
      }
      back();
      return true;
    });
    return () => sub.remove();
  }, [back, pickerOpen]);

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

  if (!channel || !streamUrl) {
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

  const streamType = detectStreamType(streamUrl);
  const tracksInfo = `A:${audioTracks.length} S:${subTracks.length + textTracks.length}`;

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
          uri: streamUrl,
          ...(streamType ? { type: streamType } : {}),
          headers: { 'User-Agent': 'VLC/3.0.20 LibVLC/3.0.20' },
          textTracks: textTracks.length ? textTracks : undefined,
        }}
        style={StyleSheet.absoluteFill}
        resizeMode="contain"
        paused={false}
        controls={false}
        selectedAudioTrack={selectedAudio}
        selectedTextTrack={selectedText}
        viewType={useTextureFallback ? ViewType.TEXTURE : ViewType.SURFACE}
        shutterColor="transparent"
        onLoadStart={() => {
          loadStartRef.current = Date.now();
          setBuffering(true);
          setError(null);
          setFirstFrameAt(null);
          setAudioTracks([]);
          setSubTracks([]);
        }}
        onBuffer={({ isBuffering }) => setBuffering(isBuffering)}
        onLoad={() => setBuffering(false)}
        onAudioTracks={(e) => setAudioTracks(e.audioTracks)}
        onTextTracks={(e) => setSubTracks(e.textTracks)}
        onReadyForDisplay={() => {
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
              {displayTitle}
            </Text>
            {displaySubtitle ? (
              <Text style={styles.group} numberOfLines={1}>
                {displaySubtitle}
              </Text>
            ) : null}
            <Text style={styles.diag} numberOfLines={1}>
              {[
                streamType ? `type=${streamType}` : 'type=auto',
                useTextureFallback ? 'surface=texture' : 'surface=default',
                tracksInfo,
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
            <View style={styles.spacer} />
            {audioTracks.length > 1 ? (
              <View style={styles.trackBtn}>
                <TvButton
                  label={`Audio (${audioTracks.length})`}
                  onSelect={() => setPickerOpen('audio')}
                />
              </View>
            ) : null}
            {subTracks.length + textTracks.length > 0 ? (
              <View style={styles.trackBtn}>
                <TvButton
                  label={`Subs (${subTracks.length + textTracks.length})`}
                  onSelect={() => setPickerOpen('subs')}
                />
              </View>
            ) : null}
          </View>
        </>
      ) : null}

      <TrackPickerModal
        open={pickerOpen}
        audioTracks={audioTracks}
        subTracks={subTracks}
        selectedAudio={selectedAudio}
        selectedText={selectedText}
        onSelectAudio={(t) => {
          setSelectedAudio(t);
          setPickerOpen(null);
        }}
        onSelectText={(t) => {
          setSelectedText(t);
          setPickerOpen(null);
        }}
        onClose={() => setPickerOpen(null)}
      />
    </View>
  );
}

interface TrackPickerProps {
  open: null | 'audio' | 'subs';
  audioTracks: OnAudioTracksData['audioTracks'];
  subTracks: OnTextTracksData['textTracks'];
  selectedAudio: SelectedTrack;
  selectedText: SelectedTrack;
  onSelectAudio: (t: SelectedTrack) => void;
  onSelectText: (t: SelectedTrack) => void;
  onClose: () => void;
}

function TrackPickerModal({
  open,
  audioTracks,
  subTracks,
  selectedAudio,
  selectedText,
  onSelectAudio,
  onSelectText,
  onClose,
}: TrackPickerProps) {
  if (!open) return null;
  const isAudio = open === 'audio';
  const title = isAudio ? 'Audio track' : 'Subtitles';

  type Row = { key: string; label: string; onPress: () => void; selected: boolean };
  const rows: Row[] = [];

  if (isAudio) {
    audioTracks.forEach((t) => {
      const label = [t.language, t.title].filter(Boolean).join(' · ') || `Track ${t.index + 1}`;
      const selected =
        selectedAudio.type === SelectedTrackType.INDEX && selectedAudio.value === t.index;
      rows.push({
        key: `a-${t.index}`,
        label,
        selected,
        onPress: () => onSelectAudio({ type: SelectedTrackType.INDEX, value: t.index }),
      });
    });
    if (!rows.length) {
      rows.push({
        key: 'a-sys',
        label: 'System default',
        selected: true,
        onPress: () => onSelectAudio({ type: SelectedTrackType.SYSTEM }),
      });
    }
  } else {
    rows.push({
      key: 's-off',
      label: 'Off',
      selected: selectedText.type === SelectedTrackType.DISABLED,
      onPress: () => onSelectText({ type: SelectedTrackType.DISABLED }),
    });
    subTracks.forEach((t) => {
      const label = [t.language, t.title].filter(Boolean).join(' · ') || `Subtitle ${t.index + 1}`;
      const selected =
        selectedText.type === SelectedTrackType.INDEX && selectedText.value === t.index;
      rows.push({
        key: `s-${t.index}`,
        label,
        selected,
        onPress: () => onSelectText({ type: SelectedTrackType.INDEX, value: t.index }),
      });
    });
  }

  return (
    <Modal transparent animationType="fade" visible onRequestClose={onClose}>
      <Pressable style={styles.modalBackdrop} onPress={onClose}>
        <Pressable style={styles.modalCard} onPress={() => {}}>
          <Text style={styles.modalTitle}>{title}</Text>
          <FlatList
            data={rows}
            keyExtractor={(r) => r.key}
            renderItem={({ item }) => (
              <TvButton
                label={`${item.selected ? '●  ' : '○  '}${item.label}`}
                onSelect={item.onPress}
                active={item.selected}
              />
            )}
            ItemSeparatorComponent={() => <View style={styles.modalSep} />}
          />
        </Pressable>
      </Pressable>
    </Modal>
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
  spacer: { flex: 1 },
  backBtn: {
    width: 160,
  },
  trackBtn: {
    width: 200,
    marginLeft: 12,
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
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.7)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  modalCard: {
    width: 480,
    maxHeight: '80%',
    backgroundColor: '#111827',
    borderRadius: 16,
    padding: 20,
  },
  modalTitle: {
    color: '#fff',
    fontSize: 20,
    fontWeight: '700',
    marginBottom: 16,
  },
  modalSep: {
    height: 8,
  },
});
