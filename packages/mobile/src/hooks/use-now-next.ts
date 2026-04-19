import { useMemo } from 'react';

export interface NowNext {
  now?: { title: string; startMs?: number; endMs?: number };
  next?: { title: string; startMs?: number; endMs?: number };
}

/**
 * Stub hook for now/next EPG lookup. Returns an empty map today so LiveTvScreen
 * can wire the call site without a real EPG table behind it. M6 replaces the
 * body with an op-sqlite query against `epg_programmes` keyed by tvgId and
 * clamped to a ±3h window around `Date.now()`. The returned shape is already
 * the one M6 will produce, so consumers (grid overlays, detail screens) don't
 * need to change.
 */
export function useNowNext(tvgIds: readonly string[]): Map<string, NowNext> {
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => new Map<string, NowNext>(), [tvgIds.join('|')]);
}
