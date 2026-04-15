import { useEffect, useState, useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AddSourceForm } from '../components/AddSourceForm';
import { SourceList } from '../components/SourceList';
import { useEpgSettings, useEpgStats, triggerEpgRefresh } from '../hooks/use-epg';

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

      <EpgSettingsSection />

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

function EpgSettingsSection() {
  const queryClient = useQueryClient();
  const { data: settings } = useEpgSettings();
  const { data: stats } = useEpgStats();
  const [globalUrl, setGlobalUrl] = useState('');
  const [saving, setSaving] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [message, setMessage] = useState('');

  useEffect(() => {
    if (settings?.globalEpgUrl) {
      setGlobalUrl(settings.globalEpgUrl);
    }
  }, [settings?.globalEpgUrl]);

  const handleSaveUrl = async () => {
    setSaving(true);
    setMessage('');
    try {
      const result = await window.api.epg.setGlobalUrl(globalUrl.trim());
      if (result.ok) {
        setMessage('EPG URL saved');
        queryClient.invalidateQueries({ queryKey: ['epg', 'settings'] });
      } else {
        setMessage(result.error || 'Failed to save');
      }
    } finally {
      setSaving(false);
    }
  };

  const handleRefresh = async () => {
    setRefreshing(true);
    setMessage('');
    try {
      const result = await triggerEpgRefresh();
      if (result.ok) {
        setMessage(
          `EPG refreshed: ${result.programmeCount?.toLocaleString()} programmes, ${result.channelCount} channels`,
        );
        queryClient.invalidateQueries({ queryKey: ['epg'] });
      } else {
        setMessage(result.error || 'Refresh failed');
      }
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <section className="rounded-xl border border-surface-800 bg-surface-900 p-5">
      <h3 className="mb-4 text-lg font-semibold text-surface-200">EPG (TV Guide)</h3>

      <div className="space-y-3">
        <div>
          <label className="mb-1 block text-sm font-medium text-surface-300">
            Global EPG URL
          </label>
          <p className="mb-2 text-xs text-surface-500">
            XMLTV format (plain or gzipped). Used as fallback when a source has no EPG URL.
          </p>
          <div className="flex gap-2">
            <input
              type="url"
              value={globalUrl}
              onChange={(e) => setGlobalUrl(e.target.value)}
              placeholder="https://example.com/epg.xml.gz"
              className="flex-1 rounded-lg border border-surface-700 bg-surface-800 px-3 py-2 text-sm text-surface-200 placeholder-surface-500 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
            <button
              onClick={handleSaveUrl}
              disabled={saving}
              className="rounded-lg bg-surface-700 px-4 py-2 text-sm font-medium text-surface-200 hover:bg-surface-600 disabled:opacity-50"
            >
              {saving ? 'Saving...' : 'Save'}
            </button>
          </div>
        </div>

        <div className="flex items-center justify-between">
          <div className="text-sm text-surface-400">
            {stats ? (
              <>
                {stats.programmeCount.toLocaleString()} programmes &middot;{' '}
                {stats.channelCount} channels
                {stats.lastRefreshedAt && (
                  <>
                    {' '}
                    &middot; Last updated:{' '}
                    {new Date(stats.lastRefreshedAt).toLocaleString()}
                  </>
                )}
              </>
            ) : (
              'No EPG data loaded'
            )}
          </div>
          <button
            onClick={handleRefresh}
            disabled={refreshing}
            className="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent-hover disabled:opacity-50"
          >
            {refreshing ? 'Refreshing...' : 'Refresh Now'}
          </button>
        </div>

        {message && (
          <p className="rounded-lg bg-surface-800 px-3 py-2 text-sm text-surface-300">
            {message}
          </p>
        )}
      </div>
    </section>
  );
}
