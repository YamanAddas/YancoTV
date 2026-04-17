import { useEffect, useState } from 'react';
import { useSettingsStore } from '../../stores/settings-store';
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

const LANGUAGE_OPTIONS = [
  { value: 'off', label: 'Off' },
  { value: 'en', label: 'English' },
  { value: 'es', label: 'Spanish' },
  { value: 'fr', label: 'French' },
  { value: 'de', label: 'German' },
  { value: 'ar', label: 'Arabic' },
  { value: 'pt', label: 'Portuguese' },
  { value: 'tr', label: 'Turkish' },
  { value: 'it', label: 'Italian' },
  { value: 'nl', label: 'Dutch' },
  { value: 'ru', label: 'Russian' },
];

const COLOR_OPTIONS = [
  { value: '#FFFFFF', label: 'White' },
  { value: '#FFFF00', label: 'Yellow' },
  { value: '#FFB400', label: 'Amber' },
  { value: '#00FFC8', label: 'Cyan' },
  { value: '#FF64A5', label: 'Pink' },
];

export function SubtitlesSettings() {
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
        title="Subtitles"
        subtitle="Default language, auto-search, and on-screen appearance"
      />

      <div className="space-y-2">
        <SectionHeading>Language</SectionHeading>

        <SettingRow
          label="Preferred subtitle language"
          description="Auto-select subtitle track when available"
        >
          <Select
            value={get('playback_subtitle_lang')}
            onChange={(v) => set('playback_subtitle_lang', v)}
            options={LANGUAGE_OPTIONS}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <SectionHeading>OpenSubtitles</SectionHeading>

        <SettingRow
          label="Auto-search when playback starts"
          description="Find and download a matching subtitle as soon as a movie / episode plays"
        >
          <Toggle
            checked={getBool('opensubtitles.autoSearch')}
            onChange={(v) => setBool('opensubtitles.autoSearch', v)}
          />
        </SettingRow>

        <OpenSubtitlesCredentials />
      </div>

      <div className="space-y-2">
        <SectionHeading>Appearance</SectionHeading>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-surface-200">Text size</p>
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
          label="Text color"
          description="Color of the subtitle font"
        >
          <Select
            value={currentColor}
            onChange={(v) => set('subtitle_color', v)}
            options={COLOR_OPTIONS}
          />
        </SettingRow>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-surface-200">Background opacity</p>
              <p className="mt-0.5 text-xs text-surface-500">
                Opacity of the shaded box behind subtitle text
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
        Sample subtitle text
      </span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// OpenSubtitles credentials (moved from PlaybackSettings)
// ---------------------------------------------------------------------------

function OpenSubtitlesCredentials() {
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
        <p className="text-sm font-medium text-surface-200">OpenSubtitles account</p>
        <p className="mt-0.5 text-xs text-surface-500">
          Optional — raises your daily download limit. Anonymous users get 5/day.
        </p>
      </div>

      <div className="space-y-2">
        <TextInput
          value={username}
          onChange={setUsername}
          placeholder="Username"
          className="w-full"
        />
        <TextInput
          value={password}
          onChange={setPassword}
          placeholder="Password"
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
            title="Clear subtitle cache"
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
