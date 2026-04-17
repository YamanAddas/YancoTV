import { useEffect } from 'react';
import { useToastStore } from '../stores/toast-store';
import { useSettingsStore } from '../stores/settings-store';
import { usePlayerStore } from '../stores/player-store';

type DownloadListItem = { id: string; title: string };
type RecordingListItem = { id: string; title: string };
type SourceListItem = { id: string; name: string };
type LiveChannel = { id: string; tvgId?: string; streamUrl: string; cleanTitle?: string; title: string };

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

  // Programme reminders → toast when the reminder fires. If the user has
  // enabled auto-tune we also resolve the channel and start playback;
  // otherwise the toast carries a Watch action so it's still one click away.
  useEffect(() => {
    const unsub = window.api?.reminders?.onFired?.(async (reminder) => {
      const autoTune = useSettingsStore.getState().getBool('ui_reminder_auto_tune');
      let channel: LiveChannel | undefined;
      try {
        const channels = (await window.api.content.getLive()) as LiveChannel[];
        channel = channels.find((c) => c.tvgId === reminder.channelTvgId);
      } catch {
        // best-effort — fall through to plain toast
      }

      if (autoTune && channel?.streamUrl) {
        usePlayerStore
          .getState()
          .play(
            channel.streamUrl,
            reminder.title,
            channel.id,
            undefined,
            'live',
          );
        push({
          kind: 'info',
          message: `Tuning in — ${reminder.title}`,
        });
        return;
      }

      if (channel?.streamUrl) {
        push({
          kind: 'info',
          message: `Starting now — ${reminder.title}`,
          action: {
            label: 'Watch',
            onClick: () => {
              usePlayerStore
                .getState()
                .play(channel!.streamUrl, reminder.title, channel!.id, undefined, 'live');
            },
          },
        });
      } else {
        push({ kind: 'info', message: `Starting now — ${reminder.title}` });
      }
    });
    return unsub;
  }, [push]);
}
