import React, { useMemo, useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import type { ContentItem, ContentType } from '@yancotv/core';
import {
  useNavigation,
  type CompositeNavigationProp,
} from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { PageHeader } from '../components/layout/PageHeader';
import { ContentGrid } from '../components/cards/ContentGrid';
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

const ALL = '__all__';

interface Props {
  type: ContentType;
  title: string;
}

export function ChannelListScreen({ type, title }: Props) {
  const navigation = useNavigation<ListNavigation>();
  const openDetail = (channelId: string) =>
    navigation.navigate('Detail', { channelId });
  const allChannels = useSourcesStore((s) => s.channels);
  const [category, setCategory] = useState<string>(ALL);

  const items = useMemo(
    () => allChannels.filter((c) => c.type === type),
    [allChannels, type],
  );

  // Compute category names AND per-category counts in a single pass. The old
  // chip-row rendered `items.filter(...)` for every category on every render —
  // O(categories × items) and a big source of the tab-switch stall.
  const { categories, countByCategory } = useMemo(() => {
    const counts = new Map<string, number>();
    for (const it of items) {
      if (it.groupName) {
        counts.set(it.groupName, (counts.get(it.groupName) ?? 0) + 1);
      }
    }
    const names = Array.from(counts.keys()).sort((a, b) => a.localeCompare(b));
    return { categories: names, countByCategory: counts };
  }, [items]);

  const filtered: ContentItem[] = useMemo(() => {
    if (category === ALL) return items;
    return items.filter((it) => it.groupName === category);
  }, [items, category]);

  // Live TV uses the honeycomb hex card; movies/series use 2:3 posters.
  const variant: 'hex' | 'poster' = type === 'live' ? 'hex' : 'poster';

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
    <View style={{ flex: 1 }}>
      <PageHeader
        eyebrow={`${items.length.toLocaleString()} items`}
        title={title}
        subtitle={category === ALL ? 'All categories' : category}
      />

      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.chipRow}
      >
        <CategoryChip
          label="All"
          count={items.length}
          active={category === ALL}
          onPress={() => setCategory(ALL)}
        />
        {categories.map((cat) => (
          <CategoryChip
            key={cat}
            label={cat}
            count={countByCategory.get(cat) ?? 0}
            active={category === cat}
            onPress={() => setCategory(cat)}
          />
        ))}
      </ScrollView>

      <ContentGrid data={filtered} variant={variant} onOpen={openDetail} />
    </View>
  );
}

function CategoryChip({
  label,
  count,
  active,
  onPress,
}: {
  label: string;
  count: number;
  active: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={({ pressed, focused }) => [
        styles.chip,
        active && styles.chipActive,
        (pressed || focused) && !active && styles.chipFocus,
      ]}
    >
      <Text style={[styles.chipLabel, active && styles.chipLabelActive]}>
        {label}
      </Text>
      <Text style={[styles.chipCount, active && styles.chipCountActive]}>
        {count}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  chipRow: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.md,
    gap: 8,
  },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: radii.pill,
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
    marginRight: 8,
  },
  chipActive: {
    backgroundColor: 'rgba(0, 255, 170, 0.12)',
    borderColor: colors.accent,
  },
  chipFocus: {
    borderColor: 'rgba(0, 255, 170, 0.5)',
  },
  chipLabel: {
    color: colors.surface200,
    fontSize: 12,
    fontWeight: '600',
    marginRight: 6,
  },
  chipLabelActive: {
    color: colors.accent,
  },
  chipCount: {
    color: colors.surface400,
    fontSize: 10,
    fontWeight: '700',
    backgroundColor: 'rgba(255,255,255,0.05)',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: radii.sm,
    minWidth: 22,
    textAlign: 'center',
  },
  chipCountActive: {
    color: colors.accent,
    backgroundColor: 'rgba(0, 255, 170, 0.12)',
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
