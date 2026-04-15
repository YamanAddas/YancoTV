import { useEffect } from 'react';
import { useSettingsStore } from '../../stores/settings-store';

// ---------------------------------------------------------------------------
// Playback Settings — volume, buffer, aspect ratio, speed, hardware accel
// ---------------------------------------------------------------------------

function SettingRow({
  label,
  description,
  children,
}: {
  label: string;
  description?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
      <div className="min-w-0">
        <p className="text-sm font-medium text-surface-200">{label}</p>
        {description && (
          <p className="mt-0.5 text-xs text-surface-500">{description}</p>
        )}
      </div>
      <div className="flex-shrink-0">{children}</div>
    </div>
  );
}

function Toggle({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
        checked ? 'bg-accent shadow-glow-sm' : 'bg-surface-600'
      }`}
    >
      <span
        className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${
          checked ? 'translate-x-[18px]' : 'translate-x-[3px]'
        }`}
      />
    </button>
  );
}

function Select({
  value,
  onChange,
  options,
}: {
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-1.5 text-sm text-surface-200 focus:border-accent/50 focus:outline-none focus:ring-1 focus:ring-accent/30"
    >
      {options.map((opt) => (
        <option key={opt.value} value={opt.value}>
          {opt.label}
        </option>
      ))}
    </select>
  );
}

export function PlaybackSettings() {
  const { get, getBool, set, setBool, load, loaded } = useSettingsStore();

  useEffect(() => {
    load();
  }, [load]);

  if (!loaded) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-accent border-t-transparent" />
      </div>
    );
  }

  const defaultVolume = parseInt(get('playback_default_volume'), 10) || 80;

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-surface-100">Playback</h2>
        <p className="mt-1 text-sm text-surface-500">
          Video and audio playback preferences
        </p>
      </div>

      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Audio
        </h3>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-surface-200">
                Default volume
              </p>
              <p className="mt-0.5 text-xs text-surface-500">
                Volume level when the app starts ({defaultVolume}%)
              </p>
            </div>
            <span className="text-sm font-medium text-surface-300">
              {defaultVolume}%
            </span>
          </div>
          <input
            type="range"
            min={0}
            max={100}
            step={5}
            value={defaultVolume}
            onChange={(e) => set('playback_default_volume', e.target.value)}
            className="mt-2 w-full accent-accent"
          />
        </div>

        <SettingRow
          label="Preferred audio language"
          description="Auto-select audio track by language when available"
        >
          <Select
            value={get('playback_audio_lang')}
            onChange={(v) => set('playback_audio_lang', v)}
            options={[
              { value: 'default', label: 'Default' },
              { value: 'en', label: 'English' },
              { value: 'es', label: 'Spanish' },
              { value: 'fr', label: 'French' },
              { value: 'de', label: 'German' },
              { value: 'ar', label: 'Arabic' },
              { value: 'pt', label: 'Portuguese' },
            ]}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Video
        </h3>

        <SettingRow
          label="Aspect ratio"
          description="How the video is scaled to fit the player"
        >
          <Select
            value={get('playback_aspect_ratio')}
            onChange={(v) => set('playback_aspect_ratio', v)}
            options={[
              { value: 'auto', label: 'Auto' },
              { value: '16:9', label: '16:9' },
              { value: '4:3', label: '4:3' },
              { value: 'fill', label: 'Fill Screen' },
            ]}
          />
        </SettingRow>

        <SettingRow
          label="Hardware acceleration"
          description="Use GPU for video decoding (recommended)"
        >
          <Toggle
            checked={getBool('playback_hw_accel')}
            onChange={(v) => setBool('playback_hw_accel', v)}
          />
        </SettingRow>

        <SettingRow
          label="Deinterlacing"
          description="Deinterlace mode for interlaced content"
        >
          <Select
            value={get('playback_deinterlace')}
            onChange={(v) => set('playback_deinterlace', v)}
            options={[
              { value: 'auto', label: 'Auto' },
              { value: 'on', label: 'Always On' },
              { value: 'off', label: 'Off' },
            ]}
          />
        </SettingRow>

        <SettingRow
          label="Default playback speed"
          description="Speed for VOD content (does not affect live)"
        >
          <Select
            value={get('playback_speed')}
            onChange={(v) => set('playback_speed', v)}
            options={[
              { value: '0.5', label: '0.5x' },
              { value: '0.75', label: '0.75x' },
              { value: '1.0', label: '1.0x (Normal)' },
              { value: '1.25', label: '1.25x' },
              { value: '1.5', label: '1.5x' },
              { value: '2.0', label: '2.0x' },
            ]}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Buffering
        </h3>

        <SettingRow
          label="Buffer size"
          description="Larger buffers reduce stuttering but increase delay"
        >
          <Select
            value={get('playback_buffer_size')}
            onChange={(v) => set('playback_buffer_size', v)}
            options={[
              { value: 'auto', label: 'Auto' },
              { value: 'small', label: 'Small (1s)' },
              { value: 'medium', label: 'Medium (3s)' },
              { value: 'large', label: 'Large (5s)' },
              { value: 'xlarge', label: 'Extra Large (10s)' },
            ]}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Subtitles
        </h3>

        <SettingRow
          label="Subtitle language"
          description="Auto-select subtitle track when available"
        >
          <Select
            value={get('playback_subtitle_lang')}
            onChange={(v) => set('playback_subtitle_lang', v)}
            options={[
              { value: 'off', label: 'Off' },
              { value: 'en', label: 'English' },
              { value: 'es', label: 'Spanish' },
              { value: 'fr', label: 'French' },
              { value: 'de', label: 'German' },
              { value: 'ar', label: 'Arabic' },
              { value: 'pt', label: 'Portuguese' },
            ]}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Behaviour
        </h3>

        <SettingRow
          label="Resume playback"
          description="Continue VOD content from where you left off"
        >
          <Toggle
            checked={getBool('playback_resume')}
            onChange={(v) => setBool('playback_resume', v)}
          />
        </SettingRow>
      </div>
    </div>
  );
}
