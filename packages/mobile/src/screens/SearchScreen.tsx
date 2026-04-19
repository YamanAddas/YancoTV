import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  useNavigation,
  type CompositeNavigationProp,
} from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import type { ContentItem, ContentType } from '@yancotv/core';
import { PageHeader } from '../components/layout/PageHeader';
import { ContentCard } from '../components/cards/ContentCard';
import { searchContent } from '../db/content-store';
import { useSearchHistoryStore } from '../stores/search-history-store';
import { colors, radii, spacing } from '../styles/theme';
import type {
  MainTabsParamList,
  RootStackParamList,
} from '../navigation/RootNavigator';

type SearchNavigation = CompositeNavigationProp<
  BottomTabNavigationProp<MainTabsParamList>,
  NativeStackNavigationProp<RootStackParamList>
>;

type TypeFilter = 'all' | ContentType;

const FILTER_OPTIONS: { value: TypeFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'live', label: 'Live' },
  { value: 'movie', label: 'Movies' },
  { value: 'series', label: 'Series' },
];

const DEBOUNCE_MS = 300;
const ZONE_DISPLAY_CAP = 24;

export function SearchScreen() {
  const navigation = useNavigation<SearchNavigation>();
  const [query, setQuery] = useState('');
  const [committedQuery, setCommittedQuery] = useState('');
  const [filter, setFilter] = useState<TypeFilter>('all');
  const [results, setResults] = useState<ContentItem[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const runIdRef = useRef(0);

  const history = useSearchHistoryStore((s) => s.entries);
  const recordHistory = useSearchHistoryStore((s) => s.record);
  const removeHistory = useSearchHistoryStore((s) => s.remove);
  const clearHistory = useSearchHistoryStore((s) => s.clear);

  const runSearch = useCallback(async (q: string) => {
    const trimmed = q.trim();
    const runId = ++runIdRef.current;
    if (!trimmed) {
      setResults([]);
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    try {
      const data = await searchContent(trimmed);
      if (runIdRef.current !== runId) return; // stale
      setResults(data);
      if (data.length > 0) {
        // record once results land — skips typo history entries
        void recordHistory(trimmed);
      }
    } catch {
      if (runIdRef.current === runId) setResults([]);
    } finally {
      if (runIdRef.current === runId) setIsLoading(false);
    }
  }, [recordHistory]);

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setCommittedQuery(query);
      void runSearch(query);
    }, DEBOUNCE_MS);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [query, runSearch]);

  const onHistoryPick = (q: string) => {
    setQuery(q);
  };

  const onItemPress = (item: ContentItem) => {
    if (item.type === 'live' && item.streamUrl) {
      navigation.navigate('Player', { channelId: item.id });
      return;
    }
    navigation.navigate('Detail', { channelId: item.id });
  };

  const bucketed = useMemo(() => {
    const live: ContentItem[] = [];
    const movies: ContentItem[] = [];
    const series: ContentItem[] = [];
    for (const r of results) {
      if (r.type === 'live') live.push(r);
      else if (r.type === 'movie') movies.push(r);
      else if (r.type === 'series') series.push(r);
    }
    return { live, movies, series };
  }, [results]);

  const visibleCount =
    (filter === 'all' || filter === 'live' ? bucketed.live.length : 0) +
    (filter === 'all' || filter === 'movie' ? bucketed.movies.length : 0) +
    (filter === 'all' || filter === 'series' ? bucketed.series.length : 0);

  const showEmptyNoQuery = committedQuery.trim().length === 0 && !isLoading;
  const showNoResults =
    committedQuery.trim().length > 0 && !isLoading && visibleCount === 0;

  return (
    <ScrollView
      contentContainerStyle={styles.scroll}
      keyboardShouldPersistTaps="handled"
    >
      <PageHeader
        eyebrow="Find anything"
        title="Search"
        subtitle="Channels, movies and series across every source"
      />

      <View style={styles.inputWrap}>
        <TextInput
          value={query}
          onChangeText={setQuery}
          placeholder="Search channels, movies, series..."
          placeholderTextColor={colors.surface500}
          autoCorrect={false}
          autoCapitalize="none"
          returnKeyType="search"
          style={styles.input}
          selectionColor={colors.accent}
        />
        {query.length > 0 ? (
          <Pressable
            onPress={() => setQuery('')}
            style={({ pressed, focused }) => [
              styles.clearBtn,
              (pressed || focused) && styles.clearBtnFocus,
            ]}
          >
            <Text style={styles.clearBtnText}>Clear</Text>
          </Pressable>
        ) : null}
      </View>

      <View style={styles.filterRow}>
        {FILTER_OPTIONS.map((opt) => {
          const selected = filter === opt.value;
          return (
            <Pressable
              key={opt.value}
              onPress={() => setFilter(opt.value)}
              style={({ pressed, focused }) => [
                styles.chip,
                selected && styles.chipSelected,
                (pressed || focused) && !selected && styles.chipFocus,
              ]}
            >
              <Text style={[styles.chipText, selected && styles.chipTextSelected]}>
                {opt.label}
              </Text>
            </Pressable>
          );
        })}
      </View>

      {isLoading ? (
        <View style={styles.loadingRow}>
          <ActivityIndicator color={colors.accent} />
          <Text style={styles.loadingText}>Searching…</Text>
        </View>
      ) : null}

      {showEmptyNoQuery && history.length === 0 ? (
        <View style={styles.emptyPanel}>
          <Text style={styles.emptyText}>Type to search your content library.</Text>
        </View>
      ) : null}

      {showEmptyNoQuery && history.length > 0 ? (
        <View style={styles.historySection}>
          <View style={styles.historyHeader}>
            <Text style={styles.sectionTitle}>Recent searches</Text>
            <Pressable
              onPress={() => void clearHistory()}
              style={({ pressed }) => [pressed && { opacity: 0.6 }]}
            >
              <Text style={styles.clearAll}>Clear all</Text>
            </Pressable>
          </View>
          <View style={styles.historyRow}>
            {history.map((h) => (
              <View key={h} style={styles.historyChip}>
                <Pressable
                  onPress={() => onHistoryPick(h)}
                  style={({ pressed, focused }) => [
                    styles.historyPick,
                    (pressed || focused) && styles.historyPickFocus,
                  ]}
                >
                  <Text style={styles.historyPickText} numberOfLines={1}>
                    {h}
                  </Text>
                </Pressable>
                <Pressable
                  onPress={() => void removeHistory(h)}
                  style={({ pressed, focused }) => [
                    styles.historyRemove,
                    (pressed || focused) && styles.historyRemoveFocus,
                  ]}
                  accessibilityLabel={`Remove ${h} from history`}
                >
                  <Text style={styles.historyRemoveText}>×</Text>
                </Pressable>
              </View>
            ))}
          </View>
        </View>
      ) : null}

      {showNoResults ? (
        <View style={styles.emptyPanel}>
          <Text style={styles.emptyText}>
            No results for &ldquo;{committedQuery}&rdquo;
          </Text>
        </View>
      ) : null}

      {!isLoading && (filter === 'all' || filter === 'live') && bucketed.live.length > 0 ? (
        <ResultRow
          title="Live TV"
          total={bucketed.live.length}
          items={bucketed.live.slice(0, ZONE_DISPLAY_CAP)}
          variant="hex"
          onPick={onItemPress}
        />
      ) : null}

      {!isLoading && (filter === 'all' || filter === 'movie') && bucketed.movies.length > 0 ? (
        <ResultRow
          title="Movies"
          total={bucketed.movies.length}
          items={bucketed.movies.slice(0, ZONE_DISPLAY_CAP)}
          variant="poster"
          onPick={onItemPress}
        />
      ) : null}

      {!isLoading && (filter === 'all' || filter === 'series') && bucketed.series.length > 0 ? (
        <ResultRow
          title="Series"
          total={bucketed.series.length}
          items={bucketed.series.slice(0, ZONE_DISPLAY_CAP)}
          variant="poster"
          onPick={onItemPress}
        />
      ) : null}
    </ScrollView>
  );
}

