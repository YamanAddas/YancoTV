import { useState, useEffect, useRef } from 'react';
import { usePlayerStore } from '../../stores/player-store';

type SettingsTab = 'subtitles' | 'audio' | 'video' | 'speed' | 'info';

const TABS: { id: SettingsTab; label: string }[] = [
  { id: 'subtitles', label: 'Subtitles' },
  { id: 'audio', label: 'Audio' },
  { id: 'video', label: 'Video' },
  { id: 'speed', label: 'Speed' },
  { id: 'info', label: 'Info' },
];

const SPEED_PRESETS = [0.5, 0.75, 1, 1.25, 1.5, 2];
const ASPECT_OPTIONS: { value: string; label: string }[] = [
  { value: 'auto', label: 'Auto' },
  { value: '16:9', label: '16:9' },
  { value: '4:3', label: '4:3' },
  { value: '21:9', label: '21:9' },
  { value: 'fill', label: 'Fill' },
];

interface SettingsPanelProps {
  onClose: () => void;
}

export function SettingsPanel({ onClose }: SettingsPanelProps) {
  const [activeTab, setActiveTab] = useState<SettingsTab>('subtitles');
  const panelRef = useRef<HTMLDivElement>(null);

  // Close on click outside
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    // Delay to avoid immediate close from the toggle click
    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handler);
    }, 100);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', handler);
    };
  }, [onClose]);

  // Close on Escape. If player is in error state, also stop playback
  // so the user doesn't need to press Escape twice (Bug 29 fix).
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
        const { status } = usePlayerStore.getState();
        if (status === 'error') {
          usePlayerStore.getState().stop();
        }
      }
    };
    window.addEventListener('keydown', handler, true);
    return () => window.removeEventListener('keydown', handler, true);
  }, [onClose]);

  return (
    <div
      ref={panelRef}
      className="absolute bottom-20 right-6 z-[50] w-80 overflow-hidden rounded-2xl border border-white/10 bg-surface-900/95 shadow-glass backdrop-blur-xl"
    >
      {/* Tab bar */}
      <div className="flex border-b border-white/10">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`flex-1 px-2 py-3 text-xs font-medium transition-colors ${
              activeTab === tab.id
                ? 'border-b-2 border-accent text-accent'
                : 'text-surface-400 hover:text-surface-200'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="max-h-72 overflow-y-auto p-4">
        {activeTab === 'subtitles' && <SubtitlesTab />}
        {activeTab === 'audio' && <AudioTab />}
        {activeTab === 'video' && <VideoTab />}
        {activeTab === 'speed' && <SpeedTab />}
        {activeTab === 'info' && <InfoTab />}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tab: Subtitles
// ---------------------------------------------------------------------------

function SubtitlesTab() {
  const toggleSubtitles = usePlayerStore((s) => s.toggleSubtitles);
  const loadSubtitleFile = usePlayerStore((s) => s.loadSubtitleFile);

  return (
    <div className="space-y-3">
      <button
        onClick={toggleSubtitles}
        className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm text-surface-300 transition-colors hover:bg-surface-800"
      >
        <span>Toggle subtitles</span>
      </button>

      <button
        onClick={loadSubtitleFile}
        className="flex w-full items-center gap-2 rounded-lg border border-dashed border-surface-600 px-3 py-2 text-sm text-surface-400 transition-colors hover:border-accent/50 hover:text-accent"
      >
        <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5" />
        </svg>
        <span>Load subtitle file...</span>
      </button>

      <div className="rounded-lg border border-surface-700 bg-surface-800/50 px-3 py-2">
        <p className="text-xs text-surface-500">
          Translation coming soon
        </p>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tab: Audio
// ---------------------------------------------------------------------------

function AudioTab() {
  const audioTracks = usePlayerStore((s) => s.audioTracks);
  const backend = usePlayerStore((s) => s.backend);

  if (audioTracks.length > 1) {
    return (
      <div className="space-y-1">
        {audioTracks.map((track) => (
          <button
            key={track.id}
            onClick={() => {
              if (backend === 'mpv') {
                window.api?.player.setAudioTrack(track.id).catch(() => {});
              }
            }}
            className={`flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm transition-colors ${
              track.selected
                ? 'bg-accent/20 text-accent'
                : 'text-surface-300 hover:bg-surface-800'
            }`}
          >
            <span className="flex-1">{track.title}</span>
            {track.language && (
              <span className="text-xs text-surface-500">{track.language}</span>
            )}
            {track.selected && (
              <svg className="h-4 w-4 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
              </svg>
            )}
          </button>
        ))}
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <p className="text-sm text-surface-400">
        {backend === 'mpv' ? 'Single audio track detected.' : 'Audio track selection is automatic.'}
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tab: Video
// ---------------------------------------------------------------------------

function VideoTab() {
  const aspectRatio = usePlayerStore((s) => s.aspectRatio);
  const setAspectRatio = usePlayerStore((s) => s.setAspectRatio);

  return (
    <div className="space-y-4">
      <div>
        <p className="mb-2 text-xs font-medium uppercase tracking-wider text-surface-500">
          Aspect Ratio
        </p>
        <div className="grid grid-cols-5 gap-1">
          {ASPECT_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => setAspectRatio(opt.value)}
              className={`rounded-lg px-2 py-2 text-xs font-medium transition-colors ${
                aspectRatio === opt.value
                  ? 'bg-accent/20 text-accent'
                  : 'bg-surface-800 text-surface-400 hover:text-surface-200'
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tab: Speed
// ---------------------------------------------------------------------------

function SpeedTab() {
  const speed = usePlayerStore((s) => s.speed);
  const setSpeed = usePlayerStore((s) => s.setSpeed);

  return (
    <div>
      <p className="mb-3 text-xs font-medium uppercase tracking-wider text-surface-500">
        Playback Speed
      </p>
      <div className="grid grid-cols-3 gap-2">
        {SPEED_PRESETS.map((preset) => (
          <button
            key={preset}
            onClick={() => setSpeed(preset)}
            className={`rounded-xl py-3 text-sm font-semibold transition-all ${
              speed === preset
                ? 'bg-accent text-surface-950 shadow-glow-sm scale-105'
                : 'bg-surface-800 text-surface-300 hover:bg-surface-700 hover:text-surface-100'
            }`}
          >
            {preset}x
          </button>
        ))}
      </div>
      <p className="mt-3 text-center text-xs text-surface-500">
        Use <kbd className="rounded bg-surface-700 px-1.5 py-0.5 font-mono">[</kbd> and{' '}
        <kbd className="rounded bg-surface-700 px-1.5 py-0.5 font-mono">]</kbd> to adjust
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tab: Info
// ---------------------------------------------------------------------------

function InfoTab() {
  const mediaInfo = usePlayerStore((s) => s.mediaInfo);

  const rows: [string, string | undefined][] = [
    ['Resolution', mediaInfo.width && mediaInfo.height ? `${mediaInfo.width}x${mediaInfo.height}` : undefined],
    ['Video Codec', mediaInfo.videoCodec],
    ['Audio Codec', mediaInfo.audioCodec],
    ['Frame Rate', mediaInfo.fps ? `${mediaInfo.fps} fps` : undefined],
    ['Bitrate', mediaInfo.bitrate ? `${mediaInfo.bitrate} kbps` : undefined],
    ['HW Decode', mediaInfo.hwdec],
  ];

  return (
    <div className="space-y-2">
      {rows.map(([label, value]) => (
        <div key={label} className="flex items-center justify-between">
          <span className="text-xs text-surface-500">{label}</span>
          <span className="text-xs font-medium text-surface-300">
            {value || '--'}
          </span>
        </div>
      ))}
    </div>
  );
}
