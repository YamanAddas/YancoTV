import { useEffect, useState, useCallback } from 'react';
import { AddSourceForm } from '../components/AddSourceForm';
import { SourceList } from '../components/SourceList';

interface Source {
  id: string;
  name: string;
  type: string;
  url?: string;
  filePath?: string;
  lastSynced?: number;
  isActive: boolean;
}

export function SettingsPage() {
  const [sources, setSources] = useState<Source[]>([]);
  const [dbStatus, setDbStatus] = useState<{
    ok: boolean;
    tables?: string[];
    counts?: Record<string, number>;
  } | null>(null);
  const [appVersion, setAppVersion] = useState('');

  const refreshSources = useCallback(async () => {
    if (!window.api) return;
    const result = await window.api.sources.getAll();
    setSources(result);
  }, []);

  useEffect(() => {
    if (!window.api) return;
    refreshSources();
    window.api.db.status().then(setDbStatus);
    window.api.app.getVersion().then(setAppVersion);
  }, [refreshSources]);

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-surface-100">Settings</h2>

      <AddSourceForm onSourceAdded={refreshSources} />

      <SourceList sources={sources} onRefresh={refreshSources} />

      <section className="rounded-xl border border-surface-800 bg-surface-900 p-5">
        <h3 className="mb-4 text-lg font-semibold text-surface-200">System Info</h3>
        <div className="space-y-2 text-sm">
          <div className="flex justify-between">
            <span className="text-surface-400">App Version</span>
            <span className="text-surface-200">{appVersion || 'Loading...'}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-surface-400">Database</span>
            <span className={dbStatus?.ok ? 'text-green-400' : 'text-surface-200'}>
              {dbStatus === null ? 'Checking...' : dbStatus.ok ? 'Connected' : 'Error'}
            </span>
          </div>
          {dbStatus?.counts && (
            <>
              <div className="flex justify-between">
                <span className="text-surface-400">Live Channels</span>
                <span className="text-surface-200">{dbStatus.counts.live ?? 0}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-surface-400">Movies</span>
                <span className="text-surface-200">{dbStatus.counts.movie ?? 0}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-surface-400">Series</span>
                <span className="text-surface-200">{dbStatus.counts.series ?? 0}</span>
              </div>
            </>
          )}
        </div>
      </section>
    </div>
  );
}
