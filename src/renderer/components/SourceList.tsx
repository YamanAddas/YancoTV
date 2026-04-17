import { useState, useEffect, useCallback, useRef } from 'react';

interface Source {
  id: string;
  name: string;
  type: string;
  url?: string;
  filePath?: string;
  userAgent?: string;
  lastSynced?: number;
  isActive: boolean;
  priority: number;
  channelCount: number;
  lastSyncError?: string;
  autoSyncInterval: number;
}

interface SyncProgress {
  phase: string;
  current: number;
  total: number;
}

interface SourceListProps {
  sources: Source[];
  onRefresh: () => void;
}

const typeLabels: Record<string, string> = {
  m3u_url: 'M3U URL',
  m3u_file: 'M3U File',
  xtream: 'Xtream',
  stalker: 'Stalker',
};

export function SourceList({ sources, onRefresh }: SourceListProps) {
  const [orderedSources, setOrderedSources] = useState(sources);
  const dragItem = useRef<number | null>(null);
  const dragOverItem = useRef<number | null>(null);

  useEffect(() => {
    setOrderedSources(sources);
  }, [sources]);

  if (sources.length === 0) return null;

  const handleDragStart = (index: number) => {
    dragItem.current = index;
  };

  const handleDragEnter = (index: number) => {
    dragOverItem.current = index;
  };

  const handleDragEnd = async () => {
    if (dragItem.current === null || dragOverItem.current === null) return;
    if (dragItem.current === dragOverItem.current) return;

    const reordered = [...orderedSources];
    const [draggedItem] = reordered.splice(dragItem.current, 1);
    reordered.splice(dragOverItem.current, 0, draggedItem);

    setOrderedSources(reordered);
    dragItem.current = null;
    dragOverItem.current = null;

    // Persist the new order
    try {
      await window.api.sources.reorder(reordered.map((s) => s.id));
      onRefresh();
    } catch (err) {
      console.error('Reorder failed:', err);
    }
  };

  return (
    <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
      <h3 className="mb-4 text-lg font-semibold text-surface-200">Sources</h3>
      <div className="space-y-2">
        {orderedSources.map((source, index) => (
          <SourceItem
            key={source.id}
            source={source}
            onRefresh={onRefresh}
            index={index}
            onDragStart={handleDragStart}
            onDragEnter={handleDragEnter}
            onDragEnd={handleDragEnd}
          />
        ))}
      </div>
    </section>
  );
}

