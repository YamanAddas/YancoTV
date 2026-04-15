import { useCallback, useState, useRef, useEffect } from 'react';
import { usePlayerStore } from '../stores/player-store';

export function PlayerOverlay() {
  const {
    status,
    currentTitle,
    currentUrl,
    position,
    duration,
    volume,
    muted,
    speed,
    aspectRatio,
    subtitleTracks,
    audioTracks,
    error,
  } = usePlayerStore();
  const pause = usePlayerStore((s) => s.pause);
  const resume = usePlayerStore((s) => s.resume);
  const stop = usePlayerStore((s) => s.stop);
  const seek = usePlayerStore((s) => s.seek);
  const setVolume = usePlayerStore((s) => s.setVolume);
  const toggleMute = usePlayerStore((s) => s.toggleMute);
  const cycleSpeed = usePlayerStore((s) => s.cycleSpeed);
  const cycleAspectRatio = usePlayerStore((s) => s.cycleAspectRatio);
  const toggleFullscreen = usePlayerStore((s) => s.toggleFullscreen);
  const setSubtitleTrack = usePlayerStore((s) => s.setSubtitleTrack);
  const setAudioTrack = usePlayerStore((s) => s.setAudioTrack);

  const [showTrackMenu, setShowTrackMenu] = useState<'subtitle' | 'audio' | null>(null);
  const trackMenuRef = useRef<HTMLDivElement>(null);

  // Close track menu when clicking outside
  useEffect(() => {
    if (!showTrackMenu) return;
    const handler = (e: MouseEvent) => {
      if (trackMenuRef.current && !trackMenuRef.current.contains(e.target as Node)) {
        setShowTrackMenu(null);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [showTrackMenu]);

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
    },
    [seek],
  );

  const handleVolume = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      setVolume(Number(e.target.value));
    },
    [setVolume],
  );

  const isLive = duration === 0 && status === 'playing';

  // Don't show overlay when nothing is playing
  if (status === 'idle' || (!currentUrl && status !== 'error')) {
    return null;
  }

  return (
    <div className="border-t border-surface-800 bg-surface-900">
      {/* Seek bar — full width above controls (VOD only) */}
      {duration > 0 && (
        <div className="px-4 pt-2">
          <input
            type="range"
            min={0}
            max={Math.floor(duration)}
            value={Math.floor(position)}
            onChange={handleSeek}
            className="h-1 w-full cursor-pointer appearance-none rounded-full bg-surface-700 accent-accent [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-accent"
          />
        </div>
      )}

      <div className="flex items-center gap-2 px-4 py-2">
        {/* Transport controls */}
        <div className="flex items-center gap-1">
          {/* Play/Pause */}
          <button
            onClick={handlePlayPause}
            className="flex h-8 w-8 items-center justify-center rounded-full bg-accent text-surface-950 transition-colors hover:bg-accent/80"
            title={status === 'playing' ? 'Pause' : 'Play'}
          >
            {status === 'buffering' ? (
              <BufferingIcon />
            ) : status === 'playing' ? (
              <PauseIcon />
            ) : (
              <PlayIcon />
            )}
          </button>

          {/* Stop */}
          <button
            onClick={stop}
            className="flex h-7 w-7 items-center justify-center rounded-md text-surface-400 transition-colors hover:text-surface-200"
            title="Stop"
          >
            <StopIcon />
          </button>
        </div>

        {/* Title + time */}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="truncate text-sm font-medium text-surface-200">
              {currentTitle || 'Playing'}
            </p>
            {isLive && (
              <span className="shrink-0 rounded bg-red-500/20 px-1.5 py-0.5 text-[10px] font-bold uppercase text-red-400">
                Live
              </span>
            )}
          </div>
          {error ? (
            <p className="truncate text-xs text-red-400">{error}</p>
          ) : duration > 0 ? (
            <p className="text-xs text-surface-500">
              {formatTime(position)} / {formatTime(duration)}
            </p>
          ) : null}
        </div>

        {/* Enhanced controls — only show when actively playing */}
        {(status === 'playing' || status === 'paused' || status === 'buffering') && (
          <div className="flex items-center gap-1" ref={trackMenuRef}>
            {/* Speed button */}
            {!isLive && (
              <button
                onClick={cycleSpeed}
                className={`rounded-md px-1.5 py-1 text-xs font-medium transition-colors ${
                  speed !== 1
                    ? 'bg-accent/15 text-accent'
                    : 'text-surface-400 hover:text-surface-200'
                }`}
                title={`Playback speed: ${speed}x (click to cycle)`}
              >
                {speed}x
              </button>
            )}

            {/* Aspect ratio */}
            <button
              onClick={cycleAspectRatio}
              className={`rounded-md px-1.5 py-1 text-xs font-medium transition-colors ${
                aspectRatio !== 'auto'
                  ? 'bg-accent/15 text-accent'
                  : 'text-surface-400 hover:text-surface-200'
              }`}
              title={`Aspect ratio: ${aspectRatio} (click to cycle)`}
            >
              <AspectRatioIcon />
            </button>

            {/* Subtitle track selector */}
            {subtitleTracks.length > 0 && (
              <div className="relative">
                <button
                  onClick={() =>
                    setShowTrackMenu(showTrackMenu === 'subtitle' ? null : 'subtitle')
                  }
                  className={`flex h-7 w-7 items-center justify-center rounded-md transition-colors ${
                    subtitleTracks.some((t) => t.selected)
                      ? 'text-accent'
                      : 'text-surface-400 hover:text-surface-200'
                  }`}
                  title="Subtitles"
                >
                  <SubtitleIcon />
                </button>
                {showTrackMenu === 'subtitle' && (
                  <TrackMenu
                    title="Subtitles"
                    tracks={subtitleTracks}
                    onSelect={(id) => {
                      setSubtitleTrack(id);
                      setShowTrackMenu(null);
                    }}
                  />
                )}
              </div>
            )}

            {/* Audio track selector */}
            {audioTracks.length > 1 && (
              <div className="relative">
                <button
                  onClick={() =>
                    setShowTrackMenu(showTrackMenu === 'audio' ? null : 'audio')
                  }
                  className="flex h-7 w-7 items-center justify-center rounded-md text-surface-400 transition-colors hover:text-surface-200"
                  title="Audio tracks"
                >
                  <AudioTrackIcon />
                </button>
                {showTrackMenu === 'audio' && (
                  <TrackMenu
                    title="Audio"
                    tracks={audioTracks}
                    onSelect={(id) => {
                      setAudioTrack(id);
                      setShowTrackMenu(null);
                    }}
                  />
                )}
              </div>
            )}

            {/* Fullscreen */}
            <button
              onClick={toggleFullscreen}
              className="flex h-7 w-7 items-center justify-center rounded-md text-surface-400 transition-colors hover:text-surface-200"
              title="Toggle fullscreen (F)"
            >
              <FullscreenIcon />
            </button>
          </div>
        )}

        {/* Volume */}
        <div className="flex items-center gap-1">
          <button
            onClick={toggleMute}
            className="flex h-7 w-7 items-center justify-center rounded-md text-surface-400 transition-colors hover:text-surface-200"
            title={muted ? 'Unmute (M)' : 'Mute (M)'}
          >
            {muted || volume === 0 ? <VolumeMutedIcon /> : <VolumeIcon />}
          </button>
          <input
            type="range"
            min={0}
            max={100}
            value={muted ? 0 : volume}
            onChange={handleVolume}
            className="h-1 w-20 cursor-pointer appearance-none rounded-full bg-surface-700 accent-accent [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-accent"
          />
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Track selector popup menu
// ---------------------------------------------------------------------------

function TrackMenu({
  title,
  tracks,
  onSelect,
}: {
  title: string;
  tracks: Array<{ id: number; title: string; language?: string; selected: boolean }>;
  onSelect: (id: number) => void;
}) {
  return (
    <div className="absolute bottom-full right-0 z-50 mb-2 min-w-[180px] rounded-lg border border-surface-700 bg-surface-900 py-1 shadow-xl">
      <p className="px-3 py-1.5 text-xs font-semibold uppercase tracking-wider text-surface-500">
        {title}
      </p>
      {tracks.map((t) => (
        <button
          key={t.id}
          onClick={() => onSelect(t.id)}
          className={`flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm transition-colors ${
            t.selected
              ? 'bg-accent/10 text-accent'
              : 'text-surface-300 hover:bg-surface-800'
          }`}
        >
          <span className="w-4 text-center">{t.selected ? '\u2713' : ''}</span>
          <span className="truncate">{t.title}</span>
          {t.language && (
            <span className="ml-auto text-xs text-surface-500">{t.language}</span>
          )}
        </button>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Helpers & Icons
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

function PlayIcon() {
  return (
    <svg className="h-4 w-4 ml-0.5" fill="currentColor" viewBox="0 0 24 24">
      <path d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.348a1.125 1.125 0 010 1.971l-11.54 6.347a1.125 1.125 0 01-1.667-.985V5.653z" />
    </svg>
  );
}

function PauseIcon() {
  return (
    <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 24 24">
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

function BufferingIcon() {
  return (
    <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
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

function AspectRatioIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M7.5 3.75H6A2.25 2.25 0 003.75 6v1.5M16.5 3.75H18A2.25 2.25 0 0120.25 6v1.5m0 9V18A2.25 2.25 0 0118 20.25h-1.5m-9 0H6A2.25 2.25 0 013.75 18v-1.5" />
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
