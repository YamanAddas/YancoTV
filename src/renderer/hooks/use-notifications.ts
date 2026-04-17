import { useEffect } from 'react';
import { useToastStore } from '../stores/toast-store';

type DownloadListItem = { id: string; title: string };
type RecordingListItem = { id: string; title: string };
type SourceListItem = { id: string; name: string };

export function useNotifications(): void {
  const push = useToastStore((s) => s.push);

  // Sync done → one toast per source when its sync completes.
  useEffect(() => {
    const unsub = window.api?.sources?.onSyncProgress?.(async (sourceId, prog) => {
      if (prog?.phase !== 'done') return;
      let name = 'source';
      try {
        const sources = (await window.api.sources.getAll()) as SourceListItem[];
        name = sources.find((s) => s.id === sourceId)?.name ?? name;
      } catch {
        // Best-effort — fall back to generic label.
      }
      push({ kind: 'success', message: `Sync finished — ${name}` });
    });
    return unsub;
  }, [push]);

  // Download status → toast on terminal transitions only.
  useEffect(() => {
    const unsub = window.api?.download?.onStatus?.(async (p) => {
      if (p.status !== 'completed' && p.status !== 'failed') return;
      let title = 'download';
      try {
        const list = (await window.api.download.list()) as DownloadListItem[];
        title = list.find((d) => d.id === p.id)?.title ?? title;
      } catch {
        // Ignore — use fallback label.
      }
      if (p.status === 'completed') {
        push({
          kind: 'success',
          message: `Download complete — ${title}`,
          action: { label: 'Open', href: '/downloads' },
        });
      } else {
        push({ kind: 'error', message: `Download failed — ${title}${p.error ? `: ${p.error}` : ''}` });
      }
    });
    return unsub;
  }, [push]);

  // Recording status → toast on terminal transitions only.
  useEffect(() => {
    const unsub = window.api?.recording?.onStatus?.(async (p) => {
      if (p.status !== 'completed' && p.status !== 'failed' && p.status !== 'cancelled') return;
      let title = 'recording';
      try {
        const list = (await window.api.recording.list()) as RecordingListItem[];
        title = list.find((r) => r.id === p.id)?.title ?? title;
      } catch {
        // Ignore — use fallback label.
      }
      if (p.status === 'completed') {
        push({
          kind: 'success',
          message: `Recording saved — ${title}`,
          action: { label: 'Open', href: '/recordings' },
        });
      } else if (p.status === 'failed') {
        push({ kind: 'error', message: `Recording failed — ${title}` });
      }
    });
    return unsub;
  }, [push]);
}
