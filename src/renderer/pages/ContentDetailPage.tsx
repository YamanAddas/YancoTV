import { useEffect, useState, useMemo, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'motion/react';
import { DetailHero } from '../components/DetailHero';
import { DetailTabs } from '../components/DetailTabs';
import { EpisodesTab } from '../components/EpisodesTab';
import { InfoTab } from '../components/InfoTab';
import { RelatedTab } from '../components/RelatedTab';
import { usePlayerStore } from '../stores/player-store';
import { useFavoritesStore } from '../stores/favorites-store';
import type { ContentDetail, ContentItem, ContentMetadata, Episode } from '../../shared/types';

type TabId = 'episodes' | 'info' | 'related';

export function ContentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [detail, setDetail] = useState<ContentDetail | null>(null);
  const [related, setRelated] = useState<{ sameGroup: ContentItem[]; sameSource: ContentItem[] }>({
    sameGroup: [],
    sameSource: [],
  });
  const [isLoading, setIsLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<TabId>('episodes');

  const play = usePlayerStore((s) => s.play);
  const toggle = useFavoritesStore((s) => s.toggle);
  const isFavorite = useFavoritesStore((s) => s.isFavorite);

  // Fetch detail + related
  useEffect(() => {
    if (!id || !window.api) return;
    setIsLoading(true);

    Promise.all([
      window.api.content.getDetail(id),
      window.api.content.getRelated(id),
    ]).then(([detailData, relatedData]) => {
      setDetail(detailData);
      setRelated(relatedData ?? { sameGroup: [], sameSource: [] });
      setIsLoading(false);

      // Set initial active tab based on content type and data
      if (detailData) {
        const meta = detailData.metadata as ContentMetadata;
        const isSeries = detailData.item.type === 'series';
        const hasEpisodes = detailData.episodes.length > 0;
        const hasInfo = !!(meta.plot || meta.cast || meta.director || meta.genre || meta.description);

        if (isSeries && hasEpisodes) {
          setActiveTab('episodes');
        } else if (hasInfo) {
          setActiveTab('info');
        } else {
          setActiveTab('related');
        }
      }
    });
  }, [id]);

  const metadata = useMemo<ContentMetadata>(() => {
    if (!detail?.metadata) return {};
    return detail.metadata as ContentMetadata;
  }, [detail]);

  const availableTabs = useMemo<TabId[]>(() => {
    if (!detail) return [];
    const tabs: TabId[] = [];
    const isSeries = detail.item.type === 'series';
    const hasEpisodes = detail.episodes.length > 0;
    const hasInfo = !!(metadata.plot || metadata.cast || metadata.director || metadata.genre || metadata.description);
    const hasRelated = related.sameGroup.length > 0 || related.sameSource.length > 0;

    if (isSeries && hasEpisodes) tabs.push('episodes');
    if (hasInfo) tabs.push('info');
    if (hasRelated) tabs.push('related');

    return tabs;
  }, [detail, metadata, related]);

  const handlePlay = useCallback(() => {
    if (!detail) return;
    const { item, episodes } = detail;

    if (item.type === 'series' && episodes.length > 0) {
      // Play first episode (or resume episode)
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
  const showTabs = availableTabs.length > 1;

  return (
    <motion.div
      className="flex h-full flex-col overflow-y-auto"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.3 }}
    >
      {/* Hero Section */}
      <DetailHero
        item={item}
        metadata={metadata}
        watchPosition={watchPosition}
        episodes={episodes}
        isFavorite={favorite}
        onBack={handleBack}
        onPlay={handlePlay}
        onFavoriteToggle={() => toggle(item.id)}
      />

      {/* Tab Bar */}
      {showTabs && (
        <DetailTabs
          tabs={availableTabs}
          activeTab={activeTab}
          onTabChange={setActiveTab}
          contentType={item.type}
        />
      )}

      {/* Tab Content */}
      <div className="flex-1 px-6 pb-8">
        <AnimatePresence mode="wait">
          {activeTab === 'episodes' && availableTabs.includes('episodes') && (
            <motion.div
              key="episodes"
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.2 }}
            >
              <EpisodesTab
                episodes={episodes}
                contentId={item.id}
                onEpisodePlay={handleEpisodePlay}
              />
            </motion.div>
          )}

          {activeTab === 'info' && availableTabs.includes('info') && (
            <motion.div
              key="info"
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.2 }}
            >
              <InfoTab metadata={metadata} />
            </motion.div>
          )}

          {activeTab === 'related' && availableTabs.includes('related') && (
            <motion.div
              key="related"
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.2 }}
            >
              <RelatedTab
                sameGroup={related.sameGroup}
                sameSource={related.sameSource}
                groupName={item.groupName}
                onItemClick={handleRelatedClick}
              />
            </motion.div>
          )}

          {/* If no tabs available and single tab content */}
          {!showTabs && availableTabs.length === 1 && (
            <motion.div
              key={availableTabs[0]}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.2 }}
            >
              {availableTabs[0] === 'episodes' && (
                <EpisodesTab
                  episodes={episodes}
                  contentId={item.id}
                  onEpisodePlay={handleEpisodePlay}
                />
              )}
              {availableTabs[0] === 'info' && <InfoTab metadata={metadata} />}
              {availableTabs[0] === 'related' && (
                <RelatedTab
                  sameGroup={related.sameGroup}
                  sameSource={related.sameSource}
                  groupName={item.groupName}
                  onItemClick={handleRelatedClick}
                />
              )}
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </motion.div>
  );
}
