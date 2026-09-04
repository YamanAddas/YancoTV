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

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function AdvancedSettings() {
  const { get, getBool, set, setBool, load, loaded } = useSettingsStore();
  const [paths, setPaths] = useState<{ userData: string; logs: string } | null>(null);
  const [backupStatus, setBackupStatus] = useState<
    | { kind: 'idle' }
    | { kind: 'busy' }
    | { kind: 'ok'; path: string; bytes: number; warnings: string[] }
    | { kind: 'error'; error: string }
  >({ kind: 'idle' });
  const [importMode, setImportMode] = useState<'merge' | 'replace'>('merge');
  const [importStatus, setImportStatus] = useState<
    | { kind: 'idle' }
    | { kind: 'busy' }
    | {
        kind: 'ok';
        stats: {
          sourcesImported: number;
          favoritesImported: number;
          favoritesSkipped: number;
          historyImported: number;
          historySkipped: number;
          settingsImported: number;
          lockedImported: number;
          hiddenImported: number;
          overridesImported: number;
          groupPrefsImported: number;
        };
        warnings: string[];
      }
    | { kind: 'error'; error: string }
  >({ kind: 'idle' });
  const [logExportStatus, setLogExportStatus] = useState<
    { kind: 'idle' } | { kind: 'busy' } | { kind: 'ok'; path: string; bytes: number } | { kind: 'error'; error: string }
  >({ kind: 'idle' });

  useEffect(() => {
    load();
    window.api?.app.getPaths().then((p) => {
      if (p) setPaths({ userData: p.userData, logs: p.logs });
    });
  }, [load]);

  async function exportBackup() {
    setBackupStatus({ kind: 'busy' });
    const res = await window.api?.backup.export();
    if (!res) {
      setBackupStatus({ kind: 'error', error: 'Backup API unavailable' });
      return;
    }
    if (!res.ok) {
      if ('cancelled' in res && res.cancelled) {
        setBackupStatus({ kind: 'idle' });
        return;
      }
      setBackupStatus({ kind: 'error', error: res.error ?? 'Export failed' });
      return;
    }
    setBackupStatus({ kind: 'ok', path: res.path, bytes: res.bytes, warnings: res.warnings });
  }

  async function importBackup() {
    if (importMode === 'replace') {
      const confirmed = window.confirm(
        'Replace mode will wipe all current sources, favorites, history, settings, parental rules, and group preferences before restoring from the backup. This cannot be undone.\n\nContinue?',
      );
      if (!confirmed) return;
    }
    setImportStatus({ kind: 'busy' });
    const res = await window.api?.backup.import(importMode);
    if (!res) {
      setImportStatus({ kind: 'error', error: 'Backup API unavailable' });
      return;
    }
    if (!res.ok) {
      if ('cancelled' in res && res.cancelled) {
        setImportStatus({ kind: 'idle' });
        return;
      }
      setImportStatus({ kind: 'error', error: res.error ?? 'Import failed' });
      return;
    }
    setImportStatus({ kind: 'ok', stats: res.stats, warnings: res.warnings });
  }

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

  async function exportLogs() {
    setLogExportStatus({ kind: 'busy' });
    const res = await window.api?.app.exportLogs();
    if (!res) {
      setLogExportStatus({ kind: 'error', error: 'Log export API unavailable' });
      return;
    }
    if (!res.ok) {
      if ('cancelled' in res && res.cancelled) {
        setLogExportStatus({ kind: 'idle' });
        return;
      }
      setLogExportStatus({ kind: 'error', error: res.error ?? 'Log export failed' });
      return;
    }
    setLogExportStatus({ kind: 'ok', path: res.path, bytes: res.bytes });
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
        <SectionHeading>Backup</SectionHeading>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3 space-y-3">
          <div>
            <p className="text-sm font-medium text-surface-200">Export backup</p>
            <p className="mt-0.5 text-xs text-surface-500">
              Save your sources, favorites, watch history, settings, parental
              rules, and group preferences to a single JSON file. Credentials
              are exported in plaintext — keep the file private.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <PrimaryButton onClick={exportBackup} disabled={backupStatus.kind === 'busy'}>
              {backupStatus.kind === 'busy' ? 'Exporting…' : 'Export…'}
            </PrimaryButton>
            {backupStatus.kind === 'ok' && (
              <span className="truncate text-xs text-surface-400" title={backupStatus.path}>
                Saved {formatBytes(backupStatus.bytes)} — {backupStatus.path}
              </span>
            )}
            {backupStatus.kind === 'error' && (
              <span className="text-xs text-red-400">{backupStatus.error}</span>
            )}
          </div>
          {/* Surface decryption warnings — backup file is otherwise complete,
              but the affected source(s) won't have credentials when restored. */}
          {backupStatus.kind === 'ok' && backupStatus.warnings.length > 0 && (
            <ul className="mt-2 space-y-1 rounded-md border border-amber-500/30 bg-amber-500/5 p-2 text-xs text-amber-200">
              {backupStatus.warnings.map((w, i) => (
                <li key={i} className="flex items-start gap-1.5 leading-snug">
                  <svg
                    aria-hidden
                    className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-amber-400"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    strokeWidth={1.75}
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z"
                    />
                  </svg>
                  <span>{w}</span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3 space-y-3">
          <div>
            <p className="text-sm font-medium text-surface-200">Restore backup</p>
            <p className="mt-0.5 text-xs text-surface-500">
              Load a previously exported backup. Favorites and history are
              re-linked to your current sources by stream URL — any that can&apos;t
              be matched will be skipped until you re-sync.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <label className="flex items-center gap-2 text-xs text-surface-300">
              <input
                type="radio"
                name="import-mode"
                value="merge"
                checked={importMode === 'merge'}
                onChange={() => setImportMode('merge')}
                className="accent-accent"
              />
              <span>
                <span className="font-medium text-surface-200">Merge</span>
                {' — keep existing, add/update from backup'}
              </span>
            </label>
            <label className="flex items-center gap-2 text-xs text-surface-300">
              <input
                type="radio"
                name="import-mode"
                value="replace"
                checked={importMode === 'replace'}
                onChange={() => setImportMode('replace')}
                className="accent-accent"
              />
              <span>
                <span className="font-medium text-surface-200">Replace</span>
                {' — wipe current data first'}
              </span>
            </label>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <PrimaryButton onClick={importBackup} disabled={importStatus.kind === 'busy'}>
              {importStatus.kind === 'busy' ? 'Importing…' : 'Import…'}
            </PrimaryButton>
            {importStatus.kind === 'error' && (
              <span className="text-xs text-red-400">{importStatus.error}</span>
            )}
          </div>
          {importStatus.kind === 'ok' && (
            <div className="space-y-1 text-xs text-surface-400">
              <p>
                Imported {importStatus.stats.sourcesImported} sources,{' '}
                {importStatus.stats.settingsImported} settings,{' '}
                {importStatus.stats.groupPrefsImported} group preferences,{' '}
                {importStatus.stats.favoritesImported} favorites,{' '}
                {importStatus.stats.historyImported} history entries.
              </p>
              {importStatus.warnings.map((w, i) => (
                <p key={i} className="text-amber-300">
                  {w}
                </p>
              ))}
            </div>
          )}
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
            <p className="text-sm font-medium text-surface-200">Export log file</p>
            <p className="mt-0.5 text-xs text-surface-500">
              Save a snapshot of the current log for bug reports. The live log
              keeps running — this is just a copy.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <PrimaryButton onClick={exportLogs} disabled={logExportStatus.kind === 'busy'}>
              {logExportStatus.kind === 'busy' ? 'Exporting…' : 'Export logs…'}
            </PrimaryButton>
            {logExportStatus.kind === 'ok' && (
              <span className="truncate text-xs text-surface-400" title={logExportStatus.path}>
                Saved {formatBytes(logExportStatus.bytes)} — {logExportStatus.path}
              </span>
            )}
            {logExportStatus.kind === 'error' && (
              <span className="text-xs text-red-400">{logExportStatus.error}</span>
            )}
          </div>
        </div>

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
