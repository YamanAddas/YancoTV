import { useCallback } from 'react';
import { usePlayerStore } from '../stores/player-store';

export function PlayerOverlay() {
  const { status, currentTitle, currentUrl, position, duration, volume } = usePlayerStore();
  const pause = usePlayerStore((s) => s.pause);
  const resume = usePlayerStore((s) => s.resume);
  const stop = usePlayerStore((s) => s.stop);
  const seek = usePlayerStore((s) => s.seek);
  const setVolume = usePlayerStore((s) => s.setVolume);
  const error = usePlayerStore((s) => s.error);

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

  // Don't show overlay when nothing is playing
  if (status === 'idle' || (!currentUrl && status !== 'error')) {
    return null;
  }

  return (
    <div className="flex items-center gap-3 border-t border-surface-800 bg-surface-900 px-4 py-2.5">
      {/* Play/Pause */}
      <button
        onClick={handlePlayPause}
        className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-full bg-accent text-surface-950 transition-colors hover:bg-accent/80"
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
        className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md text-surface-400 transition-colors hover:text-surface-200"
        title="Stop"
      >
        <StopIcon />
      </button>

      {/* Track info */}
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-surface-200">
          {currentTitle || 'Playing'}
        </p>
        {error ? (
          <p className="truncate text-xs text-red-400">{error}</p>
        ) : (
          <div className="flex items-center gap-2">
            <span className="text-xs text-surface-500">{formatTime(position)}</span>
            {duration > 0 && (
              <>
                <input
                  type="range"
                  min={0}
                  max={Math.floor(duration)}
                  value={Math.floor(position)}
                  onChange={handleSeek}
                  className="h-1 flex-1 cursor-pointer appearance-none rounded-full bg-surface-700 accent-accent [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-accent"
                />
                <span className="text-xs text-surface-500">{formatTime(duration)}</span>
              </>
            )}
            {duration === 0 && status === 'playing' && (
              <span className="text-xs text-accent">LIVE</span>
            )}
          </div>
        )}
      </div>

      {/* Volume */}
      <div className="flex items-center gap-1.5">
        <VolumeIcon />
        <input
          type="range"
          min={0}
          max={100}
          value={volume}
          onChange={handleVolume}
          className="h-1 w-20 cursor-pointer appearance-none rounded-full bg-surface-700 accent-accent [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-accent"
        />
      </div>
    </div>
  );
}

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
    <svg className="h-4 w-4 flex-shrink-0 text-surface-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M19.114 5.636a9 9 0 010 12.728M16.463 8.288a5.25 5.25 0 010 7.424M6.75 8.25l4.72-4.72a.75.75 0 011.28.53v15.88a.75.75 0 01-1.28.53l-4.72-4.72H4.51c-.88 0-1.704-.507-1.938-1.354A9.01 9.01 0 012.25 12c0-.83.112-1.633.322-2.396C2.806 8.756 3.63 8.25 4.51 8.25H6.75z" />
    </svg>
  );
}
