import { useEffect, useState } from 'react';
import { useSettingsStore } from '../../stores/settings-store';
import { useT } from '../../i18n';
import {
  PageHeading,
  SectionHeading,
  SettingRow,
  Select,
  Toggle,
  PathPicker,
  LoadingSpinner,
} from './primitives';

// ---------------------------------------------------------------------------
// Downloads Settings — save directory, concurrency, asset bundling, safety
// ---------------------------------------------------------------------------

export function DownloadsSettings() {
  const t = useT();
  const { get, getBool, set, setBool, load, loaded } = useSettingsStore();
  const [resolvedPath, setResolvedPath] = useState<string>('');

  useEffect(() => {
    load();
    refreshPath();
  }, [load]);

  async function refreshPath() {
    const paths = await window.api?.app.getPaths();
    if (paths) setResolvedPath(paths.downloads);
  }

  async function pickDirectory() {
    const res = await window.api?.dialog.pickDirectory({
      title: 'Choose downloads folder',
      defaultPath: resolvedPath,
    });
    if (res?.ok && res.path) {
      await set('download_directory', res.path);
      await refreshPath();
    }
  }

  async function resetDirectory() {
    await set('download_directory', '');
    await refreshPath();
  }

  async function openFolder() {
    await window.api?.download.openFolder();
  }

  if (!loaded) return <LoadingSpinner />;

  const configured = get('download_directory');

  return (
    <div className="space-y-6">
      <PageHeading
        title={t('nav.downloads')}
        subtitle={t('downloads.desc')}
      />

      <div className="space-y-2">
        <SectionHeading>Storage</SectionHeading>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3 space-y-3">
          <div>
            <p className="text-sm font-medium text-surface-200">{t('downloads.saveLocation')}</p>
            <p className="mt-0.5 text-xs text-surface-500">
              Movies and episodes are saved into this folder alongside their
              poster / backdrop / .nfo assets.
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
        <SectionHeading>Queue</SectionHeading>

        <SettingRow
          label={t('downloads.concurrent')}
          description={t('downloads.concurrentDesc')}
        >
          <Select
            value={get('download_max_concurrent')}
            onChange={(v) => set('download_max_concurrent', v)}
            options={[
              { value: '1', label: '1' },
              { value: '2', label: '2' },
              { value: '3', label: '3' },
              { value: '4', label: '4' },
              { value: '6', label: '6' },
              { value: '8', label: '8' },
              { value: '10', label: '10' },
            ]}
          />
        </SettingRow>

        <SettingRow
          label={t('downloads.quality')}
          description={t('downloads.qualityDesc')}
        >
          <Select
            value={get('download_preferred_quality')}
            onChange={(v) => set('download_preferred_quality', v)}
            options={[
              { value: 'auto', label: t('value.autoHighest') },
              { value: '1080', label: '1080p' },
              { value: '720', label: '720p' },
              { value: '480', label: '480p' },
              { value: '360', label: '360p' },
            ]}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <SectionHeading>{t('downloads.companionAssets')}</SectionHeading>

        <SettingRow
          label={t('downloads.fetchArtwork')}
          description={t('downloads.fetchArtworkDesc')}
        >
          <Toggle
            checked={getBool('download_fetch_assets')}
            onChange={(v) => setBool('download_fetch_assets', v)}
          />
        </SettingRow>

        <SettingRow
          label={t('downloads.extractSubs')}
          description={t('downloads.extractSubsDesc')}
        >
          <Toggle
            checked={getBool('download_extract_subtitles')}
            onChange={(v) => setBool('download_extract_subtitles', v)}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <SectionHeading>Safety</SectionHeading>

        <SettingRow
          label={t('downloads.maxSize')}
          description={t('downloads.maxSizeDesc')}
        >
          <Select
            value={get('download_max_file_size_gb')}
            onChange={(v) => set('download_max_file_size_gb', v)}
            options={[
              { value: '5', label: '5 GB' },
              { value: '10', label: '10 GB' },
              { value: '25', label: '25 GB' },
              { value: '50', label: '50 GB' },
              { value: '100', label: '100 GB' },
              { value: '0', label: t('value.noLimit') },
            ]}
          />
        </SettingRow>

        <SettingRow
          label={t('downloads.allowPrivate')}
          description={t('downloads.allowPrivateDesc')}
        >
          <Toggle
            checked={getBool('download_allow_private_ips')}
            onChange={(v) => setBool('download_allow_private_ips', v)}
          />
        </SettingRow>
      </div>
    </div>
  );
}
