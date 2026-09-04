import { useEffect, useState } from 'react';
import { useSettingsStore } from '../../stores/settings-store';
import { useT } from '../../i18n';
import type { StringKey } from '../../i18n/locales/en';
import {
  PageHeading,
  SectionHeading,
  SettingRow,
  Select,
  Toggle,
  TextInput,
  PrimaryButton,
  GhostButton,
  LoadingSpinner,
} from './primitives';

// ---------------------------------------------------------------------------
// Subtitles Settings — default language, auto-search, OpenSubtitles account,
// appearance (font size, color, background opacity).
//
// Appearance settings are applied by mpv on next stream load via
// `--sub-scale`, `--sub-color`, `--sub-back-color` args (see mpv-args.ts).
// ---------------------------------------------------------------------------

// Keys, not resolved labels: these arrays are module-level constants evaluated
// once at import, so a resolved string would freeze whatever language was
// active at load and never update when the user switches. Resolved at the
// render site instead, like the sidebar's nav items.
const LANGUAGE_OPTIONS = [
  { value: 'off', labelKey: 'value.off' as StringKey },
  { value: 'en', labelKey: 'lang.english' as StringKey },
  { value: 'es', labelKey: 'lang.spanish' as StringKey },
  { value: 'fr', labelKey: 'lang.french' as StringKey },
  { value: 'de', labelKey: 'lang.german' as StringKey },
  { value: 'ar', labelKey: 'lang.arabic' as StringKey },
  { value: 'pt', labelKey: 'lang.portuguese' as StringKey },
  { value: 'tr', labelKey: 'lang.turkish' as StringKey },
  { value: 'it', labelKey: 'lang.italian' as StringKey },
  { value: 'nl', labelKey: 'lang.dutch' as StringKey },
  { value: 'ru', labelKey: 'lang.russian' as StringKey },
];

const COLOR_OPTIONS = [
  { value: '#FFFFFF', labelKey: 'color.white' as StringKey },
  { value: '#FFFF00', labelKey: 'color.yellow' as StringKey },
  { value: '#FFB400', labelKey: 'color.amber' as StringKey },
  { value: '#00FFC8', labelKey: 'color.cyan' as StringKey },
  { value: '#FF64A5', labelKey: 'color.pink' as StringKey },
];

