import { useCallback, useEffect, useRef, useState } from 'react';
import { usePlayerStore } from '../../stores/player-store';

interface TheaterControlsProps {
  visible: boolean;
  onInteraction: () => void;
}

/**
 * Auto-hiding transparent controls overlay for Theater mode.
 * Appears on mouse movement, disappears after inactivity.
 */
export function TheaterControls({ visible, onInteraction }: TheaterControlsProps) {
  const {
    status,
    currentTitle,
    position,
    duration,
    volume,
    muted,
    speed,
    aspectRatio,
    subtitleTracks,
    audioTracks,
    error,
    fullscreen,
  } = usePlayerStore();
  const pause = usePlayerStore((s) => s.pause);
  const resume = usePlayerStore((s) => s.resume);
  const stop = usePlayerStore((s) => s.stop);
  const seek = usePlayerStore((s) => s.seek);
  const setVolume = usePlayerStore((s) => s.setVolume);
  const toggleMute = usePlayerStore((s) => s.toggleMute);
  const toggleFullscreen = usePlayerStore((s) => s.toggleFullscreen);
  const toggleSettings = usePlayerStore((s) => s.toggleSettings);
  const openSettings = usePlayerStore((s) => s.openSettings);
  const toggleAspectMenu = usePlayerStore((s) => s.toggleAspectMenu);
  const takeScreenshot = usePlayerStore((s) => s.takeScreenshot);

  const seekBarRef = useRef<HTMLInputElement>(null);
  const volumeBarRef = useRef<HTMLInputElement>(null);

  const currentUrl = usePlayerStore((s) => s.currentUrl);
  const currentContentId = usePlayerStore((s) => s.currentContentId);
  const [recordingId, setRecordingId] = useState<string | null>(null);
  const [recordingBusy, setRecordingBusy] = useState(false);
  const [recordingProgress, setRecordingProgress] = useState<{
    durationSeconds: number;
    fileSizeBytes: number;
  } | null>(null);

  // Reset when the stream changes.
  useEffect(() => {
    setRecordingId(null);
    setRecordingProgress(null);
  }, [currentUrl]);

  // Subscribe to recording status so external stops/failures clear the button.
  useEffect(() => {
    if (!window.api?.recording?.onStatus) return;
    return window.api.recording.onStatus((rec) => {
      if (recordingId && rec.id === recordingId && rec.status !== 'recording') {
        setRecordingId(null);
        setRecordingProgress(null);
      }
    });
  }, [recordingId]);

  // Live progress — update the REC badge with elapsed time + current size.
  useEffect(() => {
    if (!recordingId || !window.api?.recording?.onProgress) return;
    return window.api.recording.onProgress((p) => {
      if (p.id === recordingId) {
        setRecordingProgress({
          durationSeconds: p.durationSeconds,
          fileSizeBytes: p.fileSizeBytes,
        });
      }
    });
  }, [recordingId]);

  const handleToggleRecord = useCallback(async () => {
    if (recordingBusy) return;
    onInteraction();
    setRecordingBusy(true);
    try {
      if (recordingId) {
        await window.api.recording.stop(recordingId);
        setRecordingId(null);
      } else {
        if (!currentUrl) return;
        const res = await window.api.recording.start({
          contentId: currentContentId,
          title: currentTitle || 'Recording',
          streamUrl: currentUrl,
        });
        if (res.ok) {
          setRecordingId(res.id);
        } else {
          // eslint-disable-next-line no-alert
          alert(`Could not start recording: ${res.error}`);
        }
      }
    } finally {
      setRecordingBusy(false);
    }
  }, [recordingBusy, recordingId, currentUrl, currentContentId, currentTitle, onInteraction]);

  const isLive = (!duration || !isFinite(duration) || duration === 0) && status === 'playing';
  const isVod = duration > 0 && isFinite(duration);
  const hasSubtitles = subtitleTracks.length > 0;
  const hasMultipleAudio = audioTracks.length > 1;

  const handlePlayPause = useCallback(() => {
    if (status === 'playing' || status === 'buffering') {
      pause();
    } else if (status === 'paused') {
      resume();
    }
  }, [status, pause, resume]);

  const handleSeek = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      seek(Number(e.target.value));
      onInteraction();
    },
    [seek, onInteraction],
  );

  const handleVolume = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      setVolume(Number(e.target.value));
      onInteraction();
    },
    [setVolume, onInteraction],
  );

  const handleSkipBack = useCallback(() => {
    if (position > 0) {
      seek(Math.max(0, position - 10));
      onInteraction();
    }
  }, [position, seek, onInteraction]);

  const handleSkipForward = useCallback(() => {
    if (isVod) {
      seek(Math.min(duration, position + 10));
      onInteraction();
    }
  }, [isVod, duration, position, seek, onInteraction]);

  // The top-left "Back" button exits theater mode by stopping playback.
  // A mini-player / PIP variant was planned (Sprint 12B / 16) but dropped
  // in 0.2.0 — see CHANGELOG. "Back = stop" matches the button's label.
  const handleBack = useCallback(() => {
    stop();
  }, [stop]);

  return (
    <div
      className={`absolute inset-0 z-[45] flex flex-col justify-between transition-opacity duration-300 ${
        visible ? 'opacity-100' : 'pointer-events-none opacity-0'
      }`}
      onMouseDown={onInteraction}
    >
      {/* Top bar — title + back */}
      <div className="bg-gradient-to-b from-black/70 to-transparent px-6 pb-12 pt-4">
        <div className="flex items-center gap-3">
          <button
            onClick={handleBack}
            className="flex h-9 w-9 items-center justify-center rounded-full bg-white/10 text-white/80 backdrop-blur-sm transition-colors hover:bg-white/20 hover:text-white"
            title="Back (Esc)"
          >
            <ArrowLeftIcon />
          </button>
          <div className="min-w-0 flex-1">
            <p className="truncate text-lg font-semibold text-white drop-shadow-lg">
              {currentTitle || 'Playing'}
            </p>
            {error && (
              <p className="truncate text-sm text-red-400">{error}</p>
            )}
          </div>
          {recordingId && (
            <span className="flex items-center gap-1.5 rounded-full bg-red-600/95 px-3 py-1 text-xs font-bold uppercase tracking-wider text-white shadow-lg">
              <span className="h-2 w-2 animate-pulse rounded-full bg-white" />
              REC
              {recordingProgress && (
                <span className="font-semibold normal-case tabular-nums tracking-normal text-white/90">
                  {formatDuration(recordingProgress.durationSeconds)}
                  {recordingProgress.fileSizeBytes > 0 && (
                    <> · {formatBytes(recordingProgress.fileSizeBytes)}</>
                  )}
                </span>
              )}
            </span>
          )}
          {isLive && (
            <span className="flex items-center gap-1.5 rounded-full bg-red-500/90 px-3 py-1 text-xs font-bold uppercase tracking-wider text-white shadow-lg">
              <span className="h-2 w-2 animate-pulse rounded-full bg-white" />
              Live
            </span>
          )}
        </div>
      </div>

      {/* Center — large play/pause (shown when paused) */}
      {status === 'paused' && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <button
            onClick={handlePlayPause}
            className="pointer-events-auto flex h-20 w-20 items-center justify-center rounded-full bg-accent/90 text-surface-950 shadow-glow-lg transition-transform hover:scale-110"
          >
            <PlayIcon className="h-10 w-10 ml-1" />
          </button>
        </div>
      )}

      {/* Bottom bar — seek + controls */}
      <div className="bg-gradient-to-t from-black/80 to-transparent px-6 pb-5 pt-16">
        {/* Seek bar — full width (VOD only) */}
        {isVod && (
          <div className="mb-3 flex items-center gap-3">
            <span className="w-16 text-right text-xs tabular-nums text-white/70">
              {formatTime(position)}
            </span>
            <div className="relative flex-1">
              <input
                ref={seekBarRef}
                type="range"
                min={0}
                max={Math.floor(duration)}
                value={Math.floor(position)}
                onChange={handleSeek}
                className="seek-bar h-1.5 w-full cursor-pointer appearance-none rounded-full bg-white/20 transition-all hover:h-2 [&::-webkit-slider-thumb]:h-4 [&::-webkit-slider-thumb]:w-4 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-accent [&::-webkit-slider-thumb]:shadow-glow-sm [&::-webkit-slider-thumb]:transition-transform [&::-webkit-slider-thumb]:hover:scale-125"
                style={{
                  background: `linear-gradient(to right, #00FFAA ${(position / duration) * 100}%, rgba(255,255,255,0.2) ${(position / duration) * 100}%)`,
                }}
              />
            </div>
            <span className="w-16 text-xs tabular-nums text-white/70">
              {formatTime(duration)}
            </span>
          </div>
        )}

        {/* Control buttons row */}
        <div className="flex items-center gap-2">
          {/* Left: transport controls */}
          <div className="flex items-center gap-1">
            {/* Skip back 10s */}
            {isVod && (
              <ControlButton onClick={handleSkipBack} title="Back 10s (Left)">
                <SkipBackIcon />
              </ControlButton>
            )}

            {/* Play/Pause */}
            <button
              onClick={handlePlayPause}
              className="flex h-10 w-10 items-center justify-center rounded-full bg-accent text-surface-950 shadow-glow-sm transition-all hover:bg-accent-hover hover:shadow-glow hover:scale-105"
              title={status === 'playing' ? 'Pause (Space)' : 'Play (Space)'}
            >
              {status === 'playing' || status === 'buffering' ? (
                <PauseIcon className="h-5 w-5" />
              ) : (
                <PlayIcon className="h-5 w-5 ml-0.5" />
              )}
            </button>

            {/* Skip forward 10s */}
            {isVod && (
              <ControlButton onClick={handleSkipForward} title="Forward 10s (Right)">
                <SkipForwardIcon />
              </ControlButton>
            )}

            {/* Stop */}
            <ControlButton onClick={stop} title="Stop">
              <StopIcon />
            </ControlButton>
          </div>

          {/* Center: title or spacer */}
          <div className="flex-1" />

          {/* Right: feature controls */}
          <div className="flex items-center gap-1">
            {/* Speed (VOD only) */}
            {isVod && (
              <ControlButton
                onClick={() => openSettings('speed')}
                title={`Speed: ${speed}x`}
                active={speed !== 1}
              >
                <span className="text-xs font-semibold">{speed}x</span>
              </ControlButton>
            )}

            {/* Aspect ratio — compact popover, distinct from the full panel */}
            <ControlButton
              onClick={toggleAspectMenu}
              title={`Aspect: ${aspectRatio}`}
              active={aspectRatio !== 'auto'}
            >
              <AspectRatioIcon />
            </ControlButton>

            {/* Subtitles — always visible so OpenSubtitles is reachable even
                when no embedded tracks exist */}
            <ControlButton
              onClick={() => openSettings('subtitles')}
              title="Subtitles (S)"
              active={hasSubtitles && subtitleTracks.some((t) => t.selected)}
            >
              <SubtitleIcon />
            </ControlButton>

            {/* Audio tracks */}
            {hasMultipleAudio && (
              <ControlButton onClick={() => openSettings('audio')} title="Audio tracks">
                <AudioTrackIcon />
              </ControlButton>
            )}

            {/* Screenshot */}
            <ControlButton
              onClick={() => {
                takeScreenshot();
              }}
              title="Screenshot"
            >
              <CameraIcon />
            </ControlButton>

            {/* Record — works for any live/VOD stream */}
            <button
              onClick={handleToggleRecord}
              disabled={recordingBusy || !currentUrl}
              className={`flex h-9 w-9 items-center justify-center rounded-lg transition-colors disabled:opacity-40 ${
                recordingId
                  ? 'bg-red-500/20 text-red-400 hover:bg-red-500/30'
                  : 'text-white/70 hover:bg-white/10 hover:text-white'
              }`}
              title={recordingId ? 'Stop recording' : 'Record'}
            >
              {recordingId ? (
                <span className="flex items-center justify-center">
                  <span className="h-2.5 w-2.5 animate-pulse rounded-full bg-red-400" />
                </span>
              ) : (
                <RecordIcon />
              )}
            </button>

            {/* Settings gear — full panel, defaults to Info tab */}
            <ControlButton onClick={toggleSettings} title="Settings (G)">
              <SettingsIcon />
            </ControlButton>

            {/* Volume */}
            <div className="flex items-center gap-1">
              <ControlButton onClick={toggleMute} title={muted ? 'Unmute (M)' : 'Mute (M)'}>
                {muted || volume === 0 ? <VolumeMutedIcon /> : <VolumeIcon />}
              </ControlButton>
              <input
                ref={volumeBarRef}
                type="range"
                min={0}
                max={100}
                value={muted ? 0 : volume}
                onChange={handleVolume}
                className="h-1 w-24 cursor-pointer appearance-none rounded-full bg-white/20 [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-accent [&::-webkit-slider-thumb]:shadow-glow-sm"
                style={{
                  background: `linear-gradient(to right, #00FFAA ${muted ? 0 : volume}%, rgba(255,255,255,0.2) ${muted ? 0 : volume}%)`,
                }}
              />
            </div>

            {/* Fullscreen */}
            <ControlButton onClick={toggleFullscreen} title={fullscreen ? 'Exit Fullscreen (F)' : 'Fullscreen (F)'}>
              {fullscreen ? <ExitFullscreenIcon /> : <FullscreenIcon />}
            </ControlButton>
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Shared control button component
// ---------------------------------------------------------------------------

