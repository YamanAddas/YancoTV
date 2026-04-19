import React, { useMemo, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { ContentItem } from '@yancotv/core';
import { useSourcesStore } from '../stores/sources-store';
import { useContentDetail } from '../hooks/use-content-detail';
import { DetailHero } from '../components/detail/DetailHero';
import { DetailTabBar, type TabDef } from '../components/detail/DetailTabBar';
import { InfoTab } from '../components/detail/InfoTab';
import { EpisodesTab } from '../components/detail/EpisodesTab';
import { RelatedTab } from '../components/detail/RelatedTab';
import { colors, radii, spacing } from '../styles/theme';
import type {
  DetailScreenProps,
  RootStackParamList,
} from '../navigation/RootNavigator';

type DetailNavigation = NativeStackNavigationProp<RootStackParamList, 'Detail'>;
type DetailTab = 'info' | 'episodes' | 'related';

const RELATED_LIMIT = 20;

/**
 * Single detail surface for live / movie / series. Hosts the cinematic hero,
 * a three-tab panel (Info / Episodes / Related), and loading/error states.
 * Series default to the Episodes tab and hide Info until the user toggles.
 * Movies and live default to Info and hide the Episodes tab entirely. The
 * Related rails are sourced in-memory from `useSourcesStore` — no new IPC
 * endpoint is needed since the channels union already holds everything.
 */
export function ContentDetailScreen() {
  const navigation = useNavigation<DetailNavigation>();
  const route = useRoute<DetailScreenProps['route']>();
  const selectedId = route.params?.channelId;

  const item = useSourcesStore((s) =>
    selectedId ? s.channels.find((c) => c.id === selectedId) : undefined,
  );
  const source = useSourcesStore((s) =>
    item ? s.sources.find((src) => src.id === item.sourceId) : undefined,
  );
  const allChannels = useSourcesStore((s) => s.channels);

  const { loading, error, metadata, episodes } = useContentDetail(selectedId);

  const isSeries = item?.type === 'series';
  const [tab, setTab] = useState<DetailTab>(isSeries ? 'episodes' : 'info');

  const tabs = useMemo<TabDef<DetailTab>[]>(() => {
    const base: TabDef<DetailTab>[] = [{ key: 'info', label: 'Info' }];
    if (isSeries) base.push({ key: 'episodes', label: 'Episodes' });
    base.push({ key: 'related', label: 'Related' });
    return base;
  }, [isSeries]);

  const { sameGroup, sameSource } = useMemo(
    () => buildRelated(allChannels, item),
    [allChannels, item],
  );

  if (!item) {
    return (
      <View style={styles.missing}>
        <Text style={styles.missingTitle}>Content not found</Text>
        <Pressable
          onPress={() => navigation.goBack()}
          style={({ pressed }) => [styles.backCta, pressed && { opacity: 0.8 }]}
        >
          <Text style={styles.backCtaText}>Back</Text>
        </Pressable>
      </View>
    );
  }

  const openPlayer = (episodeId?: string) =>
    navigation.navigate('Player', { channelId: item.id, episodeId });

  const canPlay = item.streamUrl.length > 0 || episodes.length > 0;
  const primaryLabel = (() => {
    if (item.type === 'series' && episodes.length > 0) return 'Play S01E01';
    if (item.type === 'series') return loading ? 'Loading episodes\u2026' : 'No episodes';
    return canPlay ? 'Play' : 'No stream URL';
  })();

  function onPlayPrimary() {
    if (item!.type === 'series') {
      const first = episodes
        .slice()
        .sort(
          (a, b) =>
            a.seasonNumber - b.seasonNumber || a.episodeNumber - b.episodeNumber,
        )[0];
      if (first) openPlayer(first.id);
      return;
    }
    if (canPlay) openPlayer();
  }

  return (
    <ScrollView
      contentContainerStyle={styles.content}
      showsVerticalScrollIndicator={false}
    >
      <DetailHero
        item={item}
        metadata={metadata}
        canPlay={canPlay}
        primaryLabel={primaryLabel}
        onBack={() => navigation.goBack()}
        onPlay={onPlayPrimary}
        subsCount={metadata.subtitles?.length ?? 0}
      />

      {loading && !metadata.detailFetchedAt ? (
        <View style={styles.loadingBox}>
          <ActivityIndicator color={colors.accent} />
          <Text style={styles.loadingText}>Loading details\u2026</Text>
        </View>
      ) : null}
      {error ? (
        <View style={styles.errorBox}>
          <Text style={styles.errorText}>Details unavailable: {error}</Text>
        </View>
      ) : null}

      <DetailTabBar tabs={tabs} active={tab} onChange={setTab} />

      {tab === 'info' ? (
        <InfoTab item={item} metadata={metadata} sourceName={source?.name} />
      ) : null}
      {tab === 'episodes' && isSeries ? (
        <EpisodesTab
          contentId={item.id}
          episodes={episodes}
          onEpisodePlay={(epId) => openPlayer(epId)}
          emptyLabel={loading ? 'Loading episodes\u2026' : 'No episodes available'}
        />
      ) : null}
      {tab === 'related' ? (
        <RelatedTab
          sameGroup={sameGroup}
          sameSource={sameSource}
          groupName={item.groupName}
          onItemPress={(id) => navigation.replace('Detail', { channelId: id })}
        />
      ) : null}
    </ScrollView>
  );
}

/**
 * Build same-group and same-source cohorts from the in-memory channels list.
 * Same-group narrows by type+group; same-source falls back to the full source
 * pool minus anything already in the group rail. Both rails are clamped to
 * RELATED_LIMIT so a 5k-movie Xtream source doesn't hang the tab.
 */
function buildRelated(
  all: ContentItem[],
  item: ContentItem | undefined,
): { sameGroup: ContentItem[]; sameSource: ContentItem[] } {
  if (!item) return { sameGroup: [], sameSource: [] };
  const sameGroup: ContentItem[] = [];
  const sameSource: ContentItem[] = [];
  const groupKey = item.groupName;
  for (const c of all) {
    if (c.id === item.id) continue;
    if (c.type !== item.type) continue;
    if (c.sourceId !== item.sourceId) continue;
    if (groupKey && c.groupName === groupKey) {
      if (sameGroup.length < RELATED_LIMIT) sameGroup.push(c);
    } else {
      if (sameSource.length < RELATED_LIMIT) sameSource.push(c);
    }
    if (
      sameGroup.length >= RELATED_LIMIT &&
      sameSource.length >= RELATED_LIMIT
    ) {
      break;
    }
  }
  return { sameGroup, sameSource };
}

const styles = StyleSheet.create({
  content: {
    paddingBottom: spacing.xxl,
  },
  missing: {
    flex: 1,
    padding: spacing.xl,
    alignItems: 'flex-start',
    justifyContent: 'center',
  },
  missingTitle: {
    marginBottom: spacing.md,
    fontSize: 24,
    fontWeight: '700',
    color: colors.white,
  },
  backCta: {
    paddingHorizontal: 22,
    paddingVertical: 12,
    backgroundColor: colors.accent,
    borderRadius: radii.md,
  },
  backCtaText: {
    color: colors.bg,
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 1,
  },
  loadingBox: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.sm,
  },
  loadingText: {
    color: colors.surface300,
    fontSize: 13,
  },
  errorBox: {
    marginHorizontal: spacing.xl,
    padding: spacing.md,
    borderRadius: radii.md,
    backgroundColor: 'rgba(248, 113, 113, 0.08)',
    borderWidth: 1,
    borderColor: 'rgba(248, 113, 113, 0.3)',
    marginBottom: spacing.md,
  },
  errorText: {
    color: colors.red300,
    fontSize: 12,
  },
});
