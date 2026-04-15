import { useState, useEffect, useCallback } from 'react';
import { AddSourceForm } from '../AddSourceForm';
import { SourceList } from '../SourceList';
import { useSettingsStore } from '../../stores/settings-store';

// ---------------------------------------------------------------------------
// Playlist / Source Settings — manage sources + auto-sync options
// ---------------------------------------------------------------------------

interface Source {
  id: string;
  name: string;
  type: string;
  url?: string;
  filePath?: string;
  lastSynced?: number;
  isActive: boolean;
}

export function PlaylistSettings() {
  const [sources, setSources] = useState<Source[]>([]);
  const { get, set, setBool, getBool, load, loaded } = useSettingsStore();

  const loadSources = useCallback(async () => {
    try {
      const list = await window.api.sources.getAll();
      setSources(list);
    } catch (err) {
      console.error('Failed to load sources:', err);
    }
  }, []);

  useEffect(() => {
    loadSources();
    load();
  }, [loadSources, load]);

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-surface-100">Playlists</h2>
        <p className="mt-1 text-sm text-surface-500">
          Manage your IPTV sources and sync settings
        </p>
      </div>

      {/* Sources list */}
      <SourceList sources={sources} onRefresh={loadSources} />

      {/* Add source */}
      <AddSourceForm onSourceAdded={loadSources} />

      {/* Auto-sync settings */}
      {loaded && (
        <section className="rounded-xl border border-surface-800 bg-surface-900 p-5">
          <h3 className="mb-4 text-sm font-semibold uppercase tracking-wider text-surface-500">
            Sync Options
          </h3>
          <div className="space-y-2">
            <div className="flex items-center justify-between rounded-lg border border-surface-800 bg-surface-900/60 px-4 py-3">
              <div>
                <p className="text-sm font-medium text-surface-200">
                  Auto-sync on startup
                </p>
                <p className="mt-0.5 text-xs text-surface-500">
                  Automatically refresh all sources when the app starts
                </p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={getBool('playlist_auto_sync_on_start')}
                onClick={() =>
                  setBool('playlist_auto_sync_on_start', !getBool('playlist_auto_sync_on_start'))
                }
                className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
                  getBool('playlist_auto_sync_on_start') ? 'bg-accent' : 'bg-surface-600'
                }`}
              >
                <span
                  className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${
                    getBool('playlist_auto_sync_on_start')
                      ? 'translate-x-[18px]'
                      : 'translate-x-[3px]'
                  }`}
                />
              </button>
            </div>

            <div className="flex items-center justify-between rounded-lg border border-surface-800 bg-surface-900/60 px-4 py-3">
              <div>
                <p className="text-sm font-medium text-surface-200">
                  Auto-sync interval
                </p>
                <p className="mt-0.5 text-xs text-surface-500">
                  How often to automatically refresh playlist data
                </p>
              </div>
              <select
                value={get('playlist_auto_sync_interval')}
                onChange={(e) => set('playlist_auto_sync_interval', e.target.value)}
                className="rounded-lg border border-surface-700 bg-surface-800 px-3 py-1.5 text-sm text-surface-200 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              >
                <option value="0">Manual only</option>
                <option value="6">Every 6 hours</option>
                <option value="12">Every 12 hours</option>
                <option value="24">Every 24 hours</option>
                <option value="48">Every 2 days</option>
                <option value="168">Weekly</option>
              </select>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}