function ControlButton({
  onClick,
  title,
  active,
  children,
}: {
  onClick: () => void;
  title: string;
  active?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={`flex h-9 w-9 items-center justify-center rounded-lg transition-colors ${
        active
          ? 'bg-accent/20 text-accent'
          : 'text-white/70 hover:bg-white/10 hover:text-white'
      }`}
      title={title}
    >
      {children}
    </button>
  );
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatTime(seconds: number): string {
  const s = Math.floor(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  if (h > 0) return `${h}:${pad(m)}:${pad(sec)}`;
  return `${m}:${pad(sec)}`;
}

function formatDuration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  if (h > 0) return `${h}:${pad(m)}:${pad(sec)}`;
  return `${pad(m)}:${pad(sec)}`;
}

function formatBytes(bytes: number): string {
  if (bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let val = bytes;
  let i = 0;
  while (val >= 1024 && i < units.length - 1) {
    val /= 1024;
    i++;
  }
  return `${val.toFixed(val < 10 && i > 0 ? 1 : 0)} ${units[i]}`;
}

// ---------------------------------------------------------------------------
// Icons
// ---------------------------------------------------------------------------

function PlayIcon({ className = 'h-5 w-5' }: { className?: string }) {
  return (
    <svg className={className} fill="currentColor" viewBox="0 0 24 24">
      <path d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.348a1.125 1.125 0 010 1.971l-11.54 6.347a1.125 1.125 0 01-1.667-.985V5.653z" />
    </svg>
  );
}

function PauseIcon({ className = 'h-5 w-5' }: { className?: string }) {
  return (
    <svg className={className} fill="currentColor" viewBox="0 0 24 24">
      <path fillRule="evenodd" d="M6.75 5.25a.75.75 0 01.75-.75H9a.75.75 0 01.75.75v13.5a.75.75 0 01-.75.75H7.5a.75.75 0 01-.75-.75V5.25zm7.5 0A.75.75 0 0115 4.5h1.5a.75.75 0 01.75.75v13.5a.75.75 0 01-.75.75H15a.75.75 0 01-.75-.75V5.25z" clipRule="evenodd" />
    </svg>
  );
}

function StopIcon() {
  return (
    <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 24 24">
      <path fillRule="evenodd" d="M4.5 7.5a3 3 0 013-3h9a3 3 0 013 3v9a3 3 0 01-3 3h-9a3 3 0 01-3-3v-9z" clipRule="evenodd" />
    </svg>
  );
}

function SkipBackIcon() {
  return (
    <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M12.066 11.2a1 1 0 000 1.6l5.334 4A1 1 0 0019 16V8a1 1 0 00-1.6-.8l-5.333 4zM4.066 11.2a1 1 0 000 1.6l5.334 4A1 1 0 0011 16V8a1 1 0 00-1.6-.8l-5.334 4z" />
    </svg>
  );
}

function SkipForwardIcon() {
  return (
    <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M11.933 12.8a1 1 0 000-1.6L6.6 7.2A1 1 0 005 8v8a1 1 0 001.6.8l5.333-4zM19.933 12.8a1 1 0 000-1.6l-5.333-4A1 1 0 0013 8v8a1 1 0 001.6.8l5.333-4z" />
    </svg>
  );
}

function VolumeIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M19.114 5.636a9 9 0 010 12.728M16.463 8.288a5.25 5.25 0 010 7.424M6.75 8.25l4.72-4.72a.75.75 0 011.28.53v15.88a.75.75 0 01-1.28.53l-4.72-4.72H4.51c-.88 0-1.704-.507-1.938-1.354A9.01 9.01 0 012.25 12c0-.83.112-1.633.322-2.396C2.806 8.756 3.63 8.25 4.51 8.25H6.75z" />
    </svg>
  );
}

function VolumeMutedIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M17.25 9.75L19.5 12m0 0l2.25 2.25M19.5 12l2.25-2.25M19.5 12l-2.25 2.25m-10.5-6l4.72-4.72a.75.75 0 011.28.531v15.88a.75.75 0 01-1.28.53l-4.72-4.72H4.51c-.88 0-1.704-.507-1.938-1.354A9.01 9.01 0 012.25 12c0-.83.112-1.633.322-2.396C2.806 8.756 3.63 8.25 4.51 8.25H6.75z" />
    </svg>
  );
}

function FullscreenIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 3.75v4.5m0-4.5h4.5m-4.5 0L9 9M3.75 20.25v-4.5m0 4.5h4.5m-4.5 0L9 15M20.25 3.75h-4.5m4.5 0v4.5m0-4.5L15 9m5.25 11.25h-4.5m4.5 0v-4.5m0 4.5L15 15" />
    </svg>
  );
}

function ExitFullscreenIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M9 9V4.5M9 9H4.5M9 9L3.75 3.75M9 15v4.5M9 15H4.5M9 15l-5.25 5.25M15 9h4.5M15 9V4.5M15 9l5.25-5.25M15 15h4.5M15 15v4.5m0-4.5l5.25 5.25" />
    </svg>
  );
}

function SubtitleIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.087.16 2.185.283 3.293.369V21l4.076-4.076a1.526 1.526 0 011.037-.443 48.282 48.282 0 005.68-.494c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0012 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018z" />
    </svg>
  );
}

function AudioTrackIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M10.5 21l5.25-11.25L21 21m-9-3h7.5M3 5.621a48.474 48.474 0 016-.371m0 0c1.12 0 2.233.038 3.334.114M9 5.25V3m3.334 2.364C11.176 10.658 7.69 15.08 3 17.502m9.334-12.138c.896.061 1.785.147 2.666.257m-4.589 8.495a18.023 18.023 0 01-3.827-5.802" />
    </svg>
  );
}

function AspectRatioIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M7.5 3.75H6A2.25 2.25 0 003.75 6v1.5M16.5 3.75H18A2.25 2.25 0 0120.25 6v1.5m0 9V18A2.25 2.25 0 0118 20.25h-1.5m-9 0H6A2.25 2.25 0 013.75 18v-1.5" />
    </svg>
  );
}

function SettingsIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.324.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 011.37.49l1.296 2.247a1.125 1.125 0 01-.26 1.431l-1.003.827c-.293.24-.438.613-.431.992a6.759 6.759 0 010 .255c-.007.378.138.75.43.99l1.005.828c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 01-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.57 6.57 0 01-.22.128c-.331.183-.581.495-.644.869l-.213 1.28c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.02-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 01-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 01-1.369-.49l-1.297-2.247a1.125 1.125 0 01.26-1.431l1.004-.827c.292-.24.437-.613.43-.992a6.932 6.932 0 010-.255c.007-.378-.138-.75-.43-.99l-1.004-.828a1.125 1.125 0 01-.26-1.43l1.297-2.247a1.125 1.125 0 011.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.087.22-.128.332-.183.582-.495.644-.869l.214-1.281z" />
      <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
    </svg>
  );
}

function CameraIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M6.827 6.175A2.31 2.31 0 015.186 7.23c-.38.054-.757.112-1.134.175C2.999 7.58 2.25 8.507 2.25 9.574V18a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9.574c0-1.067-.75-1.994-1.802-2.169a47.865 47.865 0 00-1.134-.175 2.31 2.31 0 01-1.64-1.055l-.822-1.316a2.192 2.192 0 00-1.736-1.039 48.774 48.774 0 00-5.232 0 2.192 2.192 0 00-1.736 1.039l-.821 1.316z" />
      <path strokeLinecap="round" strokeLinejoin="round" d="M16.5 12.75a4.5 4.5 0 11-9 0 4.5 4.5 0 019 0z" />
    </svg>
  );
}

function RecordIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <circle cx="12" cy="12" r="8" />
      <circle cx="12" cy="12" r="4" fill="currentColor" />
    </svg>
  );
}

function ArrowLeftIcon() {
  return (
    <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M10.5 19.5L3 12m0 0l7.5-7.5M3 12h18" />
    </svg>
  );
}
