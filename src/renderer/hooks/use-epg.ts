import { useEffect, useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  NowNextMap,
  EpgGuideChannel,
  EpgProgramme,
  EpgRefreshResult,
} from '../../shared/types/epg';

/**
 * Fetch now/next EPG data for a batch of tvg IDs.
 * Used by the Live TV grid to show current + next programme on each channel card.
 *
 * - staleTime 2 min: programmes rarely change more often than that
 * - No refetchInterval: the main-process push event (`epg:refreshProgress`)
 *   invalidates the cache when a full EPG refresh completes, so we don't need
 *   a background polling loop burning IPC bandwidth every 60 s.
 * - refetchOnWindowFocus: catches the case where the user leaves and returns.
 */
export function useNowNextBatch(tvgIds: string[]) {
  return useQuery<NowNextMap>({
    queryKey: ['epg', 'nowNextBatch', tvgIds],
    queryFn: () => window.api.epg.getNowNextBatch(tvgIds),
    enabled: tvgIds.length > 0,
    staleTime: 2 * 60_000, // 2 minutes
    refetchOnWindowFocus: true,
  });
}

/**
 * Fetch guide data (channels + programmes) for a time range.
 * Used by the EPG Grid page.
 */
export function useGuideData(
  startTime: number,
  endTime: number,
  sourceId?: string,
) {
  return useQuery<EpgGuideChannel[]>({
    queryKey: ['epg', 'guide', startTime, endTime, sourceId],
    queryFn: () => window.api.epg.getGuide(startTime, endTime, sourceId),
    enabled: startTime > 0 && endTime > startTime,
    staleTime: 5 * 60_000, // 5 minutes
  });
}

/**
 * Fetch programmes for a single channel in a time range.
 */
export function useChannelProgrammes(
  tvgId: string | undefined,
  startTime: number,
  endTime: number,
) {
  return useQuery<EpgProgramme[]>({
    queryKey: ['epg', 'channel', tvgId, startTime, endTime],
    queryFn: () => window.api.epg.getForChannel(tvgId!, startTime, endTime),
    enabled: !!tvgId && startTime > 0 && endTime > startTime,
    staleTime: 5 * 60_000,
  });
}

/**
 * Fetch EPG statistics (programme count, channel count, last refresh).
 */
export function useEpgStats() {
  return useQuery({
    queryKey: ['epg', 'stats'],
    queryFn: () => window.api.epg.getStats(),
    staleTime: 30_000,
  });
}

/**
 * Fetch EPG settings (global URL, refresh interval, last refreshed).
 */
export function useEpgSettings() {
  return useQuery({
    queryKey: ['epg', 'settings'],
    queryFn: () => window.api.epg.getSettings(),
    staleTime: 30_000,
  });
}

/**
 * Trigger an EPG refresh.
 */
export async function triggerEpgRefresh(): Promise<EpgRefreshResult> {
  return window.api.epg.refresh();
}

/**
 * Listen for EPG refresh progress events pushed from the main process.
 * Calls `onComplete` when a refresh finishes successfully so components
 * can invalidate their caches without polling.
 */
export function useEpgRefreshProgress(onComplete: () => void) {
  const stableOnComplete = useCallback(onComplete, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!window.api?.epg?.onRefreshProgress) return;

    return window.api.epg.onRefreshProgress((progress) => {
      if (progress.phase === 'complete') {
        stableOnComplete();
      }
    });
  }, [stableOnComplete]);
}

/**
 * Convenience hook: subscribes to EPG refresh events and automatically
 * invalidates all EPG queries when a refresh completes.
 */
export function useEpgAutoInvalidate() {
  const queryClient = useQueryClient();

  useEpgRefreshProgress(
    useCallback(() => {
      queryClient.invalidateQueries({ queryKey: ['epg'] });
    }, [queryClient]),
  );
}
