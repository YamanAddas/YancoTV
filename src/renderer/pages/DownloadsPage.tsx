import { useCallback, useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { EmptyState } from '../components/EmptyState';
import { usePlayerStore } from '../stores/player-store';
import type { Download, DownloadStatus } from '../../shared/types';

function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v < 10 && i > 0 ? 1 : 0)} ${units[i]}`;
}

function formatSpeed(bps: number): string {
  if (!bps || bps <= 0) return '';
  return `${formatBytes(bps)}/s`;
}

function formatEta(bytesRemaining: number, bps: number): string {
  if (!bps || bps <= 0 || bytesRemaining <= 0) return '';
  const s = Math.floor(bytesRemaining / bps);
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`;
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return `${h}h ${m}m`;
}

function percent(done: number, total?: number): number {
  if (!total || total <= 0) return 0;
  return Math.min(100, Math.floor((done / total) * 100));
}

const STATUS_COLOR: Record<DownloadStatus, string> = {
  queued: 'bg-surface-700/40 text-surface-400 border-surface-700/60',
  downloading: 'bg-sky-500/20 text-sky-300 border-sky-500/40',
  paused: 'bg-amber-500/15 text-amber-300 border-amber-500/30',
  completed: 'bg-emerald-500/15 text-emerald-300 border-emerald-500/30',
  failed: 'bg-rose-500/15 text-rose-300 border-rose-500/30',
  cancelled: 'bg-surface-700/40 text-surface-400 border-surface-700/60',
};

