import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  BackHandler,
  FlatList,
  Modal,
  Platform,
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
  type OnProgressData,
  type OnTextTracksData,
  type SelectedTrack,
  type TextTracks,
  type VideoRef,
} from 'react-native-video';
import type { ContentMetadata, SubtitleTrack } from '@yancotv/core';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { TvButton } from '../components/tv/TvButton';
import { useSourcesStore } from '../stores/sources-store';
import { useRecentChannelsStore } from '../stores/recent-channels-store';
import {
  getLastPosition,
  recordWatch,
  updatePosition,
} from '../db/history-store';
import type {
  PlayerScreenProps,
  RootStackParamList,
} from '../navigation/RootNavigator';

type PlayerNavigation = NativeStackNavigationProp<RootStackParamList, 'Player'>;

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

const CONTROLS_HIDE_MS = 5000;
const DEFAULT_TRACK: SelectedTrack = { type: SelectedTrackType.SYSTEM };
const DEFAULT_AUDIO: SelectedTrack = { type: SelectedTrackType.SYSTEM };
const HISTORY_SAVE_INTERVAL_MS = 10_000;
const RESUME_THRESHOLD_SECONDS = 30;
const RESUME_NEAR_END_BUFFER_SECONDS = 60;

interface ResumeCandidate {
  positionSeconds: number;
  durationSeconds?: number;
}

