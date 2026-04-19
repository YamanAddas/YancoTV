import React, { useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { ContentItem, ContentType } from '@yancotv/core';
import {
  useNavigation,
  type CompositeNavigationProp,
} from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { PageHeader } from '../components/layout/PageHeader';
import { ContentGrid } from '../components/cards/ContentGrid';
import {
  CategorySidebar,
  type CategorySelection,
} from '../components/layout/CategorySidebar';
import { useSourcesStore } from '../stores/sources-store';
import { colors, radii, spacing } from '../styles/theme';
import type {
  MainTabsParamList,
  RootStackParamList,
} from '../navigation/RootNavigator';

type ListNavigation = CompositeNavigationProp<
  BottomTabNavigationProp<MainTabsParamList>,
  NativeStackNavigationProp<RootStackParamList>
>;

interface Props {
  type: ContentType;
  title: string;
}

export function ChannelListScreen({ type, title }: Props) {
  const navigation = useNavigation<ListNavigation>();
  const openDetail = (channelId: string) =>
    navigation.navigate('Detail', { channelId });
  const allChannels = useSourcesStore((s) => s.channels);
  const [selection, setSelection] = useState<CategorySelection>(null);

  const items = useMemo(
    () => allChannels.filter((c) => c.type === type),
    [allChannels, type],
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

  const filtered: ContentItem[] = useMemo(() => {
    if (selection === null) return items;
    if (Array.isArray(selection)) {
      const set = new Set(selection);
      return items.filter((it) => it.groupName && set.has(it.groupName));
    }
    return items.filter((it) => it.groupName === selection);
  }, [items, selection]);

  const variant: 'hex' | 'poster' = type === 'live' ? 'hex' : 'poster';

  const subtitle = useMemo(() => {
    if (selection === null) return 'All categories';
    if (Array.isArray(selection)) return `${selection.length} groups`;
    return selection;
  }, [selection]);

  if (items.length === 0) {
    return (
      <View style={{ flex: 1 }}>
        <PageHeader title={title} subtitle="Nothing here yet" />
        <View style={styles.emptyPanel}>
          <Text style={styles.emptyTitle}>No {title.toLowerCase()}</Text>
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
        contentType={type}
        categoryCounts={countByCategory}
        totalCount={items.length}
      />

      <View style={styles.main}>
        <PageHeader
          eyebrow={`${filtered.length.toLocaleString()} items`}
          title={title}
          subtitle={subtitle}
        />
        <ContentGrid data={filtered} variant={variant} onOpen={openDetail} />
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