export function SubtitlesSettings() {
  const t = useT();
  const { get, getBool, set, setBool, load, loaded } = useSettingsStore();

  useEffect(() => {
    load();
  }, [load]);

  if (!loaded) return <LoadingSpinner />;

  const scale = parseFloat(get('subtitle_scale')) || 1;
  const backOpacity = parseInt(get('subtitle_back_opacity'), 10) || 0;
  const currentColor = get('subtitle_color');

  return (
    <div className="space-y-6">
      <PageHeading
        title={t('settingsTab.subtitles')}
        subtitle={t('subs.desc')}
      />

      <div className="space-y-2">
        <SectionHeading>{t('settings.language')}</SectionHeading>

        <SettingRow
          label={t('subs.preferredLang')}
          description={t('subs.preferredLangDesc')}
        >
          <Select
            value={get('playback_subtitle_lang')}
            onChange={(v) => set('playback_subtitle_lang', v)}
            options={LANGUAGE_OPTIONS.map((o) => ({ value: o.value, label: t(o.labelKey) }))}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <SectionHeading>OpenSubtitles</SectionHeading>

        <SettingRow
          label={t('subs.autoSearch')}
          description={t('subs.autoSearchDesc')}
        >
          <Toggle
            checked={getBool('opensubtitles.autoSearch')}
            onChange={(v) => setBool('opensubtitles.autoSearch', v)}
          />
        </SettingRow>

        <OpenSubtitlesCredentials />
      </div>

      <div className="space-y-2">
        <SectionHeading>{t('subs.appearance')}</SectionHeading>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-surface-200">{t('subs.textSize')}</p>
              <p className="mt-0.5 text-xs text-surface-500">
                Scale factor applied on top of the source subtitle size
              </p>
            </div>
            <span className="text-sm font-medium text-surface-300">
              {scale.toFixed(2)}×
            </span>
          </div>
          <input
            type="range"
            min={0.5}
            max={3.0}
            step={0.1}
            value={scale}
            onChange={(e) => set('subtitle_scale', e.target.value)}
            className="mt-2 w-full accent-accent"
          />
        </div>

        <SettingRow
          label={t('subs.textColor')}
          description={t('subs.textColorDesc')}
        >
          <Select
            value={currentColor}
            onChange={(v) => set('subtitle_color', v)}
            options={COLOR_OPTIONS.map((o) => ({ value: o.value, label: t(o.labelKey) }))}
          />
        </SettingRow>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-surface-200">{t('subs.bgOpacity')}</p>
              <p className="mt-0.5 text-xs text-surface-500">
                {t('subs.bgOpacityDesc')}
              </p>
            </div>
            <span className="text-sm font-medium text-surface-300">{backOpacity}%</span>
          </div>
          <input
            type="range"
            min={0}
            max={100}
            step={5}
            value={backOpacity}
            onChange={(e) => set('subtitle_back_opacity', e.target.value)}
            className="mt-2 w-full accent-accent"
          />
        </div>

        <SubtitlePreview scale={scale} color={currentColor} backOpacity={backOpacity} />

        <p className="px-1 text-xs text-surface-500">
          Appearance changes take effect the next time a stream starts.
        </p>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Live preview — best-effort CSS approximation of mpv rendering
// ---------------------------------------------------------------------------

function SubtitlePreview({
  scale,
  color,
  backOpacity,
}: {
  scale: number;
  color: string;
  backOpacity: number;
}) {
  const t = useT();
  const alpha = Math.max(0, Math.min(1, backOpacity / 100));
  return (
    <div className="flex h-24 items-end justify-center rounded-xl border border-accent/5 bg-gradient-to-br from-surface-800 to-surface-900 p-4">
      <span
        style={{
          fontSize: `${14 * scale}px`,
          color,
          backgroundColor: `rgba(0, 0, 0, ${alpha})`,
          padding: '2px 8px',
          borderRadius: 4,
          fontWeight: 600,
        }}
      >
        {t('subs.sampleText')}
      </span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// OpenSubtitles credentials (moved from PlaybackSettings)
// ---------------------------------------------------------------------------

function OpenSubtitlesCredentials() {
  const t = useT();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [status, setStatus] = useState<'idle' | 'saving' | 'clearing' | 'saved' | 'error'>('idle');
  const [message, setMessage] = useState<string | null>(null);
  const [cacheStats, setCacheStats] = useState<{ count: number } | null>(null);

  useEffect(() => {
    window.api?.subtitles.getCacheStats().then((r: { ok: boolean; count?: number }) => {
      if (r?.ok) setCacheStats({ count: r.count ?? 0 });
    });
  }, []);

  const save = async () => {
    if (!username.trim() || !password) {
      setMessage('Enter both username and password.');
      setStatus('error');
      return;
    }
    setStatus('saving');
    setMessage(null);
    const res = await window.api?.subtitles.setCredentials(username.trim(), password);
    if (res?.ok) {
      setStatus('saved');
      setMessage('Credentials saved. Your download quota is now linked to your account.');
      setPassword('');
    } else {
      setStatus('error');
      setMessage(res?.error ?? 'Failed to save credentials.');
    }
  };

  const clear = async () => {
    setStatus('clearing');
    await window.api?.subtitles.clearCredentials();
    setUsername('');
    setPassword('');
    setStatus('idle');
    setMessage('Credentials removed. Anonymous downloads will be used.');
  };

  const clearCache = async () => {
    await window.api?.subtitles.clearCache();
    setCacheStats({ count: 0 });
  };

  return (
    <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3 space-y-3">
      <div>
        <p className="text-sm font-medium text-surface-200">{t('subs.account')}</p>
        <p className="mt-0.5 text-xs text-surface-500">
          Optional — raises your daily download limit. Anonymous users get 5/day.
        </p>
      </div>

      <div className="space-y-2">
        <TextInput
          value={username}
          onChange={setUsername}
          placeholder={t('auth.username')}
          className="w-full"
        />
        <TextInput
          value={password}
          onChange={setPassword}
          placeholder={t('auth.password')}
          type="password"
          className="w-full"
        />
      </div>

      <div className="flex items-center gap-2">
        <PrimaryButton onClick={save} disabled={status === 'saving'}>
          {status === 'saving' ? 'Saving…' : 'Save'}
        </PrimaryButton>
        <GhostButton onClick={clear} disabled={status === 'clearing'}>
          Clear
        </GhostButton>
        {cacheStats !== null && (
          <button
            onClick={clearCache}
            className="ml-auto text-xs text-surface-500 hover:text-surface-300 transition-colors"
            title={t('subs.clearCache')}
          >
            Clear cache ({cacheStats.count})
          </button>
        )}
      </div>

      {message && (
        <p className={`text-xs ${status === 'error' ? 'text-red-400' : 'text-accent'}`}>
          {message}
        </p>
      )}
    </div>
  );
}
