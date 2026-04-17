import { useEffect, useState } from 'react';
import { useSettingsStore } from '../../stores/settings-store';
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
        title="Downloads"
        subtitle="Where VOD downloads are saved and how they behave"
      />

      <div className="space-y-2">
        <SectionHeading>Storage</SectionHeading>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3 space-y-3">
          <div>
            <p className="text-sm font-medium text-surface-200">Save location</p>
            <p className="mt-0.5 text-xs text-surface-500">
              Movies and episodes are saved into this folder alongside their
              poster / backdrop / .nfo assets.
            </p>
          </div>
          <PathPicker
            value={resolvedPath}
            placeholder="Choose a folder"
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
          label="Concurrent downloads"
          description="How many files can download at the same time (1–10)"
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
          label="Preferred quality"
          description="Hint for provider streams that offer multiple renditions"
        >
          <Select
            value={get('download_preferred_quality')}
            onChange={(v) => set('download_preferred_quality', v)}
            options={[
              { value: 'auto', label: 'Auto (highest)' },
              { value: '1080', label: '1080p' },
              { value: '720', label: '720p' },
              { value: '480', label: '480p' },
              { value: '360', label: '360p' },
            ]}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <SectionHeading>Companion assets</SectionHeading>

        <SettingRow
          label="Fetch poster / backdrop / .nfo"
          description="Save Kodi-compatible metadata alongside each download"
        >
          <Toggle
            checked={getBool('download_fetch_assets')}
            onChange={(v) => setBool('download_fetch_assets', v)}
          />
        </SettingRow>

        <SettingRow
          label="Extract embedded subtitles"
          description="Pull subtitle tracks out of the file after download completes"
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
          label="Maximum file size"
          description="Refuse downloads that advertise a larger size"
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
              { value: '0', label: 'No limit' },
            ]}
          />
        </SettingRow>

        <SettingRow
          label="Allow private network addresses"
          description="Permit downloads from LAN / loopback hosts. Leave off unless you know why you need it."
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