function formatHMS(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`;
}

// TV is the bigger problem child: SurfaceView z-order bugs are well-documented
// on older Fire TV sticks and low-end Android TV boxes. TextureView is slightly
// slower but renders inside the React view tree, so overlays never occlude it.
// On phones SurfaceView is fine and cheaper.
const DEFAULT_VIEW_TYPE = Platform.isTV ? ViewType.TEXTURE : ViewType.SURFACE;

export function PlayerScreen() {
  const navigation = useNavigation<PlayerNavigation>();
  const route = useRoute<PlayerScreenProps['route']>();
  const selectedId = route.params?.channelId;
  const selectedEpisodeId = route.params?.episodeId;
  const back = useCallback(() => navigation.goBack(), [navigation]);
  const channel = useSourcesStore((s) =>
    s.channels.find((c) => c.id === selectedId),
  );

  const metadata = useMemo(() => parseMetadata(channel?.metadataJson), [
    channel?.metadataJson,
  ]);

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
  const [paused, setPaused] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [firstFrameAt, setFirstFrameAt] = useState<number | null>(null);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [viewTypeOverride, setViewTypeOverride] = useState<ViewType | null>(null);
  const [audioTracks, setAudioTracks] = useState<OnAudioTracksData['audioTracks']>([]);
  const [subTracks, setSubTracks] = useState<OnTextTracksData['textTracks']>([]);
  const [selectedAudio, setSelectedAudio] = useState<SelectedTrack>(DEFAULT_AUDIO);
  const [selectedText, setSelectedText] = useState<SelectedTrack>(DEFAULT_TRACK);
  const [pickerOpen, setPickerOpen] = useState<null | 'audio' | 'subs'>(null);
  const [resumePrompt, setResumePrompt] = useState<ResumeCandidate | null>(null);
  const [historyId, setHistoryId] = useState<string | null>(null);
  const loadStartRef = useRef<number>(0);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingSeekRef = useRef<number | null>(null);
  const lastSaveAtRef = useRef<number>(0);

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

  // Record live zaps into the recent-channels ring buffer (≤10, most-recent
  // first, persisted to AsyncStorage via the core factory). Non-live flows
  // use watch_history instead and are handled in the effect below.
  useEffect(() => {
    if (!channel || channel.type !== 'live') return;
    void useRecentChannelsStore.getState().record(channel.id);
  }, [channel]);

  // Resume check: for non-live content, look up the last saved position and
  // either offer a resume prompt (paused until the user picks) or start from
  // zero. `recordWatch` fires in parallel so we have a historyId to write
  // positions to from onProgress below.
  useEffect(() => {
    if (!channel || channel.type === 'live') return;
    const contentId = channel.id;
    const episodeId = selectedEpisodeId;
    let cancelled = false;
    (async () => {
      try {
        const prev = await getLastPosition(contentId, episodeId);
        if (cancelled) return;
        const nearEnd =
          prev?.durationSeconds != null &&
          prev.positionSeconds >
            prev.durationSeconds - RESUME_NEAR_END_BUFFER_SECONDS;
        if (prev && prev.positionSeconds > RESUME_THRESHOLD_SECONDS && !nearEnd) {
          setResumePrompt({
            positionSeconds: prev.positionSeconds,
            durationSeconds: prev.durationSeconds,
          });
          setPaused(true);
        }
        const id = await recordWatch(contentId, episodeId);
        if (!cancelled) setHistoryId(id);
      } catch {
        // Resume is a nice-to-have; fall through to normal playback if SQLite
        // is unavailable for any reason.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [channel, selectedEpisodeId]);

  const onResumeAccept = useCallback(() => {
    if (resumePrompt) pendingSeekRef.current = resumePrompt.positionSeconds;
    setResumePrompt(null);
    setPaused(false);
  }, [resumePrompt]);

  const onResumeDecline = useCallback(() => {
    pendingSeekRef.current = null;
    setResumePrompt(null);
    setPaused(false);
  }, []);

  const onProgress = useCallback(
    (data: OnProgressData) => {
      if (!historyId || resumePrompt || pendingSeekRef.current != null) return;
      const now = Date.now();
      if (now - lastSaveAtRef.current < HISTORY_SAVE_INTERVAL_MS) return;
      lastSaveAtRef.current = now;
      updatePosition(
        historyId,
        Math.floor(data.currentTime),
        data.seekableDuration > 0
          ? Math.floor(data.seekableDuration)
          : undefined,
      ).catch(() => {
        // Fire-and-forget; a dropped write will be caught by the next tick.
      });
    },
    [historyId, resumePrompt],
  );

  // On Android TV, D-pad key presses don't fire onPress on the invisible
  // tap catcher — we need the real TV event handler to wake the controls.
  useEffect(() => {
    if (!Platform.isTV) return;
    type TVEvent = {
      enable: (c: unknown, cb: () => void) => void;
      disable: () => void;
    };
    let handler: TVEvent | null = null;
    try {
      const rn = require('react-native') as {
        TVEventHandler?: new () => TVEvent;
      };
      if (rn.TVEventHandler) {
        handler = new rn.TVEventHandler();
        handler.enable(null, () => showControls());
      }
    } catch {
      // no-op — non-TV build
    }
    return () => handler?.disable();
  }, []);

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
  const effectiveViewType = viewTypeOverride ?? DEFAULT_VIEW_TYPE;
  const tracksInfo = `A:${audioTracks.length} S:${subTracks.length + textTracks.length}`;

  return (
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
        paused={paused}
        // Native Media3 controls: play/pause, seek, subtitles, audio, speed.
        // The user expects these — they're the "video controls inside the
        // player" that React-side overlays can't fully replace.
        controls
        selectedAudioTrack={selectedAudio}
        selectedTextTrack={selectedText}
        viewType={effectiveViewType}
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
        onLoad={() => {
          setBuffering(false);
          const seekTo = pendingSeekRef.current;
          if (seekTo != null) {
            videoRef.current?.seek(seekTo);
            pendingSeekRef.current = null;
          }
        }}
        onProgress={onProgress}
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
          // Flip the surface type once — if we started on TEXTURE and failed
          // with a codec-like error, try SURFACE (and vice versa).
          const codecLike = /decoder|codec|surface|mediacodec|no video track/i.test(
            message,
          );
          if (!viewTypeOverride && codecLike) {
            setViewTypeOverride(
              effectiveViewType === ViewType.TEXTURE ? ViewType.SURFACE : ViewType.TEXTURE,
            );
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

      {buffering && !error ? (
        <View style={styles.overlayCenter} pointerEvents="none">
          <ActivityIndicator size="large" color="#fff" />
        </View>
      ) : null}

      {error ? (
        <View style={styles.errorBox}>
          <Text style={styles.errorTitle}>Playback error</Text>
          <Text style={styles.errorDetail} numberOfLines={4}>
            {error}
          </Text>
          <View style={styles.errorActions}>
            <View style={styles.backBtn}>
              <TvButton label="Back" onSelect={back} autoFocus active />
            </View>
            <View style={styles.backBtn}>
              <TvButton
                label="Retry"
                onSelect={() => {
                  setError(null);
                  setBuffering(true);
                  setViewTypeOverride(
                    effectiveViewType === ViewType.TEXTURE
                      ? ViewType.SURFACE
                      : ViewType.TEXTURE,
                  );
                }}
              />
            </View>
          </View>
        </View>
      ) : null}

      {controlsVisible && !error ? (
        <>
          <View style={styles.topBar} pointerEvents="box-none">
            <View style={styles.topBarText} pointerEvents="none">
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
                  `view=${effectiveViewType === ViewType.TEXTURE ? 'texture' : 'surface'}`,
                  tracksInfo,
                  firstFrameAt !== null ? `frame=${firstFrameAt}ms` : null,
                ]
                  .filter(Boolean)
                  .join(' · ')}
              </Text>
            </View>
            <View style={styles.topBarBtns}>
              <View style={styles.backBtn}>
                <TvButton label="Back" onSelect={back} active />
              </View>
              <View style={styles.backBtn}>
                <TvButton
                  label={paused ? 'Play' : 'Pause'}
                  onSelect={() => {
                    setPaused((p) => !p);
                    showControls();
                  }}
                />
              </View>
              {audioTracks.length > 1 ? (
                <View style={styles.backBtn}>
                  <TvButton label={`Audio (${audioTracks.length})`} onSelect={() => setPickerOpen('audio')} />
                </View>
              ) : null}
              {subTracks.length + textTracks.length > 0 ? (
                <View style={styles.backBtn}>
                  <TvButton
                    label={`Subs (${subTracks.length + textTracks.length})`}
                    onSelect={() => setPickerOpen('subs')}
                  />
                </View>
              ) : null}
            </View>
          </View>
        </>
      ) : null}

      <ResumePromptModal
        candidate={resumePrompt}
        onResume={onResumeAccept}
        onStartOver={onResumeDecline}
      />

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

interface ResumePromptModalProps {
  candidate: ResumeCandidate | null;
  onResume: () => void;
  onStartOver: () => void;
}

function ResumePromptModal({
  candidate,
  onResume,
  onStartOver,
}: ResumePromptModalProps) {
  if (!candidate) return null;
  const resumeAt = formatHMS(candidate.positionSeconds);
  const total = candidate.durationSeconds
    ? ` / ${formatHMS(candidate.durationSeconds)}`
    : '';
  return (
    <Modal
      transparent
      animationType="fade"
      visible
      onRequestClose={onStartOver}
    >
      <View style={styles.modalBackdrop}>
        <View style={styles.resumeCard}>
          <Text style={styles.modalTitle}>Resume where you left off?</Text>
          <Text style={styles.resumeDetail}>
            {resumeAt}
            {total}
          </Text>
          <View style={styles.resumeActions}>
            <View style={styles.resumeBtn}>
              <TvButton
                label={`Resume from ${resumeAt}`}
                onSelect={onResume}
                autoFocus
                active
              />
            </View>
            <View style={styles.resumeBtn}>
              <TvButton label="Start over" onSelect={onStartOver} />
            </View>
          </View>
        </View>
      </View>
    </Modal>
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
    padding: 20,
    backgroundColor: 'rgba(0,0,0,0.55)',
    flexDirection: 'row',
    alignItems: 'flex-start',
  },
  topBarText: {
    flex: 1,
    marginRight: 16,
  },
  topBarBtns: {
    flexDirection: 'row',
    alignItems: 'center',
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
  backBtn: {
    width: 140,
    marginLeft: 8,
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
  errorActions: {
    flexDirection: 'row',
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
  resumeCard: {
    width: 440,
    maxWidth: '90%',
    backgroundColor: '#111827',
    borderRadius: 16,
    padding: 24,
    alignItems: 'center',
  },
  resumeDetail: {
    color: '#9ca3af',
    fontSize: 14,
    marginTop: -4,
    marginBottom: 20,
    fontFamily: 'monospace',
    letterSpacing: 0.5,
  },
  resumeActions: {
    flexDirection: 'row',
    gap: 12,
  },
  resumeBtn: {
    minWidth: 160,
  },
});
