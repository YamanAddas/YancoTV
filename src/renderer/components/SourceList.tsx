import { useState } from 'react';

interface Source {
  id: string;
  name: string;
  type: string;
  url?: string;
  filePath?: string;
  lastSynced?: number;
  isActive: boolean;
}

interface SourceListProps {
  sources: Source[];
  onRefresh: () => void;
}

const typeLabels: Record<string, string> = {
  m3u_url: 'M3U URL',
  m3u_file: 'M3U File',
  xtream: 'Xtream',
};

export function SourceList({ sources, onRefresh }: SourceListProps) {
  if (sources.length === 0) return null;

  return (
    <section className="rounded-xl border border-surface-800 bg-surface-900 p-5">
      <h3 className="mb-4 text-lg font-semibold text-surface-200">Sources</h3>
      <div className="space-y-2">
        {sources.map((source) => (
          <SourceItem key={source.id} source={source} onRefresh={onRefresh} />
        ))}
      </div>
    </section>
  );
}

function SourceItem({ source, onRefresh }: { source: Source; onRefresh: () => void }) {
  const [syncing, setSyncing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [syncResult, setSyncResult] = useState<string | null>(null);

  const handleSync = async () => {
    setSyncing(true);
    setSyncResult(null);
    try {
      const result = await window.api.sources.sync(source.id);
      setSyncResult(result.ok ? `${result.count} entries synced` : result.error);
      onRefresh();
    } catch (err) {
      setSyncResult(String(err));
    } finally {
      setSyncing(false);
    }
  };

  const handleDelete = async () => {
    setDeleting(true);
    try {
      await window.api.sources.remove(source.id);
      onRefresh();
    } catch (err) {
      console.error('Delete failed:', err);
    } finally {
      setDeleting(false);
    }
  };

  const lastSynced = source.lastSynced
    ? new Date(source.lastSynced).toLocaleString()
    : 'Never';

  return (
    <div className="rounded-lg border border-surface-700 bg-surface-800 p-3">
      <div className="flex items-center justify-between">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="truncate font-medium text-surface-200">{source.name}</p>
            <span className="shrink-0 rounded bg-surface-700 px-1.5 py-0.5 text-xs text-surface-400">
              {typeLabels[source.type] ?? source.type}
            </span>
          </div>
          <p className="mt-0.5 truncate text-xs text-surface-500">
            {source.url || source.filePath || 'No path'}
          </p>
          <p className="mt-0.5 text-xs text-surface-500">
            Last synced: {lastSynced}
          </p>
        </div>

        <div className="ml-3 flex shrink-0 gap-1.5">
          <button
            onClick={handleSync}
            disabled={syncing}
            title="Sync"
            className="rounded-md bg-surface-700 p-1.5 text-surface-400 hover:bg-surface-600 hover:text-surface-200 disabled:opacity-50"
          >
            <svg
              className={`h-4 w-4 ${syncing ? 'animate-spin' : ''}`}
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
          </button>
          <button
            onClick={handleDelete}
            disabled={deleting}
            title="Delete"
            className="rounded-md bg-surface-700 p-1.5 text-surface-400 hover:bg-red-500/20 hover:text-red-400 disabled:opacity-50"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
              />
            </svg>
          </button>
        </div>
      </div>

      {syncResult && (
        <p className="mt-2 text-xs text-surface-400">{syncResult}</p>
      )}
    </div>
  );
}