function SourceItem({
  source,
  onRefresh,
  index,
  onDragStart,
  onDragEnter,
  onDragEnd,
}: {
  source: Source;
  onRefresh: () => void;
  index: number;
  onDragStart: (index: number) => void;
  onDragEnter: (index: number) => void;
  onDragEnd: () => void;
}) {
  const [syncing, setSyncing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [editing, setEditing] = useState(false);
  const [syncResult, setSyncResult] = useState<string | null>(null);
  const [progress, setProgress] = useState<SyncProgress | null>(null);

  // Edit form state
  const [editName, setEditName] = useState(source.name);
  const [editUrl, setEditUrl] = useState(source.url ?? '');
  const [editEpgUrl, setEditEpgUrl] = useState('');
  const [editUserAgent, setEditUserAgent] = useState(source.userAgent ?? '');
  const [editError, setEditError] = useState('');
  const [saving, setSaving] = useState(false);

  // Listen for sync progress events
  useEffect(() => {
    const unsubscribe = window.api.sources.onSyncProgress(
      (sourceId: string, prog: SyncProgress) => {
        if (sourceId === source.id) {
          setProgress(prog);
        }
      },
    );
    return unsubscribe;
  }, [source.id]);

  const handleSync = useCallback(async () => {
    setSyncing(true);
    setSyncResult(null);
    setProgress(null);
    try {
      const result = await window.api.sources.sync(source.id);
      setSyncResult(result.ok ? `${result.count} entries synced` : result.error);
      onRefresh();
    } catch (err) {
      setSyncResult(String(err));
    } finally {
      setSyncing(false);
      setProgress(null);
    }
  }, [source.id, onRefresh]);

  const handleDelete = useCallback(async () => {
    setDeleting(true);
    try {
      await window.api.sources.remove(source.id);
      onRefresh();
    } catch (err) {
      console.error('Delete failed:', err);
    } finally {
      setDeleting(false);
    }
  }, [source.id, onRefresh]);

  const handleEdit = () => {
    setEditName(source.name);
    setEditUrl(source.url ?? '');
    setEditEpgUrl('');
    setEditUserAgent(source.userAgent ?? '');
    setEditError('');
    setEditing(true);
  };

  const handleSaveEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setEditError('');
    try {
      const input: Record<string, string | undefined> = { id: source.id };
      if (editName !== source.name) input.name = editName;
      if (editUrl !== (source.url ?? '')) input.url = editUrl;
      if (editEpgUrl.trim()) input.epgUrl = editEpgUrl.trim();
      if (editUserAgent !== (source.userAgent ?? '')) {
        input.userAgent = editUserAgent;
      }

      const result = await window.api.sources.update(input);
      if (result.ok) {
        setEditing(false);
        onRefresh();
      } else {
        setEditError(result.error || 'Failed to update');
      }
    } catch (err) {
      setEditError(String(err));
    } finally {
      setSaving(false);
    }
  };

  const lastSynced = source.lastSynced
    ? new Date(source.lastSynced).toLocaleString()
    : 'Never';

  const progressPercent =
    progress && progress.total > 0
      ? Math.round((progress.current / progress.total) * 100)
      : 0;

  const phaseLabel: Record<string, string> = {
    deleting: 'Clearing old data...',
    inserting: 'Importing entries...',
    indexing: 'Building search index...',
    done: 'Done!',
  };

  // Health indicator color
  const healthColor = source.lastSyncError
    ? 'bg-red-400'
    : source.lastSynced && Date.now() - source.lastSynced < 24 * 3600_000
      ? 'bg-green-400'
      : source.lastSynced
        ? 'bg-yellow-400'
        : 'bg-surface-500';

  return (
    <div
      className="rounded-lg border border-surface-700/50 bg-surface-800/40 p-3"
      draggable
      onDragStart={() => onDragStart(index)}
      onDragEnter={() => onDragEnter(index)}
      onDragEnd={onDragEnd}
      onDragOver={(e) => e.preventDefault()}
    >
      {editing ? (
        <form onSubmit={handleSaveEdit} className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium text-surface-300">Edit Source</span>
            <button
              type="button"
              onClick={() => setEditing(false)}
              className="text-surface-400 hover:text-surface-200"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
          <input
            type="text"
            value={editName}
            onChange={(e) => setEditName(e.target.value)}
            placeholder="Name"
            className="w-full rounded-md border border-surface-700/50 bg-surface-800/60 px-2.5 py-1.5 text-sm text-surface-200 placeholder-surface-500 focus:border-accent/50 focus:outline-none"
          />
          <input
            type="text"
            value={editUrl}
            onChange={(e) => setEditUrl(e.target.value)}
            placeholder="URL"
            className="w-full rounded-md border border-surface-700/50 bg-surface-800/60 px-2.5 py-1.5 text-sm text-surface-200 placeholder-surface-500 focus:border-accent/50 focus:outline-none"
          />
          <input
            type="text"
            value={editEpgUrl}
            onChange={(e) => setEditEpgUrl(e.target.value)}
            placeholder="EPG URL (leave empty to keep current)"
            className="w-full rounded-md border border-surface-700/50 bg-surface-800/60 px-2.5 py-1.5 text-sm text-surface-200 placeholder-surface-500 focus:border-accent/50 focus:outline-none"
          />
          <input
            type="text"
            value={editUserAgent}
            onChange={(e) => setEditUserAgent(e.target.value)}
            placeholder="Custom User-Agent (optional — overrides global)"
            className="w-full rounded-md border border-surface-700/50 bg-surface-800/60 px-2.5 py-1.5 text-sm text-surface-200 placeholder-surface-500 focus:border-accent/50 focus:outline-none"
          />
          {editError && (
            <p className="text-xs text-red-400">{editError}</p>
          )}
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={saving}
              className="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-white hover:bg-accent-hover disabled:opacity-50"
            >
              {saving ? 'Saving...' : 'Save'}
            </button>
            <button
              type="button"
              onClick={() => setEditing(false)}
              className="rounded-md bg-surface-700 px-3 py-1.5 text-xs font-medium text-surface-300 hover:bg-surface-600"
            >
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <>
          <div className="flex items-center justify-between">
            <div className="flex min-w-0 flex-1 items-start gap-2">
              {/* Drag handle */}
              <div className="mt-1 flex cursor-grab flex-col gap-0.5 text-surface-500 active:cursor-grabbing">
                <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 24 24">
                  <circle cx="9" cy="6" r="1.5" />
                  <circle cx="15" cy="6" r="1.5" />
                  <circle cx="9" cy="12" r="1.5" />
                  <circle cx="15" cy="12" r="1.5" />
                  <circle cx="9" cy="18" r="1.5" />
                  <circle cx="15" cy="18" r="1.5" />
                </svg>
              </div>

              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  {/* Health indicator dot */}
                  <span className={`h-2 w-2 shrink-0 rounded-full ${healthColor}`} title={source.lastSyncError ?? 'OK'} />
                  <p className="truncate font-medium text-surface-200">{source.name}</p>
                  <span className="shrink-0 rounded bg-surface-700 px-1.5 py-0.5 text-xs text-surface-400">
                    {typeLabels[source.type] ?? source.type}
                  </span>
                  {source.channelCount > 0 && (
                    <span className="shrink-0 text-xs text-surface-500">
                      {source.channelCount.toLocaleString()} entries
                    </span>
                  )}
                </div>
                <p className="mt-0.5 truncate text-xs text-surface-500">
                  {source.url || source.filePath || 'No path'}
                </p>
                <p className="mt-0.5 text-xs text-surface-500">
                  Last synced: {lastSynced}
                </p>
                {source.lastSyncError && (
                  <p className="mt-0.5 truncate text-xs text-red-400" title={source.lastSyncError}>
                    Error: {source.lastSyncError}
                  </p>
                )}
              </div>
            </div>

            <div className="ml-3 flex shrink-0 gap-1.5">
              {/* Edit button */}
              <button
                onClick={handleEdit}
                disabled={syncing}
                title="Edit"
                className="rounded-md bg-surface-700 p-1.5 text-surface-400 hover:bg-surface-600 hover:text-surface-200 disabled:opacity-50"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                </svg>
              </button>
              {/* Sync button */}
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
              {/* Delete button */}
              <button
                onClick={handleDelete}
                disabled={deleting || syncing}
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

          {/* Progress bar during sync */}
          {syncing && progress && progress.phase !== 'done' && (
            <div className="mt-2">
              <div className="mb-1 flex items-center justify-between text-xs text-surface-400">
                <span>{phaseLabel[progress.phase] ?? progress.phase}</span>
                <span>
                  {progress.phase === 'inserting'
                    ? `${progress.current.toLocaleString()} / ${progress.total.toLocaleString()}`
                    : `${progressPercent}%`}
                </span>
              </div>
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-700">
                <div
                  className="h-full rounded-full bg-accent shadow-glow-sm transition-all duration-300 ease-out"
                  style={{
                    width: `${progress.phase === 'deleting' ? 10 : progress.phase === 'indexing' ? 95 : progressPercent}%`,
                  }}
                />
              </div>
            </div>
          )}

          {/* Sync result message */}
          {syncResult && !syncing && (
            <p className="mt-2 text-xs text-surface-400">{syncResult}</p>
          )}
        </>
      )}
    </div>
  );
}