export function DownloadsPage() {
  const qc = useQueryClient();
  const [liveProgress, setLiveProgress] = useState<
    Record<string, { bytesDownloaded: number; bytesTotal?: number; bytesPerSecond: number }>
  >({});

  const { data: downloads = [], isLoading } = useQuery({
    queryKey: ['download', 'list'],
    queryFn: () => window.api.download.list(),
    staleTime: 10_000,
    refetchInterval: 15_000,
  });

  useEffect(() => {
    const offProgress = window.api.download.onProgress((p) => {
      setLiveProgress((prev) => ({
        ...prev,
        [p.id]: {
          bytesDownloaded: p.bytesDownloaded,
          bytesTotal: p.bytesTotal,
          bytesPerSecond: p.bytesPerSecond,
        },
      }));
    });
    const offStatus = window.api.download.onStatus(() => {
      qc.invalidateQueries({ queryKey: ['download', 'list'] });
    });
    return () => {
      offProgress();
      offStatus();
    };
  }, [qc]);

  const handlePause = useCallback(
    async (id: string) => {
      await window.api.download.pause(id);
      qc.invalidateQueries({ queryKey: ['download', 'list'] });
    },
    [qc],
  );

  const handleResume = useCallback(
    async (id: string) => {
      await window.api.download.resume(id);
      qc.invalidateQueries({ queryKey: ['download', 'list'] });
    },
    [qc],
  );

  const handleCancel = useCallback(
    async (id: string) => {
      await window.api.download.cancel(id);
      qc.invalidateQueries({ queryKey: ['download', 'list'] });
    },
    [qc],
  );

  const handleRemove = useCallback(
    async (id: string, deleteFile: boolean) => {
      await window.api.download.remove(id, deleteFile);
      qc.invalidateQueries({ queryKey: ['download', 'list'] });
    },
    [qc],
  );

  const handleOpenFolder = useCallback(() => {
    window.api.download.openFolder();
  }, []);

  const play = usePlayerStore((s) => s.play);
  const handlePlay = useCallback(
    (dl: Download) => {
      if (dl.status !== 'completed' || !dl.filePath) return;
      play(dl.filePath, dl.title, `download:${dl.id}`);
    },
    [play],
  );

  const sorted = useMemo(
    () => [...downloads].sort((a, b) => b.queuedAt - a.queuedAt),
    [downloads],
  );

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-2xl font-bold text-surface-100 text-glow-sm">Downloads</h2>
        <button
          onClick={handleOpenFolder}
          className="rounded-lg border border-accent/30 bg-accent/10 px-3 py-1.5 text-sm text-accent transition-colors hover:bg-accent/20"
        >
          Open Folder
        </button>
      </div>

      {!isLoading && sorted.length === 0 ? (
        <EmptyState
          icon="film"
          title="No downloads yet"
          message="Open a movie or episode and click Download to add it here."
        />
      ) : (
        <div className="space-y-2">
          {sorted.map((dl) => {
            const live = liveProgress[dl.id];
            const done = live && dl.status === 'downloading' ? live.bytesDownloaded : dl.bytesDownloaded;
            const total = live && dl.status === 'downloading' ? live.bytesTotal : dl.bytesTotal;
            const bps = live && dl.status === 'downloading' ? live.bytesPerSecond : 0;
            const pct = percent(done, total);
            const remaining = total ? total - done : 0;

            return (
              <div
                key={dl.id}
                className="rounded-xl border border-surface-700/40 bg-surface-800/30 p-4 transition-colors hover:bg-surface-800/50"
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-3">
                      {dl.status === 'completed' ? (
                        <button
                          onClick={() => handlePlay(dl)}
                          className="inline-flex min-w-0 items-center gap-2 text-left text-sm font-medium text-surface-100 hover:text-accent"
                          title="Play download"
                        >
                          <svg
                            className="h-3.5 w-3.5 flex-shrink-0 text-accent/80"
                            fill="currentColor"
                            viewBox="0 0 24 24"
                          >
                            <path d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.348a1.125 1.125 0 010 1.971l-11.54 6.347a1.125 1.125 0 01-1.667-.985V5.653z" />
                          </svg>
                          <span className="truncate">{dl.title}</span>
                        </button>
                      ) : (
                        <span className="truncate text-sm font-medium text-surface-100">
                          {dl.title}
                        </span>
                      )}
                      <span
                        className={`flex-shrink-0 rounded-full border px-2 py-0.5 text-xs ${STATUS_COLOR[dl.status as DownloadStatus]}`}
                      >
                        {dl.status}
                      </span>
                    </div>

                    {/* Progress bar for anything that has bytes */}
                    {(dl.status === 'downloading' ||
                      dl.status === 'paused' ||
                      dl.status === 'completed' ||
                      dl.status === 'failed') &&
                      total && total > 0 && (
                        <div className="mt-2.5">
                          <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-700/50">
                            <div
                              className={`h-full rounded-full transition-all duration-200 ${
                                dl.status === 'completed'
                                  ? 'bg-emerald-500/70'
                                  : dl.status === 'failed'
                                  ? 'bg-rose-500/70'
                                  : dl.status === 'paused'
                                  ? 'bg-amber-500/70'
                                  : 'bg-accent'
                              }`}
                              style={{ width: `${pct}%` }}
                            />
                          </div>
                        </div>
                      )}

                    <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-surface-400 tabular-nums">
                      {total ? (
                        <span>
                          {formatBytes(done)} / {formatBytes(total)} ({pct}%)
                        </span>
                      ) : (
                        <span>{formatBytes(done)}</span>
                      )}
                      {dl.status === 'downloading' && bps > 0 && (
                        <>
                          <span>{formatSpeed(bps)}</span>
                          {remaining > 0 && <span>ETA {formatEta(remaining, bps)}</span>}
                        </>
                      )}
                      {dl.error && (
                        <span className="text-rose-300" title={dl.error}>
                          {dl.error.slice(0, 120)}
                        </span>
                      )}
                    </div>
                  </div>

                  <div className="flex flex-shrink-0 items-center gap-2">
                    {dl.status === 'downloading' && (
                      <button
                        onClick={() => handlePause(dl.id)}
                        className="rounded-md border border-amber-500/30 bg-amber-500/10 px-2.5 py-1 text-xs text-amber-300 hover:bg-amber-500/20"
                      >
                        Pause
                      </button>
                    )}
                    {(dl.status === 'paused' || dl.status === 'failed') && (
                      <button
                        onClick={() => handleResume(dl.id)}
                        className="rounded-md border border-accent/30 bg-accent/10 px-2.5 py-1 text-xs text-accent hover:bg-accent/20"
                      >
                        Resume
                      </button>
                    )}
                    {(dl.status === 'queued' || dl.status === 'downloading') && (
                      <button
                        onClick={() => handleCancel(dl.id)}
                        className="rounded-md border border-rose-500/30 bg-rose-500/10 px-2.5 py-1 text-xs text-rose-300 hover:bg-rose-500/20"
                      >
                        Cancel
                      </button>
                    )}
                    {(dl.status === 'completed' ||
                      dl.status === 'failed' ||
                      dl.status === 'cancelled' ||
                      dl.status === 'paused') && (
                      <>
                        <button
                          onClick={() => handleRemove(dl.id, false)}
                          className="rounded-md border border-surface-700/60 bg-surface-800/40 px-2.5 py-1 text-xs text-surface-300 hover:bg-surface-700/60"
                          title="Remove from list (keep file)"
                        >
                          Remove
                        </button>
                        <button
                          onClick={() => handleRemove(dl.id, true)}
                          className="rounded-md border border-rose-500/30 bg-rose-500/10 px-2.5 py-1 text-xs text-rose-300 hover:bg-rose-500/20"
                          title="Delete file and remove from list"
                        >
                          Delete
                        </button>
                      </>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
