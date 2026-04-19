import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { FlashList } from '@shopify/flash-list';
import type { ContentItem } from '@yancotv/core';
import { useShellStore, type RailCategory } from '../stores/shell-store';
import { usePlayerStore } from '../stores/player-store';
import { listByType } from '../db/queries';
import { colors, radii, spacing } from '../styles/theme';

const PAGE_SIZE = 40;
const TILE_HEIGHT = 146;
const TILE_GAP = spacing.sm;

export function ContentPanel() {
  const category = useShellStore((s) => s.category);
  const setActiveContent = useShellStore((s) => s.setActiveContent);
  const activeContentId = useShellStore((s) => s.activeContentId);
  const play = usePlayerStore((s) => s.play);

  const onRowPress = useCallback(
    (item: ContentItem) => {
      setActiveContent(item.id);
      if (!item.streamUrl) return;
      play({
        contentId: item.id,
        url: item.streamUrl,
        title: item.cleanTitle || item.title,
        logoUrl: item.logoUrl,
      });
    },
    [setActiveContent, play],
  );

  const [items, setItems] = useState<ContentItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [exhausted, setExhausted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const loadToken = useRef(0);

  useEffect(() => {
    const token = ++loadToken.current;
    setItems([]);
    setExhausted(false);
    setError(null);
    if (category.kind === 'favorites') {
      // Favorites rail lands in M5R.3. Show empty panel for now.
      setExhausted(true);
      return;
    }
    setLoading(true);
    const type = category.kind === 'type' ? category.type : category.type;
    const groupName = category.kind === 'group' ? category.groupName : undefined;
    listByType({ type, groupName, limit: PAGE_SIZE, offset: 0 })
      .then((rows) => {
        if (token !== loadToken.current) return;
        setItems(rows);
        setExhausted(rows.length < PAGE_SIZE);
      })
      .catch((e: unknown) => {
        if (token !== loadToken.current) return;
        setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (token !== loadToken.current) return;
        setLoading(false);
      });
  }, [category]);

  const loadMore = useCallback(() => {
    if (loading || exhausted) return;
    if (category.kind === 'favorites') return;
    const token = loadToken.current;
    setLoading(true);
    const type = category.kind === 'type' ? category.type : category.type;
    const groupName = category.kind === 'group' ? category.groupName : undefined;
    listByType({
      type,
      groupName,
      limit: PAGE_SIZE,
      offset: items.length,
    })
      .then((rows) => {
        if (token !== loadToken.current) return;
        setItems((prev) => [...prev, ...rows]);
        if (rows.length < PAGE_SIZE) setExhausted(true);
      })
      .catch((e: unknown) => {
        if (token !== loadToken.current) return;
        setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (token !== loadToken.current) return;
        setLoading(false);
      });
  }, [category, items.length, loading, exhausted]);

  const renderItem = useCallback(
    ({ item }: { item: ContentItem }) => (
      <ContentRow
        item={item}
        active={item.id === activeContentId}
        onPress={onRowPress}
      />
    ),
    [activeContentId, onRowPress],
  );

  return (
    <View style={styles.root}>
      <Header category={category} total={items.length} exhausted={exhausted} />
      {error ? (
        <View style={styles.empty}>
          <Text style={styles.emptyText}>{error}</Text>
        </View>
      ) : items.length === 0 && !loading ? (
        <View style={styles.empty}>
          <Text style={styles.emptyText}>
            {category.kind === 'favorites'
              ? 'Favorites arrive in M5R.3.'
              : 'No content yet. Add a source.'}
          </Text>
        </View>
      ) : (
        <FlashList
          data={items}
          keyExtractor={(item) => item.id}
          renderItem={renderItem}
          estimatedItemSize={TILE_HEIGHT + TILE_GAP}
          onEndReached={loadMore}
          onEndReachedThreshold={0.5}
          drawDistance={600}
        />
      )}
    </View>
  );
}

function Header({
  category,
  total,
  exhausted,
}: {
  category: RailCategory;
  total: number;
  exhausted: boolean;
}) {
  const label =
    category.kind === 'type'
      ? labelForType(category.type)
      : category.kind === 'favorites'
        ? 'Favorites'
        : category.groupName;
  return (
    <View style={styles.header}>
      <Text style={styles.headerTitle}>{label}</Text>
      {total > 0 && (
        <Text style={styles.headerCount}>
          {total}
          {exhausted ? '' : '+'}
        </Text>
      )}
    </View>
  );
}

function labelForType(t: 'live' | 'movie' | 'series'): string {
  if (t === 'live') return 'Live TV';
  if (t === 'movie') return 'Movies';
  return 'Series';
}

interface RowProps {
  item: ContentItem;
  active: boolean;
  onPress: (item: ContentItem) => void;
}

function ContentRow({ item, active, onPress }: RowProps) {
  const handlePress = useCallback(() => onPress(item), [item, onPress]);
  return (
    <Pressable
      onPress={handlePress}
      style={({ focused }) => [
        styles.row,
        active && styles.rowActive,
        focused && styles.rowFocused,
      ]}
    >
      <View style={styles.logoWrap}>
        {item.logoUrl ? (
          <Image
            source={{ uri: item.logoUrl }}
            style={styles.logo}
            resizeMode="contain"
          />
        ) : (
          <Text style={styles.logoFallback}>
            {(item.title || '?').charAt(0).toUpperCase()}
          </Text>
        )}
      </View>
      <View style={styles.rowText}>
        <Text style={styles.rowTitle} numberOfLines={1}>
          {item.cleanTitle || item.title}
        </Text>
        {item.groupName && (
          <Text style={styles.rowSub} numberOfLines={1}>
            {item.groupName}
          </Text>
        )}
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.lg,
    paddingBottom: spacing.md,
  },
  headerTitle: {
    color: colors.white,
    fontSize: 22,
    fontWeight: '800',
  },
  headerCount: {
    color: colors.surface400,
    fontSize: 13,
    fontWeight: '600',
  },
  empty: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xl,
  },
  emptyText: {
    color: colors.surface400,
    fontSize: 14,
    textAlign: 'center',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: spacing.lg,
    marginBottom: TILE_GAP,
    padding: spacing.md,
    borderRadius: radii.md,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'transparent',
  },
  rowActive: {
    borderColor: colors.accent,
    backgroundColor: colors.glass,
  },
  rowFocused: {
    borderColor: colors.focus,
    backgroundColor: colors.glass,
  },
  logoWrap: {
    width: 56,
    height: 56,
    borderRadius: radii.sm,
    backgroundColor: colors.surface800,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  logo: {
    width: '100%',
    height: '100%',
  },
  logoFallback: {
    color: colors.surface300,
    fontSize: 20,
    fontWeight: '800',
  },
  rowText: {
    flex: 1,
    marginLeft: spacing.md,
  },
  rowTitle: {
    color: colors.white,
    fontSize: 15,
    fontWeight: '700',
  },
  rowSub: {
    color: colors.surface400,
    fontSize: 12,
    marginTop: 2,
  },
});
