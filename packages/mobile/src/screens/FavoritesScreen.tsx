import React, { useMemo } from 'react';
import {
  FlatList,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  useNavigation,
  type CompositeNavigationProp,
} from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import type { ContentItem } from '@yancotv/core';
import { PageHeader } from '../components/layout/PageHeader';
import { ContentCard } from '../components/cards/ContentCard';
import { useSourcesStore } from '../stores/sources-store';
import { useFavoritesStore } from '../stores/favorites-store';
import { colors, radii, spacing } from '../styles/theme';
import type {
  MainTabsParamList,
  RootStackParamList,
} from '../navigation/RootNavigator';

type FavoritesNavigation = CompositeNavigationProp<
  BottomTabNavigationProp<MainTabsParamList>,
  NativeStackNavigationProp<RootStackParamList>
>;

type Bucket = 'live' | 'movie' | 'series';
const BUCKETS: { key: Bucket; label: string; variant: 'hex' | 'poster' }[] = [
  { key: 'live', label: 'Live TV', variant: 'hex' },
  { key: 'movie', label: 'Movies', variant: 'poster' },
  { key: 'series', label: 'Series', variant: 'poster' },
];

export function FavoritesScreen() {
  const navigation = useNavigation<FavoritesNavigation>();
  const channels = useSourcesStore((s) => s.channels);
  const favoriteIds = useFavoritesStore((s) => s.favoriteIds);
  const isLoaded = useFavoritesStore((s) => s.isLoaded);

  const { favorites, byType } = useMemo(() => {
    const all: ContentItem[] = [];
    for (const c of channels) {
      if (favoriteIds.has(c.id)) all.push(c);
    }
    const buckets: Record<Bucket, ContentItem[]> = {
      live: [],
      movie: [],
      series: [],
    };
    for (const f of all) {
      if (f.type === 'live' || f.type === 'movie' || f.type === 'series') {
        buckets[f.type].push(f);
      }
    }
    return { favorites: all, byType: buckets };
  }, [channels, favoriteIds]);

  const openDetail = (id: string) =>
    navigation.navigate('Detail', { channelId: id });

  if (!isLoaded) {
    return (
      <View style={styles.root}>
        <PageHeader title="Favorites" subtitle="Loading…" />
      </View>
    );
  }

  if (favorites.length === 0) {
    return (
      <ScrollView contentContainerStyle={styles.scroll}>
        <PageHeader
          eyebrow="Saved for later"
          title="Favorites"
          subtitle="Nothing here yet"
        />
        <View style={styles.emptyPanel}>
          <Text style={styles.emptyTitle}>No favorites yet</Text>
          <Text style={styles.emptyText}>
            Open any channel, movie or series and tap the heart to save it here
            for quick access.
          </Text>
          <Pressable
            onPress={() => navigation.navigate('Home')}
            style={({ pressed }) => [styles.cta, pressed && { opacity: 0.8 }]}
          >
            <Text style={styles.ctaText}>Browse content</Text>
          </Pressable>
        </View>
      </ScrollView>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.scroll}>
      <PageHeader
        eyebrow="Saved for later"
        title="Favorites"
        subtitle={`${favorites.length} saved`}
      />
      {BUCKETS.map(({ key, label, variant }) =>
        byType[key].length > 0 ? (
          <FavoritesRow
            key={key}
            title={label}
            items={byType[key]}
            variant={variant}
            onPick={openDetail}
          />
        ) : null,
      )}
    </ScrollView>
  );
}

function FavoritesRow({
  title,
  items,
  variant,
  onPick,
}: {
  title: string;
  items: ContentItem[];
  variant: 'hex' | 'poster';
  onPick: (id: string) => void;
}) {
  return (
    <View style={styles.section}>
      <View style={styles.rowHeader}>
        <Text style={styles.sectionTitle}>{title}</Text>
        <Text style={styles.rowCount}>{items.length}</Text>
      </View>
      <FlatList
        horizontal
        data={items}
        keyExtractor={(it) => it.id}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.rowList}
        renderItem={({ item }) => (
          <View style={{ marginRight: 12 }}>
            <ContentCard
              title={item.title}
              subtitle={item.groupName}
              imageUrl={item.logoUrl}
              variant={variant}
              width={variant === 'hex' ? 130 : 120}
              onPress={() => onPick(item.id)}
            />
          </View>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  scroll: {
    paddingBottom: spacing.xxl,
  },
  section: {
    marginTop: spacing.md,
  },
  rowHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: spacing.xl,
    marginBottom: spacing.sm + 4,
  },
  sectionTitle: {
    color: colors.white,
    fontSize: 16,
    fontWeight: '700',
    letterSpacing: 0.5,
    textTransform: 'uppercase',
  },
  rowCount: {
    color: colors.surface400,
    fontSize: 11,
    fontWeight: '700',
    backgroundColor: colors.surface800,
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: radii.pill,
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
