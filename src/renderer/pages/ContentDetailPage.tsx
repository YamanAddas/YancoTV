import { useEffect, useState, useMemo, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { motion } from 'motion/react';
import { DetailHero } from '../components/DetailHero';
import { EpisodesTab } from '../components/EpisodesTab';
import { InfoTab } from '../components/InfoTab';
import { RelatedTab } from '../components/RelatedTab';
import { usePlayerStore } from '../stores/player-store';
import { useFavoritesStore } from '../stores/favorites-store';
import { useToastStore } from '../stores/toast-store';
import type { ContentDetail, ContentItem, ContentMetadata, Episode } from '../../shared/types';

export function ContentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [detail, setDetail] = useState<ContentDetail | null>(null);
  const [related, setRelated] = useState<{ sameGroup: ContentItem[]; sameSource: ContentItem[] }>({
    sameGroup: [],
    sameSource: [],
  });
  const [isLoading, setIsLoading] = useState(true);

  const play = usePlayerStore((s) => s.play);
  const toggle = useFavoritesStore((s) => s.toggle);
  const isFavorite = useFavoritesStore((s) => s.isFavorite);
  const pushToast = useToastStore((s) => s.push);

  // Fetch detail + related. Guard against the user navigating away (or
  // switching detail IDs) while the IPC round-trip is in flight — without
  // this, the resolution lands on an unmounted component or stomps state
  // from a newer fetch.
  useEffect(() => {
    if (!id || !window.api) return;
    let cancelled = false;
    setIsLoading(true);

    Promise.all([
      window.api.content.getDetail(id),
      window.api.content.getRelated(id),
    ]).then(([detailData, relatedData]) => {
      if (cancelled) return;
      setDetail(detailData);
      setRelated(relatedData ?? { sameGroup: [], sameSource: [] });
      setIsLoading(false);
    }).catch(() => {
      if (cancelled) return;
      // Settle isLoading so the spinner doesn't spin forever on IPC failure.
      setIsLoading(false);
    });

    return () => {
      cancelled = true;
    };
  }, [id]);

  const metadata = useMemo<ContentMetadata>(() => {
    if (!detail?.metadata) return {};
    return detail.metadata as ContentMetadata;
  }, [detail]);

  const handlePlay = useCallback(() => {
    if (!detail) return;
    const { item, episodes } = detail;

    if (item.type === 'series' && episodes.length > 0) {
      const ep = episodes[0];
      const title = ep.title || `S${ep.seasonNumber ?? 1}E${ep.episodeNumber ?? 1}`;
      const showName = item.cleanTitle || item.title;
      play(ep.streamUrl, `${showName} - ${title}`, item.id, ep.id);
    } else {
      play(item.streamUrl, item.cleanTitle || item.title, item.id);
    }
  }, [detail, play]);

  const handleEpisodePlay = useCallback(
    (episode: Episode) => {
      if (!detail) return;
      const showName = detail.item.cleanTitle || detail.item.title;
      const title = episode.title || `S${episode.seasonNumber ?? 1}E${episode.episodeNumber ?? '?'}`;
      play(episode.streamUrl, `${showName} - ${title}`, detail.item.id, episode.id);
    },
    [detail, play],
  );

  const handleDownload = useCallback(async () => {
    if (!detail) return;
    const { item } = detail;
    const title = item.cleanTitle || item.title;
    // Push an optimistic toast immediately — the enqueue round-trip is
    // ~instant but users still benefit from the visual confirmation.
    pushToast({
      kind: 'info',
      message: `Starting download: ${title}`,
      durationMs: 2000,
    });
    const res = await window.api.download.enqueue({
      title,
      streamUrl: item.streamUrl,
      contentId: item.id,
    });
    if (!res.ok) {
      pushToast({
        kind: 'error',
        message: `Could not start download: ${res.error}`,
        durationMs: 5000,
      });
      return;
    }
    pushToast({
      kind: 'success',
      message: `Added to downloads: ${title}`,
      action: { label: 'View', href: '/downloads' },
    });
  }, [detail, pushToast]);

  const handleEpisodeDownload = useCallback(
    async (episode: Episode) => {
      if (!detail) return;
      const showName = detail.item.cleanTitle || detail.item.title;
      const epLabel =
        episode.title || `S${episode.seasonNumber ?? 1}E${episode.episodeNumber ?? '?'}`;
      const label = `${showName} - ${epLabel}`;
      pushToast({
        kind: 'info',
        message: `Starting download: ${label}`,
        durationMs: 2000,
      });
      const res = await window.api.download.enqueue({
        title: label,
        streamUrl: episode.streamUrl,
        contentId: detail.item.id,
        episodeId: episode.id,
      });
      if (!res.ok) {
        pushToast({
          kind: 'error',
          message: `Could not start download: ${res.error}`,
          durationMs: 5000,
        });
        return;
      }
      pushToast({
        kind: 'success',
        message: `Added to downloads: ${label}`,
        action: { label: 'View', href: '/downloads' },
      });
    },
    [detail, pushToast],
  );

  const handleBack = useCallback(() => {
    navigate(-1);
  }, [navigate]);

  const handleRelatedClick = useCallback(
    (item: ContentItem) => {
      const route = item.type === 'series' ? `/series/${item.id}` : `/movies/${item.id}`;
      navigate(route);
    },
    [navigate],
  );

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-accent/30 border-t-accent" />
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4">
        <p className="text-surface-400">Content not found</p>
        <button
          onClick={handleBack}
          className="rounded-lg bg-surface-800 px-4 py-2 text-sm text-surface-200 transition-colors hover:bg-surface-700"
        >
          Go back
        </button>
      </div>
    );
  }

  const { item, episodes, watchPosition } = detail;
  const favorite = isFavorite(item.id);
  const hasRelated = related.sameGroup.length > 0 || related.sameSource.length > 0;
  const isSeries = item.type === 'series';

  return (
    <motion.div
      className="flex h-full flex-col overflow-y-auto"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.3 }}
    >
      {/* Hero — shared between movies and series */}
      <DetailHero
        item={item}
        metadata={metadata}
        watchPosition={watchPosition}
        episodes={episodes}
        isFavorite={favorite}
        onBack={handleBack}
        onPlay={handlePlay}
        onFavoriteToggle={() => toggle(item.id)}
        onDownload={item.type === 'movie' ? handleDownload : undefined}
      />

      <div className="flex-1 space-y-8 px-6 pb-8">
        {isSeries ? (
          /* ── SERIES LAYOUT ─────────────────────────────────────── */
          <>
            {/* Episodes — always visible, the main content */}
            {episodes.length > 0 && (
              <motion.section
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.25, delay: 0.1 }}
              >
                <h2 className="mb-3 text-lg font-semibold text-surface-100">Episodes</h2>
                <EpisodesTab
                  episodes={episodes}
                  contentId={item.id}
                  onEpisodePlay={handleEpisodePlay}
                  onEpisodeDownload={handleEpisodeDownload}
                />
              </motion.section>
            )}

            {/* Episodes loading state for Xtream series */}
            {episodes.length === 0 && (
              <div className="rounded-xl border border-dashed border-surface-700 bg-surface-900/50 py-10 text-center">
                <p className="text-sm text-surface-500">No episodes available</p>
              </div>
            )}

            {/* Related series */}
            {hasRelated && (
              <motion.section
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.25, delay: 0.2 }}
              >
                <RelatedTab
                  sameGroup={related.sameGroup}
                  sameSource={related.sameSource}
                  groupName={item.groupName}
                  onItemClick={handleRelatedClick}
                />
              </motion.section>
            )}
          </>
        ) : (
          /* ── MOVIE LAYOUT ──────────────────────────────────────── */
          <>
            {/* Inline info — no tabs, just show what exists */}
            <MovieInfo metadata={metadata} />

            {/* More Like This */}
            {hasRelated && (
              <motion.section
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.25, delay: 0.15 }}
              >
                <RelatedTab
                  sameGroup={related.sameGroup}
                  sameSource={related.sameSource}
                  groupName={item.groupName}
                  onItemClick={handleRelatedClick}
                />
              </motion.section>
            )}
          </>
        )}
      </div>
    </motion.div>
  );
}

/** Inline movie info section — renders only fields that have data */
function MovieInfo({ metadata }: { metadata: ContentMetadata }) {
  const description = metadata.plot || metadata.description;
  const cast = metadata.cast;
  const director = metadata.director;
  const genre = metadata.genre;
  const releaseDate = metadata.releaseDate;
  const hasAny = !!(description || cast || director || genre || releaseDate);

  if (!hasAny) return null;

  return (
    <motion.section
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, delay: 0.1 }}
    >
      <InfoTab metadata={metadata} />
    </motion.section>
  );
}
