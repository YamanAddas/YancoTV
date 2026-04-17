import { useCallback, useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { EmptyState } from '../components/EmptyState';
import { usePlayerStore } from '../stores/player-store';
import type { Recording } from '../../shared/types/recording';

function formatDuration(seconds?: number): string {
  if (!seconds || seconds < 0) return '—';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let val = bytes;
  let i = 0;
  while (val >= 1024 && i < units.length - 1) {
    val /= 1024;
    i++;
  }
  return `${val.toFixed(val < 10 && i > 0 ? 1 : 0)} ${units[i]}`;
}

function formatStartedAt(ms: number): string {
  const d = new Date(ms);
  return d.toLocaleString();
}

const STATUS_COLOR: Record<Recording['status'], string> = {
  recording: 'bg-red-500/20 text-red-300 border-red-500/40',
  completed: 'bg-emerald-500/15 text-emerald-300 border-emerald-500/30',
  failed: 'bg-rose-500/15 text-rose-300 border-rose-500/30',
  cancelled: 'bg-surface-700/40 text-surface-400 border-surface-700/60',
};

export function RecordingsPage() {
  const qc = useQueryClient();
  const [livePairs, setLivePairs] = useState<
    Record<string, { durationSeconds: number; fileSizeBytes: number }>
  >({});

  const { data: ffmpegCheck } = useQuery({
    queryKey: ['recording', 'ffmpeg-check'],
    queryFn: () => window.api.recording.checkFfmpeg(),
    staleTime: 60_000,
  });

  const { data: recordings = [], isLoading } = useQuery({
    queryKey: ['recording', 'list'],
    queryFn: () => window.api.recording.list(),
    staleTime: 10_000,
    refetchInterval: 15_000,
  });

  useEffect(() => {
    const offProgress = window.api.recording.onProgress((p) => {
      setLivePairs((prev) => ({
        ...prev,
        [p.id]: { durationSeconds: p.durationSeconds, fileSizeBytes: p.fileSizeBytes },
      }));
    });
    const offStatus = window.api.recording.onStatus(() => {
      qc.invalidateQueries({ queryKey: ['recording', 'list'] });
    });
    return () => {
      offProgress();
      offStatus();
    };
  }, [qc]);

  const handleStop = useCallback(
    async (id: string) => {
      await window.api.recording.stop(id);
      qc.invalidateQueries({ queryKey: ['recording', 'list'] });
    },
    [qc],
  );

  const handleDelete = useCallback(
    async (id: string, deleteFile: boolean) => {
      await window.api.recording.remove(id, deleteFile);
      qc.invalidateQueries({ queryKey: ['recording', 'list'] });
    },
    [qc],
  );

  const handleOpenFolder = useCallback(() => {
    window.api.recording.openFolder();
  }, []);

  const play = usePlayerStore((s) => s.play);
  const handlePlay = useCallback(
    (rec: Recording) => {
      if (rec.status !== 'completed' || !rec.filePath) return;
      play(rec.filePath, rec.title, `recording:${rec.id}`);
    },
    [play],
  );

  const sorted = useMemo(
    () => [...recordings].sort((a, b) => b.startedAt - a.startedAt),
    [recordings],
  );

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-2xl font-bold text-surface-100 text-glow-sm">Recordings</h2>
        <button
          onClick={handleOpenFolder}
          className="rounded-lg border border-accent/30 bg-accent/10 px-3 py-1.5 text-sm text-accent transition-colors hover:bg-accent/20"
        >
          Open Folder
        </button>
      </div>

      {ffmpegCheck && !ffmpegCheck.available && (
        <div className="rounded-lg border border-amber-500/30 bg-amber-500/10 p-3 text-sm text-amber-200">
          ffmpeg is not installed or not on PATH. Install ffmpeg to enable recording. On
          Windows, drop <code>ffmpeg.exe</code> into <code>{'<AppDir>'}/ffmpeg/</code> or use
          <code> winget install ffmpeg</code>.
        </div>
      )}

      {!isLoading && sorted.length === 0 ? (
        <EmptyState
          icon="film"
          title="No recordings yet"
          message="Right-click a live channel and choose Record to start recording."
        />
      ) : (
        <div className="overflow-hidden rounded-xl border border-surface-700/40 bg-surface-800/30">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-surface-700/40 bg-surface-800/60 text-xs uppercase tracking-wide text-surface-400">
              <tr>
                <th className="px-4 py-3">Title</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Started</th>
                <th className="px-4 py-3">Duration</th>
                <th className="px-4 py-3">Size</th>
                <th className="px-4 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((r) => {
                const live = livePairs[r.id];
                const duration =
                  r.status === 'recording' && live
                    ? live.durationSeconds
                    : r.durationSeconds;
                const size =
                  r.status === 'recording' && live ? live.fileSizeBytes : r.fileSizeBytes;
                return (
                  <tr
                    key={r.id}
                    className="border-b border-surface-700/20 last:border-b-0 hover:bg-surface-800/40"
                  >
                    <td className="px-4 py-3 font-medium text-surface-100">
                      {r.status === 'completed' ? (
                        <button
                          onClick={() => handlePlay(r)}
                          className="inline-flex items-center gap-2 text-left hover:text-accent"
                          title="Play recording"
                        >
                          <svg
                            className="h-3.5 w-3.5 flex-shrink-0 text-accent/80"
                            fill="currentColor"
                            viewBox="0 0 24 24"
                          >
                            <path d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.348a1.125 1.125 0 010 1.971l-11.54 6.347a1.125 1.125 0 01-1.667-.985V5.653z" />
                          </svg>
                          <span className="truncate">{r.title}</span>
                        </button>
                      ) : (
                        r.title
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs ${STATUS_COLOR[r.status]}`}
                      >
                        {r.status === 'recording' && (
                          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-red-400" />
                        )}
                        {r.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-surface-400">
                      {formatStartedAt(r.startedAt)}
                    </td>
                    <td className="px-4 py-3 tabular-nums text-surface-300">
                      {formatDuration(duration)}
                    </td>
                    <td className="px-4 py-3 tabular-nums text-surface-300">
                      {formatBytes(size)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      {r.status === 'recording' ? (
                        <button
                          onClick={() => handleStop(r.id)}
                          className="rounded-md border border-rose-500/30 bg-rose-500/10 px-2.5 py-1 text-xs text-rose-300 hover:bg-rose-500/20"
                        >
                          Stop
                        </button>
                      ) : (
                        <div className="flex justify-end gap-2">
                          <button
                            onClick={() => handleDelete(r.id, false)}
                            className="rounded-md border border-surface-700/60 bg-surface-800/40 px-2.5 py-1 text-xs text-surface-300 hover:bg-surface-700/60"
                            title="Remove from list (keep file)"
                          >
                            Remove
                          </button>
                          <button
                            onClick={() => handleDelete(r.id, true)}
                            className="rounded-md border border-rose-500/30 bg-rose-500/10 px-2.5 py-1 text-xs text-rose-300 hover:bg-rose-500/20"
                            title="Delete file and remove from list"
                          >
                            Delete
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
