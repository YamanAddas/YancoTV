import React, { useCallback, useMemo, useState } from 'react';
import {
  FlatList,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from 'react-native';
import type { ContentItem, ContentType } from '@yancotv/core';
import { PageHeader } from '../components/layout/PageHeader';
import { ContentCard } from '../components/cards/ContentCard';
import { useNavStore } from '../stores/nav-store';
import { useSourcesStore } from '../stores/sources-store';
import { colors, radii, spacing, sidebar } from '../styles/theme';

const ALL = '__all__';

interface Props {
  type: ContentType;
  title: string;
}

export function ChannelListScreen({ type, title }: Props) {
  const openDetail = useNavStore((s) => s.openDetail);
  const navigate = useNavStore((s) => s.navigate);
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

  const filtered = useMemo(() => {
    if (category === ALL) return items;
    return items.filter((it) => it.groupName === category);
  }, [items, category]);

  const { width: screenW } = useWindowDimensions();
  // Live TV uses the honeycomb hex card; movies/series use 2:3 posters.
  const variantKey: 'hex' | 'poster' = type === 'live' ? 'hex' : 'poster';
  // Assume sidebar collapsed on phones (matches Sidebar's narrow-screen default)
  // and expanded on larger displays. Either way, leave gutter room on both sides.
  const sidebarW = screenW >= 720 ? sidebar.width : sidebar.widthCollapsed;
  const availableWidth = screenW - sidebarW - spacing.md * 2;
  const targetCardW = variantKey === 'poster' ? 120 : 130;
  const columns = Math.max(
    2,
    Math.floor(availableWidth / (targetCardW + 12)),
  );
  const cardWidth = Math.floor((availableWidth - 12 * (columns - 1)) / columns);

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
            onPress={() => navigate('sources')}
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

      <ChannelGrid
        columns={columns}
        cardWidth={cardWidth}
        variantKey={variantKey}
        data={filtered}
        onOpen={openDetail}
      />
    </View>
  );
}

// Memoized grid. Stable renderItem + getItemLayout avoids re-renders of every
// card on each category switch, which was a big contributor to tab-switch lag
// with 5k+ items.
const ChannelGrid = React.memo(function ChannelGrid({
  columns,
  cardWidth,
  variantKey,
  data,
  onOpen,
}: {
  columns: number;
  cardWidth: number;
  variantKey: 'hex' | 'poster';
  data: ContentItem[];
  onOpen: (id: string) => void;
}) {
  const renderItem = useCallback(
    ({ item }: { item: ContentItem }) => (
      <ContentCard
        title={item.title}
        subtitle={item.groupName}
        imageUrl={item.logoUrl}
        variant={variantKey}
        width={cardWidth}
        onPress={() => onOpen(item.id)}
      />
    ),
    [variantKey, cardWidth, onOpen],
  );

  return (
    <FlatList
      key={`grid-${columns}`}
      data={data}
      keyExtractor={keyExtractor}
      numColumns={columns}
      columnWrapperStyle={columns > 1 ? styles.gridRow : undefined}
      contentContainerStyle={styles.gridContent}
      renderItem={renderItem}
      initialNumToRender={12}
      maxToRenderPerBatch={12}
      windowSize={7}
      removeClippedSubviews
    />
  );
});

const keyExtractor = (it: ContentItem) => it.id;

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
  gridRow: {
    gap: 12,
    marginBottom: 16,
  },
  gridContent: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxl,
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
