import React, { useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  useNavigation,
  type CompositeNavigationProp,
} from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import type { ContentItem, SortOption } from '@yancotv/core';
import { PageHeader } from '../components/layout/PageHeader';
import { SortDropdown } from '../components/layout/SortDropdown';
import { ContentGrid } from '../components/cards/ContentGrid';
import {
  CategorySidebar,
  type CategorySelection,
} from '../components/layout/CategorySidebar';
import { useSourcesStore } from '../stores/sources-store';
import { useNowNext } from '../hooks/use-now-next';
import { colors, radii, spacing } from '../styles/theme';
import type {
  MainTabsParamList,
  RootStackParamList,
} from '../navigation/RootNavigator';

type LiveNavigation = CompositeNavigationProp<
  BottomTabNavigationProp<MainTabsParamList>,
  NativeStackNavigationProp<RootStackParamList>
>;

/**
 * Sort live channels per the desktop `SortOption` vocabulary. Provider order
 * reads `sortOrder` (the original M3U / Xtream index); group/name sorts fall
 * back to sortOrder as a stable tiebreaker so repeated sorts are deterministic.
 */
function sortChannels(items: ContentItem[], sort: SortOption): ContentItem[] {
  const copy = items.slice();
  switch (sort) {
    case 'provider':
      copy.sort((a, b) => a.sortOrder - b.sortOrder);
      return copy;
    case 'name-asc':
      copy.sort((a, b) =>
        (a.cleanTitle || a.title).localeCompare(b.cleanTitle || b.title),
      );
      return copy;
    case 'name-desc':
      copy.sort((a, b) =>
        (b.cleanTitle || b.title).localeCompare(a.cleanTitle || a.title),
      );
      return copy;
    case 'recent':
      copy.sort((a, b) => b.createdAt - a.createdAt);
      return copy;
    case 'group':
      copy.sort((a, b) => {
        const g = (a.groupName ?? '').localeCompare(b.groupName ?? '');
        return g !== 0 ? g : a.sortOrder - b.sortOrder;
      });
      return copy;
    default:
      return copy;
  }
}

export function LiveTvScreen() {
  const navigation = useNavigation<LiveNavigation>();
  const openDetail = (channelId: string) =>
    navigation.navigate('Detail', { channelId });

  const allChannels = useSourcesStore((s) => s.channels);
  const [selection, setSelection] = useState<CategorySelection>(null);
  const [sortBy, setSortBy] = useState<SortOption>('provider');

  const items = useMemo(
    () => allChannels.filter((c) => c.type === 'live'),
    [allChannels],
  );

  const { categories, countByCategory } = useMemo(() => {
    const counts = new Map<string, number>();
    for (const it of items) {
      if (it.groupName) {
        counts.set(it.groupName, (counts.get(it.groupName) ?? 0) + 1);
      }
    }
    return {
      categories: Array.from(counts.keys()),
      countByCategory: counts,
    };
  }, [items]);

  const filtered = useMemo<ContentItem[]>(() => {
    if (selection === null) return items;
    if (Array.isArray(selection)) {
      const set = new Set(selection);
      return items.filter((it) => it.groupName != null && set.has(it.groupName));
    }
    return items.filter((it) => it.groupName === selection);
  }, [items, selection]);

  const sorted = useMemo(() => sortChannels(filtered, sortBy), [filtered, sortBy]);

  // Now/next hook call site is in place for M6. Today it returns an empty map
  // so the overlay renders nothing; wiring EPG data later is a drop-in.
  const tvgIds = useMemo(
    () =>
      sorted
        .map((c) => c.tvgId)
        .filter((id): id is string => !!id && id.length > 0),
    [sorted],
  );
  useNowNext(tvgIds);

  const subtitle = useMemo(() => {
    if (selection === null) return 'All categories';
    if (Array.isArray(selection)) return `${selection.length} groups`;
    return selection;
  }, [selection]);

  if (items.length === 0) {
    return (
      <View style={{ flex: 1 }}>
        <PageHeader title="Live TV" subtitle="Nothing here yet" />
        <View style={styles.emptyPanel}>
          <Text style={styles.emptyTitle}>No live channels</Text>
          <Text style={styles.emptyText}>
            Add an IPTV source to start browsing.
          </Text>
          <Pressable
            onPress={() => navigation.navigate('Sources')}
            style={({ pressed }) => [styles.cta, pressed && { opacity: 0.85 }]}
          >
            <Text style={styles.ctaText}>Go to Sources</Text>
          </Pressable>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.root}>
      <CategorySidebar
        categories={categories}
        selected={selection}
        onSelect={setSelection}
        contentType="live"
        categoryCounts={countByCategory}
        totalCount={items.length}
      />

      <View style={styles.main}>
        <PageHeader
          eyebrow={`${sorted.length.toLocaleString()} channels`}
          title="Live TV"
          subtitle={subtitle}
          right={<SortDropdown value={sortBy} onChange={setSortBy} />}
        />
        <ContentGrid data={sorted} variant="hex" onOpen={openDetail} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    flexDirection: 'row',
  },
  main: {
    flex: 1,
  },
  emptyPanel: {
    margin: spacing.xl,
    padding: spacing.xl,
    borderRadius: radii.lg,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
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
  },
  cta: {
    backgroundColor: colors.accent,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    borderRadius: radii.md,
    alignSelf: 'flex-start',
  },
  ctaText: {
    color: colors.bg,
    fontSize: 13,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
});
