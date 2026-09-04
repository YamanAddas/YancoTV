import { useState, useEffect } from 'react';
import { useT } from '../../i18n';

// ---------------------------------------------------------------------------
// About — app version, system info, database stats, credits
// ---------------------------------------------------------------------------

interface DbStatus {
  channelCount?: number;
  movieCount?: number;
  seriesCount?: number;
  sourceCount?: number;
  dbSizeMB?: number;
}

type UpdateState =
  | { kind: 'idle' }
  | { kind: 'busy' }
  | { kind: 'up-to-date'; currentVersion: string }
  | { kind: 'update-available'; latestVersion: string; url?: string; notes?: string }
  | { kind: 'not-configured' }
  | { kind: 'error'; error: string };

export function AboutSettings() {
  const t = useT();
  const [version, setVersion] = useState('...');
  const [dbStatus, setDbStatus] = useState<DbStatus>({});
  const [epgStats, setEpgStats] = useState<{
    programmeCount?: number;
    channelCount?: number;
  }>({});
  const [updateState, setUpdateState] = useState<UpdateState>({ kind: 'idle' });

  useEffect(() => {
    if (!window.api) return;
    window.api.app.getVersion().then((v: string) => setVersion(v));
    window.api.db.status().then((s: DbStatus) => setDbStatus(s));
    window.api.epg.getStats().then(
      (s: { programmeCount?: number; channelCount?: number }) =>
        setEpgStats(s),
    );
  }, []);

  async function checkForUpdates() {
    setUpdateState({ kind: 'busy' });
    const res = await window.api?.app.checkForUpdates();
    if (!res) {
      setUpdateState({ kind: 'error', error: 'Update check API unavailable' });
      return;
    }
    if (res.ok && res.status === 'up-to-date') {
      setUpdateState({ kind: 'up-to-date', currentVersion: res.currentVersion });
    } else if (res.ok && res.status === 'update-available') {
      setUpdateState({
        kind: 'update-available',
        latestVersion: res.latestVersion,
        url: res.url,
        notes: res.notes,
      });
    } else if (!res.ok && res.status === 'not-configured') {
      setUpdateState({ kind: 'not-configured' });
    } else {
      setUpdateState({ kind: 'error', error: (res as { error?: string }).error ?? 'Update check failed' });
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-surface-100">{t('settingsTab.about')}</h2>
        <p className="mt-1 text-sm text-surface-500">
          {t('about.desc')}
        </p>
      </div>

      {/* App info */}
      <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
        <div className="flex items-center gap-4">
          <div className="flex h-14 w-14 items-center justify-center rounded-xl bg-accent/10">
            <svg
              className="h-8 w-8 text-accent"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={1.5}
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M6 20.25h12m-7.5-3v3m3-3v3m-10.125-3h17.25c.621 0 1.125-.504 1.125-1.125V4.875c0-.621-.504-1.125-1.125-1.125H3.375c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125z"
              />
            </svg>
          </div>
          <div>
            <img
              src={new URL('../../assets/yancotv_logo.png', import.meta.url).href}
              alt="YancoTV"
              className="h-8 w-auto object-contain"
              draggable={false}
            />
            <p className="text-sm text-surface-400">Version {version}</p>
            <p className="mt-0.5 text-xs text-surface-500">
              {t('about.tagline')}
            </p>
          </div>
        </div>

        <div className="mt-4 border-t border-surface-800/60 pt-4">
          <div className="flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={checkForUpdates}
              disabled={updateState.kind === 'busy'}
              className="rounded-lg border border-surface-700/60 bg-surface-800/50 px-3 py-1.5 text-sm font-medium text-surface-200 transition hover:border-accent/40 hover:text-surface-50 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {updateState.kind === 'busy' ? 'Checking…' : 'Check for updates'}
            </button>
            <UpdateStatusLine state={updateState} />
          </div>
        </div>
      </section>

      {/* Database stats */}
      <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
          Database
        </h3>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <StatCard
            label="Sources"
            value={dbStatus.sourceCount?.toLocaleString() ?? '—'}
          />
          <StatCard
            label={t('stats.liveChannels')}
            value={dbStatus.channelCount?.toLocaleString() ?? '—'}
          />
          <StatCard
            label="Movies"
            value={dbStatus.movieCount?.toLocaleString() ?? '—'}
          />
          <StatCard
            label="Series"
            value={dbStatus.seriesCount?.toLocaleString() ?? '—'}
          />
          <StatCard
            label={t('stats.epgProgrammes')}
            value={epgStats.programmeCount?.toLocaleString() ?? '—'}
          />
          <StatCard
            label={t('stats.epgChannels')}
            value={epgStats.channelCount?.toLocaleString() ?? '—'}
          />
        </div>
        {dbStatus.dbSizeMB !== undefined && (
          <p className="mt-3 text-xs text-surface-500">
            Database size: {dbStatus.dbSizeMB.toFixed(1)} MB
          </p>
        )}
      </section>

      {/* System info */}
      <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
          System
        </h3>
        <div className="space-y-1.5 text-sm">
          <InfoRow label={t('about.platform')} value={navigator.platform} />
          <InfoRow label={t('about.userAgent')} value={navigator.userAgent} />
          <InfoRow
            label="Screen"
            value={`${window.screen.width} x ${window.screen.height}`}
          />
          <InfoRow label={t('settings.language')} value={navigator.language} />
        </div>
      </section>

      {/* Tech stack */}
      <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
          {t('about.builtWith')}
        </h3>
        <div className="flex flex-wrap gap-2">
          {[
            'Electron',
            'React',
            'TypeScript',
            'Tailwind CSS',
            'SQLite',
            'mpv',
            'Vite',
          ].map((tech) => (
            <span
              key={tech}
              className="rounded-full border border-surface-700/50 bg-surface-800/40 px-3 py-1 text-xs font-medium text-surface-300"
            >
              {tech}
            </span>
          ))}
        </div>
      </section>
    </div>
  );
}

function UpdateStatusLine({ state }: { state: UpdateState }) {
  if (state.kind === 'idle' || state.kind === 'busy') return null;
  if (state.kind === 'up-to-date') {
    return (
      <span className="text-xs text-emerald-300">You&apos;re on the latest version.</span>
    );
  }
  if (state.kind === 'update-available') {
    return (
      <span className="text-xs text-amber-300">
        Update available: v{state.latestVersion}
        {state.url && (
          <>
            {' — '}
            <a href={state.url} target="_blank" rel="noreferrer" className="underline">
              Download
            </a>
          </>
        )}
      </span>
    );
  }
  if (state.kind === 'not-configured') {
    return (
      <span className="text-xs text-surface-500">
        Automatic update checks will be enabled in a later release.
      </span>
    );
  }
  return <span className="text-xs text-red-400">{state.error}</span>;
}

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-accent/5 bg-surface-950/50 px-4 py-3 text-center">
      <p className="text-xs text-surface-500">{label}</p>
      <p className="mt-1 text-lg font-semibold text-surface-200">{value}</p>
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-3 rounded px-2 py-1">
      <span className="w-24 flex-shrink-0 text-surface-500">{label}</span>
      <span className="min-w-0 break-all text-surface-300">{value}</span>
    </div>
  );
}
