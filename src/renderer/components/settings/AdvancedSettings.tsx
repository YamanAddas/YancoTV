import { useEffect, useState } from 'react';
import { useSettingsStore } from '../../stores/settings-store';
import {
  PageHeading,
  SectionHeading,
  SettingRow,
  Toggle,
  PathPicker,
  PrimaryButton,
  LoadingSpinner,
} from './primitives';

// ---------------------------------------------------------------------------
// Advanced Settings — mpv override, data directory, hw acceleration, debug
//
// These are power-user knobs. Each one is intentionally low-key so the
// casual user scrolls past them. Changes requiring a restart say so.
// ---------------------------------------------------------------------------

export function AdvancedSettings() {
  const { get, getBool, set, setBool, load, loaded } = useSettingsStore();
  const [paths, setPaths] = useState<{ userData: string; logs: string } | null>(null);

  useEffect(() => {
    load();
    window.api?.app.getPaths().then((p) => {
      if (p) setPaths({ userData: p.userData, logs: p.logs });
    });
  }, [load]);

  async function pickMpv() {
    const res = await window.api?.dialog.pickFile({
      title: 'Select mpv.exe',
      filters: [
        { name: 'Executables', extensions: ['exe'] },
        { name: 'All Files', extensions: ['*'] },
      ],
    });
    if (res?.ok && res.path) {
      await set('advanced_mpv_path', res.path);
    }
  }

  async function resetMpv() {
    await set('advanced_mpv_path', '');
  }

  async function openDataDir() {
    await window.api?.app.openDataDir();
  }

  if (!loaded) return <LoadingSpinner />;

  const mpvPath = get('advanced_mpv_path');

  return (
    <div className="space-y-6">
      <PageHeading
        title="Advanced"
        subtitle="Power-user settings. Leave defaults unless you know why you're changing them."
      />

      <div className="space-y-2">
        <SectionHeading>Playback engine</SectionHeading>

        <SettingRow
          label="Hardware acceleration"
          description="Use GPU for video decoding. Disable only if you see rendering glitches."
        >
          <Toggle
            checked={getBool('playback_hw_accel')}
            onChange={(v) => setBool('playback_hw_accel', v)}
          />
        </SettingRow>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3 space-y-3">
          <div>
            <p className="text-sm font-medium text-surface-200">Custom mpv path</p>
            <p className="mt-0.5 text-xs text-surface-500">
              Override the bundled mpv with a specific build. Leave blank to
              use the bundled version or auto-discover mpv on your PATH.
            </p>
          </div>
          <PathPicker
            value={mpvPath}
            placeholder="(using bundled / auto-discovered mpv)"
            onPick={pickMpv}
            onReset={resetMpv}
            canReset={Boolean(mpvPath)}
          />
          <p className="text-xs text-surface-500">
            Takes effect on the next stream you start — any existing playback
            continues with the current process.
          </p>
        </div>
      </div>

      <div className="space-y-2">
        <SectionHeading>Diagnostics</SectionHeading>

        <SettingRow
          label="Debug logging"
          description="Write verbose logs to disk. Useful when reporting bugs."
        >
          <Toggle
            checked={getBool('advanced_debug_logging')}
            onChange={(v) => setBool('advanced_debug_logging', v)}
          />
        </SettingRow>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3 space-y-3">
          <div>
            <p className="text-sm font-medium text-surface-200">Data directory</p>
            <p className="mt-0.5 text-xs text-surface-500">
              Settings, database, and cached files live here. Handy for
              backups or log export.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <div
              className="flex-1 truncate rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-1.5 text-xs font-mono text-surface-300"
              title={paths?.userData ?? ''}
            >
              {paths?.userData ?? <span className="text-surface-500">(loading…)</span>}
            </div>
            <PrimaryButton onClick={openDataDir} disabled={!paths}>Open</PrimaryButton>
          </div>
          {paths && (
            <div className="pt-1">
              <p className="text-xs text-surface-500">Logs:</p>
              <p className="mt-0.5 break-all text-xs font-mono text-surface-400">
                {paths.logs}
              </p>
            </div>
          )}
        </div>

        <p className="px-1 text-xs text-surface-500">
          Debug logging changes take effect the next time the app starts.
        </p>
      </div>
    </div>
  );
}