function ResultRow({
  title,
  total,
  items,
  variant,
  onPick,
}: {
  title: string;
  total: number;
  items: ContentItem[];
  variant: 'hex' | 'poster';
  onPick: (item: ContentItem) => void;
}) {
  const overflow = total - items.length;
  return (
    <View style={styles.rowSection}>
      <View style={styles.rowHeader}>
        <Text style={styles.sectionTitle}>{title}</Text>
        <Text style={styles.rowCount}>{total}</Text>
        {overflow > 0 ? (
          <Text style={styles.rowOverflow}>
            showing first {items.length}
          </Text>
        ) : null}
      </View>
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
              variant={variant}
              width={variant === 'hex' ? 130 : 120}
              onPress={() => onPick(item)}
            />
          </View>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingBottom: spacing.xxl,
  },
  inputWrap: {
    marginHorizontal: spacing.xl,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    backgroundColor: colors.surface800,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.glassBorderSoft,
    paddingHorizontal: 14,
  },
  input: {
    flex: 1,
    paddingVertical: 12,
    color: colors.white,
    fontSize: 15,
  },
  clearBtn: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: radii.sm,
  },
  clearBtnFocus: {
    backgroundColor: 'rgba(0, 255, 170, 0.1)',
  },
  clearBtnText: {
    color: colors.accent,
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  filterRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    paddingHorizontal: spacing.xl,
    marginTop: spacing.md,
  },
  chip: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: radii.pill,
    borderWidth: 1,
    borderColor: colors.surface700,
    backgroundColor: colors.surface800,
  },
  chipFocus: {
    borderColor: colors.surface500,
  },
  chipSelected: {
    borderColor: colors.accent,
    backgroundColor: 'rgba(0, 255, 170, 0.12)',
  },
  chipText: {
    color: colors.surface400,
    fontSize: 12,
    fontWeight: '700',
  },
  chipTextSelected: {
    color: colors.accent,
  },
  loadingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.xl,
    marginTop: spacing.lg,
  },
  loadingText: {
    color: colors.surface400,
    fontSize: 13,
  },
  emptyPanel: {
    marginHorizontal: spacing.xl,
    marginTop: spacing.xl,
    padding: spacing.xl,
    borderRadius: radii.lg,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
    backgroundColor: colors.surface900,
  },
  emptyText: {
    color: colors.surface400,
    fontSize: 14,
  },
  historySection: {
    marginHorizontal: spacing.xl,
    marginTop: spacing.xl,
  },
  historyHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.sm + 4,
  },
  clearAll: {
    color: colors.surface400,
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1,
  },
  historyRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  historyChip: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: radii.pill,
    borderWidth: 1,
    borderColor: colors.surface700,
    backgroundColor: colors.surface800,
    overflow: 'hidden',
  },
  historyPick: {
    paddingLeft: 14,
    paddingRight: 8,
    paddingVertical: 8,
  },
  historyPickFocus: {
    backgroundColor: 'rgba(0, 255, 170, 0.08)',
  },
  historyPickText: {
    color: colors.surface100,
    fontSize: 13,
    fontWeight: '600',
    maxWidth: 220,
  },
  historyRemove: {
    paddingHorizontal: 10,
    paddingVertical: 6,
  },
  historyRemoveFocus: {
    backgroundColor: 'rgba(255, 255, 255, 0.06)',
  },
  historyRemoveText: {
    color: colors.surface400,
    fontSize: 18,
    lineHeight: 18,
    fontWeight: '700',
  },
  rowSection: {
    marginTop: spacing.xl,
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
  rowOverflow: {
    color: colors.surface500,
    fontSize: 11,
    marginLeft: 4,
  },
  rowList: {
    paddingHorizontal: spacing.xl,
  },
});
