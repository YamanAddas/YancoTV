import { useState, useCallback, useEffect } from 'react';
import { useEpgSettings, useEpgStats, triggerEpgRefresh } from '../../hooks/use-epg';
import { useSettingsStore } from '../../stores/settings-store';

// ---------------------------------------------------------------------------
// EPG Settings — global EPG URL, refresh controls, statistics
// ---------------------------------------------------------------------------

export function EpgSettings() {
  const { data: settings } = useEpgSettings();
  const { data: stats } = useEpgStats();
  const { get, set: saveSetting, load } = useSettingsStore();

  const [globalUrl, setGlobalUrl] = useState('');
  const [urlDirty, setUrlDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshResult, setRefreshResult] = useState<string | null>(null);

  useEffect(() => {
    load();
  }, [load]);

  // Sync local state from fetched settings (only when not user-edited)
  if (settings?.globalEpgUrl && !urlDirty && globalUrl !== settings.globalEpgUrl) {
    setGlobalUrl(settings.globalEpgUrl);
  }

  const handleSaveUrl = useCallback(async () => {
    setSaving(true);
    try {
      await window.api.epg.setGlobalUrl(globalUrl.trim());
      setUrlDirty(false);
    } catch (err) {
      console.error('Failed to save EPG URL:', err);
    } finally {
      setSaving(false);
    }
  }, [globalUrl]);

  const handleRefresh = useCallback(async () => {
    setRefreshing(true);
    setRefreshResult(null);
    try {
      const result = await triggerEpgRefresh();
      if (result.ok) {
        setRefreshResult(
          `Refreshed successfully! ${result.programmeCount} programmes, ${result.channelCount} channels loaded.`,
        );
      } else {
        setRefreshResult(`Refresh failed: ${result.error}`);
      }
    } catch (err) {
      setRefreshResult(`Error: ${String(err)}`);
    } finally {
      setRefreshing(false);
    }
  }, []);

  const lastRefreshed = settings?.lastRefreshedAt
    ? new Date(settings.lastRefreshedAt).toLocaleString()
    : 'Never';

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-surface-100">EPG</h2>
        <p className="mt-1 text-sm text-surface-500">
          Electronic Programme Guide settings and data management
        </p>
      </div>

      {/* Global EPG URL */}
      <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
          EPG Source
        </h3>
        <p className="mb-3 text-xs text-surface-500">
          Set a global XMLTV EPG URL that applies to all sources. Individual
          sources can also have their own EPG URL (auto-detected from M3U
          headers or Xtream API).
        </p>
        <div className="flex gap-2">
          <input
            type="url"
            value={globalUrl}
            onChange={(e) => {
              setGlobalUrl(e.target.value);
              setUrlDirty(true);
            }}
            placeholder="https://epg-provider.com/guide.xml.gz"
            className="flex-1 rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-2 text-sm text-surface-200 placeholder-surface-500 focus:border-accent/50 focus:outline-none focus:ring-1 focus:ring-accent/30"
          />
          <button
            onClick={handleSaveUrl}
            disabled={saving || !urlDirty}
            className="rounded-lg bg-accent shadow-glow-sm px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover hover:shadow-glow disabled:opacity-50"
          >
            {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </section>

      {/* Refresh controls */}
      <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
          Refresh
        </h3>
        <div className="flex items-center gap-4">
          <button
            onClick={handleRefresh}
            disabled={refreshing}
            className="flex items-center gap-2 rounded-lg bg-accent shadow-glow-sm px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover hover:shadow-glow disabled:opacity-50"
          >
            <svg
              className={`h-4 w-4 ${refreshing ? 'animate-spin' : ''}`}
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={2}
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
              />
            </svg>
            {refreshing ? 'Refreshing...' : 'Refresh EPG Now'}
          </button>
          <span className="text-xs text-surface-500">
            Last refreshed: {lastRefreshed}
          </span>
        </div>

        {refreshResult && (
          <p
            className={`mt-3 rounded-lg px-3 py-2 text-sm ${
              refreshResult.startsWith('Refreshed')
                ? 'bg-green-500/10 text-green-400'
                : 'bg-red-500/10 text-red-400'
            }`}
          >
            {refreshResult}
          </p>
        )}

        <div className="mt-4 flex items-center justify-between rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
          <div>
            <p className="text-sm font-medium text-surface-200">
              Auto-refresh interval
            </p>
            <p className="mt-0.5 text-xs text-surface-500">
              How often EPG data is automatically updated
            </p>
          </div>
          <select
            value={get('epg_refresh_interval')}
            onChange={(e) => saveSetting('epg_refresh_interval', e.target.value)}
            className="rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-1.5 text-sm text-surface-200 focus:border-accent/50 focus:outline-none focus:ring-1 focus:ring-accent/30"
          >
            <option value={6}>Every 6 hours</option>
            <option value={12}>Every 12 hours</option>
            <option value={24}>Every 24 hours</option>
            <option value={48}>Every 2 days</option>
          </select>
        </div>
      </section>

      {/* EPG Stats */}
      <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
          Statistics
        </h3>
        <div className="grid grid-cols-3 gap-3">
          <StatCard
            label="Programmes"
            value={stats?.programmeCount?.toLocaleString() ?? '—'}
          />
          <StatCard
            label="Channels"
            value={stats?.channelCount?.toLocaleString() ?? '—'}
          />
          <StatCard label="Last Refresh" value={lastRefreshed} small />
        </div>
      </section>
    </div>
  );
}

function StatCard({
  label,
  value,
  small,
}: {
  label: string;
  value: string;
  small?: boolean;
}) {
  return (
    <div className="rounded-lg border border-accent/5 bg-surface-950/50 px-4 py-3 text-center">
      <p className="text-xs text-surface-500">{label}</p>
      <p
        className={`mt-1 font-semibold text-surface-200 ${small ? 'text-xs' : 'text-lg'}`}
      >
        {value}
      </p>
    </div>
  );
}
