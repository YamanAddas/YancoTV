import { useEffect } from 'react';
import { useSettingsStore } from '../../stores/settings-store';
import { useT } from '../../i18n';
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
  const t = useT();
  const { get, getBool, set, setBool, load, loaded } = useSettingsStore();

  useEffect(() => {
    load();
  }, [load]);

  if (!loaded) return <LoadingSpinner />;

  const defaultVolume = parseInt(get('playback_default_volume'), 10) || 80;

  return (
    <div className="space-y-6">
      <PageHeading title={t('settingsTab.playback')} subtitle={t('playback.desc')} />

      <div className="space-y-2">
        <SectionHeading>Audio</SectionHeading>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-surface-200">{t('playback.defaultVolume')}</p>
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
          label={t('playback.preferredAudio')}
          description={t('playback.preferredAudioDesc')}
        >
          <Select
            value={get('playback_audio_lang')}
            onChange={(v) => set('playback_audio_lang', v)}
            options={[
              { value: 'default', label: t('value.default') },
              { value: 'en', label: t('lang.english') },
              { value: 'es', label: t('lang.spanish') },
              { value: 'fr', label: t('lang.french') },
              { value: 'de', label: t('lang.german') },
              { value: 'ar', label: t('lang.arabic') },
              { value: 'pt', label: t('lang.portuguese') },
            ]}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <SectionHeading>Video</SectionHeading>

        <SettingRow
          label={t('playback.aspectRatio')}
          description={t('playback.aspectRatioDesc')}
        >
          <Select
            value={get('playback_aspect_ratio')}
            onChange={(v) => set('playback_aspect_ratio', v)}
            options={[
              { value: 'auto', label: t('value.auto') },
              { value: '16:9', label: '16:9' },
              { value: '4:3', label: '4:3' },
              { value: 'fill', label: t('value.fillScreen') },
            ]}
          />
        </SettingRow>

        <SettingRow
          label={t('playback.deinterlacing')}
          description={t('playback.deinterlacingDesc')}
        >
          <Select
            value={get('playback_deinterlace')}
            onChange={(v) => set('playback_deinterlace', v)}
            options={[
              { value: 'auto', label: t('value.auto') },
              { value: 'on', label: t('value.alwaysOn') },
              { value: 'off', label: t('value.off') },
            ]}
          />
        </SettingRow>

        <SettingRow
          label={t('playback.defaultSpeed')}
          description={t('playback.defaultSpeedDesc')}
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
        <SectionHeading>{t('playback.buffering')}</SectionHeading>

        <SettingRow
          label={t('playback.bufferSize')}
          description={t('playback.bufferSizeDesc')}
        >
          <Select
            value={get('playback_buffer_size')}
            onChange={(v) => set('playback_buffer_size', v)}
            options={[
              { value: 'auto', label: t('value.auto') },
              { value: 'small', label: t('buffer.small') },
              { value: 'medium', label: t('buffer.medium') },
              { value: 'large', label: t('buffer.large') },
              { value: 'xlarge', label: t('buffer.xlarge') },
            ]}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <SectionHeading>{t('playback.behaviour')}</SectionHeading>

        <SettingRow
          label={t('playback.resume')}
          description={t('playback.resumeDesc')}
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
