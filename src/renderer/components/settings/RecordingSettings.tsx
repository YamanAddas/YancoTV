import { useEffect, useState } from 'react';
import { useSettingsStore } from '../../stores/settings-store';
import { useT } from '../../i18n';
import {
  PageHeading,
  SectionHeading,
  SettingRow,
  Select,
  PathPicker,
  LoadingSpinner,
} from './primitives';

// ---------------------------------------------------------------------------
// Recording Settings — storage path, concurrent limit, max duration
//
// `recording_directory` is the persisted setting; if empty, the backend
// resolves a default under ~/Videos/YancoTV. We display the resolved path
// from `app:getPaths` so the user sees what will actually be used.
// ---------------------------------------------------------------------------

export function RecordingSettings() {
  const t = useT();
  const { get, set, load, loaded } = useSettingsStore();
  const [resolvedPath, setResolvedPath] = useState<string>('');
  const [ffmpegOk, setFfmpegOk] = useState<boolean | null>(null);

  useEffect(() => {
    load();
    refreshPath();
    window.api?.recording.checkFfmpeg().then((ok: boolean) => setFfmpegOk(ok));
  }, [load]);

  async function refreshPath() {
    const paths = await window.api?.app.getPaths();
    if (paths) setResolvedPath(paths.recordings);
  }

  async function pickDirectory() {
    const res = await window.api?.dialog.pickDirectory({
      title: 'Choose recordings folder',
      defaultPath: resolvedPath,
    });
    if (res?.ok && res.path) {
      await set('recording_directory', res.path);
      await refreshPath();
    }
  }

  async function resetDirectory() {
    await set('recording_directory', '');
    await refreshPath();
  }

  async function openFolder() {
    await window.api?.recording.openFolder();
  }

  if (!loaded) return <LoadingSpinner />;

  const configured = get('recording_directory');

  return (
    <div className="space-y-6">
      <PageHeading title={t('settingsTab.recording')} subtitle={t('recording.desc')} />

      {ffmpegOk === false && (
        <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-200">
          ffmpeg was not found on this system. Recording will not work until
          ffmpeg is installed and available on your PATH.
        </div>
      )}

      <div className="space-y-2">
        <SectionHeading>Storage</SectionHeading>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3 space-y-3">
          <div>
            <p className="text-sm font-medium text-surface-200">{t('downloads.saveLocation')}</p>
            <p className="mt-0.5 text-xs text-surface-500">
              Recordings are written as MP4 files into this folder.
            </p>
          </div>
          <PathPicker
            value={resolvedPath}
            placeholder={t('downloads.chooseFolder')}
            onPick={pickDirectory}
            onReveal={openFolder}
            revealLabel="Open folder"
            onReset={resetDirectory}
            canReset={Boolean(configured)}
          />
        </div>
      </div>

      <div className="space-y-2">
        <SectionHeading>Limits</SectionHeading>

        <SettingRow
          label={t('recording.concurrent')}
          description={t('recording.concurrentDesc')}
        >
          <Select
            value={get('recording_max_concurrent')}
            onChange={(v) => set('recording_max_concurrent', v)}
            options={[
              { value: '1', label: '1' },
              { value: '2', label: '2' },
              { value: '3', label: '3' },
              { value: '4', label: '4' },
              { value: '6', label: '6' },
              { value: '10', label: '10' },
            ]}
          />
        </SettingRow>

        <SettingRow
          label={t('recording.maxLength')}
          description={t('recording.maxLengthDesc')}
        >
          <Select
            value={get('recording_max_duration_minutes')}
            onChange={(v) => set('recording_max_duration_minutes', v)}
            options={[
              { value: '0', label: t('value.noLimit') },
              { value: '60', label: '1 hour' },
              { value: '120', label: '2 hours' },
              { value: '180', label: '3 hours' },
              { value: '240', label: '4 hours' },
              { value: '360', label: '6 hours' },
              { value: '480', label: '8 hours' },
              { value: '720', label: '12 hours' },
            ]}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <SectionHeading>Format</SectionHeading>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
          <p className="text-sm font-medium text-surface-200">{t('recording.container')}</p>
          <p className="mt-0.5 text-xs text-surface-500">
            Recordings are stored as MP4 using stream copy — no re-encoding,
            so the file preserves the source quality at minimal CPU cost.
          </p>
        </div>
      </div>
    </div>
  );
}
