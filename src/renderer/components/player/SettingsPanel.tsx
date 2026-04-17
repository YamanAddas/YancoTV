import { useState, useEffect, useRef } from 'react';
import { usePlayerStore } from '../../stores/player-store';
import type { SettingsTab } from '../../stores/player-store';
import { SubtitlesTab } from './settings-tabs/SubtitlesTab';

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
  { value: '2.35:1', label: '2.35:1' },
  { value: '1:1', label: '1:1' },
  { value: 'fill', label: 'Fill' },
];

const ZOOM_PRESETS = [0.75, 1, 1.15, 1.3, 1.5, 2];

interface SettingsPanelProps {
  onClose: () => void;
}

export function SettingsPanel({ onClose }: SettingsPanelProps) {
  const initialTab = usePlayerStore((s) => s.settingsTab);
  const [activeTab, setActiveTab] = useState<SettingsTab>(initialTab);
  const panelRef = useRef<HTMLDivElement>(null);

  // Sync tab when the store changes (e.g. user clicks Subtitles button while
  // the panel is already open on another tab).
  useEffect(() => {
    setActiveTab(initialTab);
  }, [initialTab]);

  // Close on click outside
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    // Use `click` (not `mousedown`) so the trigger button's onClick runs first.
    // Otherwise mousedown closes the panel, then the button's click toggles it
    // back open — making the button feel like it never closes.
    const timer = setTimeout(() => {
      document.addEventListener('click', handler);
    }, 100);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('click', handler);
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
      className="absolute bottom-20 right-6 z-[50] w-96 overflow-hidden rounded-2xl border border-white/10 bg-surface-900/95 shadow-glass backdrop-blur-xl"
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
      <div className="max-h-[70vh] overflow-y-auto p-4">
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
// Tab: Audio
// ---------------------------------------------------------------------------

function AudioTab() {
  const audioTracks = usePlayerStore((s) => s.audioTracks);
  const audioDelay = usePlayerStore((s) => s.audioDelay);
  const setAudioTrack = usePlayerStore((s) => s.setAudioTrack);
  const setAudioDelay = usePlayerStore((s) => s.setAudioDelay);
  const adjustAudioDelay = usePlayerStore((s) => s.adjustAudioDelay);
  const backend = usePlayerStore((s) => s.backend);

  return (
    <div className="space-y-4">
      <div>
        <p className="mb-2 text-xs font-medium uppercase tracking-wider text-surface-500">
          Audio Track
        </p>
        {audioTracks.length > 1 ? (
          <div className="space-y-1">
            {audioTracks.map((track) => (
              <button
                key={track.id}
                onClick={() => setAudioTrack(track.id)}
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
                {track.selected && <CheckIcon />}
              </button>
            ))}
          </div>
        ) : (
          <p className="text-sm text-surface-400">
            {backend === 'mpv'
              ? 'Single audio track detected.'
              : 'Audio track selection is automatic.'}
          </p>
        )}
      </div>

      {/* Audio delay — lip sync */}
      <DelayControl
        label="Audio Delay"
        hint="Negative = audio earlier, Positive = audio later"
        value={audioDelay}
        onReset={() => setAudioDelay(0)}
        onStep={(d) => adjustAudioDelay(d)}
        step={0.05}
        disabled={backend !== 'mpv'}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tab: Video
// ---------------------------------------------------------------------------

function VideoTab() {
  const aspectRatio = usePlayerStore((s) => s.aspectRatio);
  const videoZoom = usePlayerStore((s) => s.videoZoom);
  const setAspectRatio = usePlayerStore((s) => s.setAspectRatio);
  const setVideoZoom = usePlayerStore((s) => s.setVideoZoom);
  const adjustVideoZoom = usePlayerStore((s) => s.adjustVideoZoom);
  const takeScreenshot = usePlayerStore((s) => s.takeScreenshot);
  const backend = usePlayerStore((s) => s.backend);

  return (
    <div className="space-y-4">
      <div>
        <p className="mb-2 text-xs font-medium uppercase tracking-wider text-surface-500">
          Aspect Ratio
        </p>
        <div className="grid grid-cols-4 gap-1">
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

      <div>
        <div className="mb-2 flex items-center justify-between">
          <p className="text-xs font-medium uppercase tracking-wider text-surface-500">
            Zoom
          </p>
          <span className="text-xs tabular-nums text-surface-400">{videoZoom.toFixed(2)}×</span>
        </div>
        <div className="grid grid-cols-6 gap-1">
          {ZOOM_PRESETS.map((z) => (
            <button
              key={z}
              onClick={() => setVideoZoom(z)}
              className={`rounded-lg px-1 py-2 text-xs font-medium transition-colors ${
                Math.abs(videoZoom - z) < 0.01
                  ? 'bg-accent/20 text-accent'
                  : 'bg-surface-800 text-surface-400 hover:text-surface-200'
              }`}
              disabled={backend !== 'mpv'}
            >
              {z}×
            </button>
          ))}
        </div>
        <div className="mt-2 flex items-center justify-center gap-2">
          <button
            onClick={() => adjustVideoZoom(-0.05)}
            disabled={backend !== 'mpv'}
            className="rounded-md bg-surface-800 px-2 py-1 text-xs text-surface-300 hover:bg-surface-700 disabled:opacity-50"
          >
            − Zoom out
          </button>
          <button
            onClick={() => setVideoZoom(1)}
            disabled={backend !== 'mpv'}
            className="rounded-md bg-surface-800 px-2 py-1 text-xs text-surface-300 hover:bg-surface-700 disabled:opacity-50"
          >
            Reset
          </button>
          <button
            onClick={() => adjustVideoZoom(0.05)}
            disabled={backend !== 'mpv'}
            className="rounded-md bg-surface-800 px-2 py-1 text-xs text-surface-300 hover:bg-surface-700 disabled:opacity-50"
          >
            + Zoom in
          </button>
        </div>
      </div>

      <button
        onClick={() => takeScreenshot()}
        disabled={backend !== 'mpv'}
        className="flex w-full items-center justify-center gap-2 rounded-lg bg-surface-800 px-3 py-2 text-sm text-surface-200 transition-colors hover:bg-surface-700 disabled:opacity-50"
      >
        <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M6.827 6.175A2.31 2.31 0 015.186 7.23c-.38.054-.757.112-1.134.175C2.999 7.58 2.25 8.507 2.25 9.574V18a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9.574c0-1.067-.75-1.994-1.802-2.169a47.865 47.865 0 00-1.134-.175 2.31 2.31 0 01-1.64-1.055l-.822-1.316a2.192 2.192 0 00-1.736-1.039 48.774 48.774 0 00-5.232 0 2.192 2.192 0 00-1.736 1.039l-.821 1.316z" />
          <path strokeLinecap="round" strokeLinejoin="round" d="M16.5 12.75a4.5 4.5 0 11-9 0 4.5 4.5 0 019 0z" />
        </svg>
        <span>Take screenshot</span>
      </button>
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

// ---------------------------------------------------------------------------
// Shared: Delay Control (reused by Audio + Subtitle tabs)
// ---------------------------------------------------------------------------

export function DelayControl({
  label,
  hint,
  value,
  onReset,
  onStep,
  step,
  disabled,
}: {
  label: string;
  hint?: string;
  value: number;
  onReset: () => void;
  onStep: (delta: number) => void;
  step: number;
  disabled?: boolean;
}) {
  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <p className="text-xs font-medium uppercase tracking-wider text-surface-500">
          {label}
        </p>
        <span className="text-xs tabular-nums text-surface-400">
          {value > 0 ? '+' : ''}
          {value.toFixed(2)} s
        </span>
      </div>
      <div className="flex items-center gap-1">
        <button
          onClick={() => onStep(-step * 10)}
          disabled={disabled}
          className="rounded-md bg-surface-800 px-2 py-1.5 text-xs text-surface-300 hover:bg-surface-700 disabled:opacity-50"
        >
          −{(step * 10).toFixed(2)}
        </button>
        <button
          onClick={() => onStep(-step)}
          disabled={disabled}
          className="rounded-md bg-surface-800 px-2 py-1.5 text-xs text-surface-300 hover:bg-surface-700 disabled:opacity-50"
        >
          −{step.toFixed(2)}
        </button>
        <button
          onClick={onReset}
          disabled={disabled}
          className="flex-1 rounded-md bg-surface-800 px-2 py-1.5 text-xs text-surface-300 hover:bg-surface-700 disabled:opacity-50"
        >
          Reset
        </button>
        <button
          onClick={() => onStep(step)}
          disabled={disabled}
          className="rounded-md bg-surface-800 px-2 py-1.5 text-xs text-surface-300 hover:bg-surface-700 disabled:opacity-50"
        >
          +{step.toFixed(2)}
        </button>
        <button
          onClick={() => onStep(step * 10)}
          disabled={disabled}
          className="rounded-md bg-surface-800 px-2 py-1.5 text-xs text-surface-300 hover:bg-surface-700 disabled:opacity-50"
        >
          +{(step * 10).toFixed(2)}
        </button>
      </div>
      {hint && <p className="mt-1 text-[11px] text-surface-500">{hint}</p>}
    </div>
  );
}

function CheckIcon() {
  return (
    <svg className="h-4 w-4 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
      <path
        fillRule="evenodd"
        d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
        clipRule="evenodd"
      />
    </svg>
  );
}
