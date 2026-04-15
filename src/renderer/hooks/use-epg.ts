import { useQuery } from '@tanstack/react-query';
import type {
  NowNextMap,
  EpgGuideChannel,
  EpgProgramme,
  EpgRefreshResult,
} from '../../shared/types/epg';

/**
 * Fetch now/next EPG data for a batch of tvg IDs.
 * Used by the Live TV grid to show current + next programme on each channel card.
 */
export function useNowNextBatch(tvgIds: string[]) {
  return useQuery<NowNextMap>({
    queryKey: ['epg', 'nowNextBatch', tvgIds],
    queryFn: () => window.api.epg.getNowNextBatch(tvgIds),
    enabled: tvgIds.length > 0,
    staleTime: 60_000, // 1 minute — EPG changes slowly
    refetchInterval: 60_000, // Auto-refresh every minute
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
 * Trigger an EPG refresh. Returns a function that can be called.
 */
export async function triggerEpgRefresh(): Promise<EpgRefreshResult> {
  return window.api.epg.refresh();
}
