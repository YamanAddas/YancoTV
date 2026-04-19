import React, { useEffect, useMemo } from 'react';
import {
  FlatList,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import type { ContentItem, ContentType, HistoryEntry } from '@yancotv/core';
import {
  useNavigation,
  type CompositeNavigationProp,
} from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { PageHeader } from '../components/layout/PageHeader';
import { ContentCard } from '../components/cards/ContentCard';
import { useSourcesStore } from '../stores/sources-store';
import { useFavoritesStore } from '../stores/favorites-store';
import { useHistoryStore } from '../stores/history-store';
import { useRecentChannelsStore } from '../stores/recent-channels-store';
import { colors, radii, spacing } from '../styles/theme';
import type {
  MainTabsParamList,
  RootStackParamList,
} from '../navigation/RootNavigator';

// The navigator is a drawer on TV and bottom-tabs on phone, but the nav methods
// we use (`navigate`, `getParent`) are identical across them. Typing against
// BottomTab is sufficient for strict-mode callers.
type HomeNavigation = CompositeNavigationProp<
  BottomTabNavigationProp<MainTabsParamList>,
  NativeStackNavigationProp<RootStackParamList>
>;

const QUICK_LINKS: {
  route: keyof MainTabsParamList;
  label: string;
  caption: string;
  accent: string;
}[] = [
  { route: 'Live', label: 'Live TV', caption: 'Channels now on air', accent: '#22c55e' },
  { route: 'Movies', label: 'Movies', caption: 'On-demand films', accent: '#a855f7' },
  { route: 'Series', label: 'Series', caption: 'TV shows', accent: '#f97316' },
  { route: 'Sources', label: 'Sources', caption: 'Manage playlists', accent: '#3b82f6' },
];

function ContinueWatchingRow({
  items,
  onResume,
  onShowDetail,
}: {
  items: HistoryEntry[];
  onResume: (entry: HistoryEntry) => void;
  onShowDetail: (entry: HistoryEntry) => void;
}) {
  return (
    <View style={styles.rowSection}>
      <Text style={styles.rowTitle}>Continue watching</Text>
      <FlatList
        horizontal
        data={items}
        keyExtractor={(it) => it.id}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.rowList}
        initialNumToRender={4}
        windowSize={3}
        maxToRenderPerBatch={3}
        removeClippedSubviews
        renderItem={({ item }) => {
          const pct =
            item.durationSeconds && item.durationSeconds > 0
              ? Math.min(
                  0.99,
                  Math.max(0, item.positionSeconds / item.durationSeconds),
                )
              : 0;
          return (
            <View style={{ marginRight: 12 }}>
              <ContentCard
                title={item.content.title}
                subtitle={
                  item.episodeId ? 'Episode' : item.content.groupName
                }
                imageUrl={item.content.logoUrl}
                variant={item.content.type === 'live' ? 'hex' : 'poster'}
                width={item.content.type === 'live' ? 130 : 120}
                onPress={() => onResume(item)}
                onLongPress={() => onShowDetail(item)}
                progress={pct > 0 ? pct : undefined}
              />
            </View>
          );
        }}
      />
    </View>
  );
}

function RecentRow({ title, items, onPick }: {
  title: string;
  items: ContentItem[];
  onPick: (id: string) => void;
}) {
  if (items.length === 0) return null;
  return (
    <View style={styles.rowSection}>
      <Text style={styles.rowTitle}>{title}</Text>
      <FlatList
        horizontal
        data={items}
        keyExtractor={(it) => it.id}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.rowList}
        initialNumToRender={4}
        windowSize={3}
        maxToRenderPerBatch={3}
        removeClippedSubviews
        renderItem={({ item }) => (
          <View style={{ marginRight: 12 }}>
            <ContentCard
              title={item.title}
              subtitle={item.groupName}
              imageUrl={item.logoUrl}
              variant={item.type === 'live' ? 'hex' : 'poster'}
              width={item.type === 'live' ? 130 : 120}
              onPress={() => onPick(item.id)}
            />
          </View>
        )}
      />
    </View>
  );
}

