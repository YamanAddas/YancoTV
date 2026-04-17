import { useEffect } from 'react';
import { useSettingsStore } from '../../stores/settings-store';
import {
  PageHeading,
  SectionHeading,
  SettingRow,
  Select,
  Toggle,
  LoadingSpinner,
} from './primitives';

// ---------------------------------------------------------------------------
// Playback Settings — audio/video defaults, buffering, resume behavior
//
// Recording / downloads / subtitles / advanced each have their own tab now.
// This page sticks to what happens during playback itself.
// ---------------------------------------------------------------------------

export function PlaybackSettings() {
  const { get, getBool, set, setBool, load, loaded } = useSettingsStore();

  useEffect(() => {
    load();
  }, [load]);

  if (!loaded) return <LoadingSpinner />;

  const defaultVolume = parseInt(get('playback_default_volume'), 10) || 80;

  return (
    <div className="space-y-6">
      <PageHeading title="Playback" subtitle="Video and audio defaults" />

      <div className="space-y-2">
        <SectionHeading>Audio</SectionHeading>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-surface-200">Default volume</p>
              <p className="mt-0.5 text-xs text-surface-500">
                Volume level when the app starts ({defaultVolume}%)
              </p>
            </div>
            <span className="text-sm font-medium text-surface-300">{defaultVolume}%</span>
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
        <SectionHeading>Video</SectionHeading>

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
        <SectionHeading>Buffering</SectionHeading>

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
        <SectionHeading>Behaviour</SectionHeading>

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
