import { useEffect, useRef } from 'react';
import { usePlayerStore, type ZapTarget } from '../stores/player-store';

const ZAP_COMMIT_DELAY_MS = 2000;

type LiveChannel = {
  id: string;
  tvgId?: string;
  streamUrl: string;
  title: string;
  cleanTitle?: string;
  logoUrl?: string;
};

/**
 * Channel zapping (Sprint 19.4, extended to mini mode in 0.3.7).
 *
 * Flow:
 * 1. User presses PageUp/PageDown while watching a LIVE channel (theater or
 *    docked mini).
 * 2. We show a floating preview (zapTarget in the player store) with the next
 *    channel's name + logo, without actually switching streams. Rapid presses
 *    scroll through the list without tearing down mpv.
 * 3. 2s after the last press we commit — call play() on the target channel.
 *
 * Active in both 'theater' and 'mini' modes and only for live content;
 * movies/series ignore the keys so seeking-by-chapter stays unambiguous in
 * the future. Idle (no stream) ignores them too.
 */
export function useChannelZap(): void {
  const channelsRef = useRef<LiveChannel[] | null>(null);
  const loadingRef = useRef<Promise<LiveChannel[]> | null>(null);
  const commitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    async function getChannels(): Promise<LiveChannel[]> {
      if (channelsRef.current) return channelsRef.current;
      if (loadingRef.current) return loadingRef.current;
      if (!window.api) return [];
      loadingRef.current = window.api.content.getLive().then((list) => {
        const channels = (list as LiveChannel[]) ?? [];
        channelsRef.current = channels;
        return channels;
      });
      try {
        return await loadingRef.current;
      } finally {
        loadingRef.current = null;
      }
    }

    function buildTarget(channels: LiveChannel[], index: number): ZapTarget | null {
      const ch = channels[index];
      if (!ch) return null;
      return {
        contentId: ch.id,
        title: ch.cleanTitle || ch.title,
        logoUrl: ch.logoUrl,
        streamUrl: ch.streamUrl,
        index,
        total: channels.length,
      };
    }

    function scheduleCommit(): void {
      if (commitTimerRef.current) clearTimeout(commitTimerRef.current);
      commitTimerRef.current = setTimeout(() => {
        commitTimerRef.current = null;
        const target = usePlayerStore.getState().zapTarget;
        if (!target) return;
        // Don't re-tune to the same channel
        if (target.contentId === usePlayerStore.getState().currentContentId) {
          usePlayerStore.setState({ zapTarget: null });
          return;
        }
        usePlayerStore
          .getState()
          .play(target.streamUrl, target.title, target.contentId, undefined, 'live');
      }, ZAP_COMMIT_DELAY_MS);
    }

    async function handleKeyDown(e: KeyboardEvent): Promise<void> {
      if (e.key !== 'PageUp' && e.key !== 'PageDown') return;

      // Don't intercept while the user is typing.
      const tag = (e.target as HTMLElement | null)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

      const state = usePlayerStore.getState();
      // Mini + theater both allow zap. Idle ignores. The mode check is OR'd
      // (rather than `!== 'idle'`) so future modes don't auto-opt-in.
      if (state.mode !== 'theater' && state.mode !== 'mini') return;
      if (state.currentContentType !== 'live') return;

      e.preventDefault();

      const channels = await getChannels();
      if (channels.length === 0) return;

      const currentTarget = usePlayerStore.getState().zapTarget;
      let currentIdx = currentTarget
        ? currentTarget.index
        : channels.findIndex((c) => c.id === state.currentContentId);
      if (currentIdx < 0) currentIdx = 0;

      const delta = e.key === 'PageUp' ? -1 : 1;
      const nextIdx = (currentIdx + delta + channels.length) % channels.length;

      const target = buildTarget(channels, nextIdx);
      if (!target) return;

      usePlayerStore.setState({ zapTarget: target });
      scheduleCommit();
    }

    window.addEventListener('keydown', handleKeyDown);

    // If the channel list is changed (e.g. after a sync), invalidate the cache
    // so the next PageUp/Down picks up the new list.
    const unsubSources = window.api?.sources?.onSyncProgress?.((_id, prog) => {
      if (prog?.phase === 'done') channelsRef.current = null;
    });

    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      if (commitTimerRef.current) clearTimeout(commitTimerRef.current);
      commitTimerRef.current = null;
      unsubSources?.();
    };
  }, []);
}
