import { useEffect, useMemo, useRef, useState } from 'react';
import {
  XtreamClient,
  type ContentItem,
  type ContentMetadata,
  type EpisodeInfo,
} from '@yancotv/core';
import { fetchHttpClient } from '../http/fetch-http-client';
import * as contentDb from '../db/content-store';
import { useSourcesStore, type MobileSource } from '../stores/sources-store';

interface DetailState {
  loading: boolean;
  error: string | null;
  metadata: ContentMetadata;
  episodes: EpisodeInfo[];
}

function parseMetadata(item: ContentItem | undefined | null): ContentMetadata {
  if (!item?.metadataJson) return {};
  try {
    return JSON.parse(item.metadataJson) as ContentMetadata;
  } catch {
    return {};
  }
}

/**
 * Lazily hydrate a content item with full metadata (plot/cast/subtitles and
 * — for series — the episode tree). Cached in the item's `metadataJson` so
 * subsequent opens are instant. The fetch runs only for xtream movies/series
 * because that's the only provider with a real detail endpoint.
 *
 * Post-M4R the item is fetched directly from SQLite via `getContentById`
 * instead of scanning a full channels array in memory (rule 4 — Zustand
 * never caches bulk content).
 */
export function useContentDetail(contentId: string | undefined): DetailState {
  const [item, setItem] = useState<ContentItem | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Bumped after a successful enrich so the next effect re-reads fresh
  // metadata from SQLite rather than trusting a stale local copy.
  const [revision, setRevision] = useState(0);

  const source = useSourcesStore((s) =>
    item ? s.sources.find((src) => src.id === item.sourceId) : undefined,
  );
  const enrichContent = useSourcesStore((s) => s.enrichContent);

  const inflightRef = useRef<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    if (!contentId) {
      setItem(null);
      return;
    }
    contentDb
      .getContentById(contentId)
      .then((row) => {
        if (cancelled) return;
        setItem(row);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [contentId, revision]);

  const metadata = useMemo(() => parseMetadata(item), [item]);
  const episodes = metadata.episodes ?? [];

  useEffect(() => {
    if (!item || !source) return;
    if (item.type === 'live') return;
    if (source.type !== 'xtream') return;
    if (metadata.detailFetchedAt) return;
    if (inflightRef.current === item.id) return;

    inflightRef.current = item.id;
    setLoading(true);
    setError(null);

    fetchDetail(item, source, metadata)
      .then((patch) => {
        if (patch) {
          enrichContent(item.id, { ...patch, detailFetchedAt: Date.now() });
          setRevision((r) => r + 1);
        }
      })
      .catch((err: unknown) => {
        const msg = err instanceof Error ? err.message : String(err);
        setError(msg);
      })
      .finally(() => {
        inflightRef.current = null;
        setLoading(false);
      });
  }, [item, source, metadata, enrichContent]);

  return { loading, error, metadata, episodes };
}

async function fetchDetail(
  item: ContentItem,
  source: MobileSource,
  current: ContentMetadata,
): Promise<Partial<ContentMetadata> | null> {
  if (source.type !== 'xtream') return null;
  const client = new XtreamClient(source.url, source.username, source.password, {
    http: fetchHttpClient,
  });

  if (item.type === 'movie') {
    const streamId = current.streamId ?? extractNumericSuffix(item.id);
    if (!streamId) return null;
    const res = await client.getVodInfo(streamId);
    if (!res.ok) throw res.error;
    const d = res.value;
    return {
      plot: d.plot || undefined,
      cast: d.cast || undefined,
      director: d.director || undefined,
      genre: d.genre || undefined,
      releaseDate: d.releaseDate || undefined,
      rating: d.rating || undefined,
      duration: d.duration || undefined,
      tagline: d.tagline || undefined,
      youtubeTrailer: d.youtubeTrailer || undefined,
      backdropUrl: d.backdropUrl || undefined,
      subtitles: d.subtitles.length ? d.subtitles : undefined,
      tmdbId: d.tmdbId ?? undefined,
    };
  }

  if (item.type === 'series') {
    const seriesId = current.seriesId ?? extractNumericSuffix(item.id);
    if (!seriesId) return null;
    const res = await client.getSeriesInfo(seriesId);
    if (!res.ok) throw res.error;
    const d = res.value;

    const episodes: EpisodeInfo[] = [];
    for (const [seasonKey, eps] of Object.entries(d.episodes)) {
      const seasonNumber = Number(seasonKey) || 0;
      for (const e of eps) {
        const epId = String(e.id);
        const ext = e.containerExtension || 'mp4';
        // Series episode URL: <base>/series/<user>/<pass>/<episode_id>.<ext>
        const streamUrl = client.buildStreamUrl(Number(epId), 'series', ext);
        episodes.push({
          id: `${item.id}:ep:${epId}`,
          seasonNumber: e.info.season ?? seasonNumber,
          episodeNumber: e.episodeNum,
          title: e.title || `Episode ${e.episodeNum}`,
          streamUrl,
          duration: e.info.duration,
        });
      }
    }

    return {
      plot: d.info.plot || current.plot,
      cast: d.info.cast || current.cast,
      director: d.info.director || current.director,
      genre: d.info.genre || current.genre,
      releaseDate: d.info.releaseDate || current.releaseDate,
      rating: d.info.rating || current.rating,
      episodes,
    };
  }

  return null;
}

function extractNumericSuffix(id: string): number {
  const m = id.match(/(\d+)$/);
  return m ? Number(m[1]) || 0 : 0;
}