export function HomeScreen() {
  const navigation = useNavigation<HomeNavigation>();
  const openDetail = (channelId: string) =>
    navigation.navigate('Detail', { channelId });
  const channels = useSourcesStore((s) => s.channels);
  const sources = useSourcesStore((s) => s.sources);
  const recentHistory = useHistoryStore((s) => s.recent);
  const recentChannelIds = useRecentChannelsStore((s) => s.ids);

  // updatePosition() writes directly to SQLite during playback without
  // touching the Zustand `recent` array, so returning from the player would
  // otherwise leave stale progress bars on this screen. Reload on focus.
  // NB: we avoid @react-navigation's useFocusEffect — pnpm resolves multiple
  // @react-navigation/core copies against distinct React peer hashes, and the
  // hook form crashes with "Cannot read property 'useCallback' of null" when
  // it crosses that boundary. The imperative `addListener('focus', …)` form
  // runs entirely in our local React instance.
  useEffect(() => {
    const unsub = navigation.addListener('focus', () => {
      void useHistoryStore.getState().load();
      void useFavoritesStore.getState().load();
    });
    return unsub;
  }, [navigation]);

  const rows = useMemo(() => {
    const byType = (t: ContentType) =>
      channels.filter((c) => c.type === t).slice(0, 20);
    return {
      live: byType('live'),
      movies: byType('movie'),
      series: byType('series'),
    };
  }, [channels]);

  // Continue-watching rail from watch_history. Entries already carry the
  // joined content row, so no second lookup is needed.
  const continueWatching = useMemo<HistoryEntry[]>(() => {
    return recentHistory.filter((e) => e.content.type !== 'live').slice(0, 15);
  }, [recentHistory]);

  // Recent live channels: resolve IDs against the in-memory channel map so
  // a zap-back tile shows the same metadata as the main grid. Skip any id
  // whose source has since been removed.
  const recentLive = useMemo<ContentItem[]>(() => {
    if (recentChannelIds.length === 0) return [];
    const byId = new Map(channels.map((c) => [c.id, c]));
    const out: ContentItem[] = [];
    for (const id of recentChannelIds) {
      const c = byId.get(id);
      if (c && c.type === 'live') out.push(c);
    }
    return out;
  }, [channels, recentChannelIds]);

  const openPlayer = (channelId: string, episodeId?: string) =>
    navigation.navigate('Player', { channelId, episodeId });

  const totalCount = channels.length;

  return (
    <ScrollView contentContainerStyle={styles.scroll}>
        <PageHeader
          eyebrow="Welcome back"
          title="Dashboard"
          subtitle={
            sources.length === 0
              ? 'Add an IPTV source to get started.'
              : `${sources.length} ${sources.length === 1 ? 'source' : 'sources'} · ${totalCount.toLocaleString()} items`
          }
        />

        <View style={styles.tilesWrap}>
          {QUICK_LINKS.map((q) => (
            <Pressable
              key={q.route}
              onPress={() => navigation.navigate(q.route)}
              style={({ pressed, focused }) => [
                styles.tile,
                { borderColor: q.accent + '55' },
                (pressed || focused) && { borderColor: q.accent, backgroundColor: q.accent + '14' },
              ]}
            >
              <View style={[styles.tileDot, { backgroundColor: q.accent }]} />
              <Text style={styles.tileLabel}>{q.label}</Text>
              <Text style={styles.tileCaption}>{q.caption}</Text>
            </Pressable>
          ))}
        </View>

        {sources.length === 0 ? (
          <View style={styles.emptyPanel}>
            <Text style={styles.emptyTitle}>No sources yet</Text>
            <Text style={styles.emptyText}>
              Add an Xtream, M3U or Stalker playlist from the Sources screen.
            </Text>
            <Pressable
              onPress={() => navigation.navigate('Sources')}
              style={({ pressed }) => [styles.cta, pressed && { opacity: 0.8 }]}
            >
              <Text style={styles.ctaText}>Go to Sources</Text>
            </Pressable>
          </View>
        ) : (
          <>
            {continueWatching.length > 0 ? (
              <ContinueWatchingRow
                items={continueWatching}
                onResume={(entry) =>
                  openPlayer(entry.contentId, entry.episodeId)
                }
                onShowDetail={(entry) => openDetail(entry.contentId)}
              />
            ) : null}
            {recentLive.length > 0 ? (
              <RecentRow
                title="Recent channels"
                items={recentLive}
                onPick={(id) => openPlayer(id)}
              />
            ) : null}
            <RecentRow title="Live TV" items={rows.live} onPick={openDetail} />
            <RecentRow title="Movies" items={rows.movies} onPick={openDetail} />
            <RecentRow title="Series" items={rows.series} onPick={openDetail} />
          </>
        )}
      </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingBottom: spacing.xxl,
  },
  tilesWrap: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.md,
    paddingHorizontal: spacing.xl,
    marginTop: spacing.md,
  },
  tile: {
    flexGrow: 1,
    minWidth: 160,
    padding: spacing.md,
    borderRadius: radii.lg,
    borderWidth: 1,
    backgroundColor: colors.surface900,
  },
  tileDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginBottom: 10,
  },
  tileLabel: {
    color: colors.white,
    fontSize: 18,
    fontWeight: '700',
  },
  tileCaption: {
    marginTop: 4,
    color: colors.surface400,
    fontSize: 12,
  },
  rowSection: {
    marginTop: spacing.xl,
  },
  rowTitle: {
    paddingHorizontal: spacing.xl,
    color: colors.white,
    fontSize: 18,
    fontWeight: '700',
    marginBottom: spacing.sm + 4,
  },
  rowList: {
    paddingHorizontal: spacing.xl,
  },
  emptyPanel: {
    margin: spacing.xl,
    padding: spacing.xl,
    borderRadius: radii.lg,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
    alignItems: 'flex-start',
  },
  emptyTitle: {
    color: colors.white,
    fontSize: 20,
    fontWeight: '700',
    marginBottom: 8,
  },
  emptyText: {
    color: colors.surface400,
    fontSize: 13,
    marginBottom: spacing.md,
    lineHeight: 18,
  },
  cta: {
    backgroundColor: colors.accent,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    borderRadius: radii.md,
  },
  ctaText: {
    color: colors.bg,
    fontSize: 13,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
});
