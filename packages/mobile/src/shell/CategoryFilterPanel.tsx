import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Modal,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { FlashList } from '@shopify/flash-list';
import Svg, { Path } from 'react-native-svg';
import type { ContentType } from '@yancotv/core';
import { groupsForType, countByType, type GroupCount } from '../db/queries';
import { useSourcesStore } from '../stores/sources-store';
import {
  useShellStore,
  type NavTarget,
  type RailCategory,
} from '../stores/shell-store';
import { colors, radii, spacing } from '../styles/theme';

// CategoryFilterPanel — middle column between AppSidebar and ContentPanel
// (M4R.D.2). TV renders it inline; phone pops it as a drawer from the
// ContentPanel header's filter chevron.
//
// Only content-type nav targets (live / movies / series) expose groups.
// Favorites has no group concept; non-content targets render placeholder
// panels and never mount this component.

export const FILTER_PANEL_WIDTH = 260;

function typeForNavTarget(t: NavTarget): ContentType | null {
  if (t === 'live') return 'live';
  if (t === 'movies') return 'movie';
  if (t === 'series') return 'series';
  return null;
}

interface GroupListState {
  groups: GroupCount[];
  total: number;
  loading: boolean;
  error: string | null;
}

function useGroupList(type: ContentType | null): GroupListState {
  const syncStatus = useSourcesStore((s) => s.syncStatus);
  const [state, setState] = useState<GroupListState>({
    groups: [],
    total: 0,
    loading: false,
    error: null,
  });
  const token = useRef(0);

  useEffect(() => {
    if (!type) {
      setState({ groups: [], total: 0, loading: false, error: null });
      return;
    }
    const t = ++token.current;
    setState((prev) => ({ ...prev, loading: true, error: null }));
    Promise.all([groupsForType(type), countByType({ type })])
      .then(([groups, total]) => {
        if (t !== token.current) return;
        setState({ groups, total, loading: false, error: null });
      })
      .catch((e: unknown) => {
        if (t !== token.current) return;
        setState({
          groups: [],
          total: 0,
          loading: false,
          error: e instanceof Error ? e.message : String(e),
        });
      });
  }, [type, syncStatus]);

  return state;
}

export function CategoryFilterPanel() {
  const navTarget = useShellStore((s) => s.navTarget);
  const category = useShellStore((s) => s.category);
  const setCategory = useShellStore((s) => s.setCategory);
  const type = typeForNavTarget(navTarget);

  return (
    <FilterPanelBody
      type={type}
      category={category}
      setCategory={setCategory}
    />
  );
}

// Phone drawer variant — full-screen modal sliding in from the left edge.
// Triggered from the ContentPanel header's filter chevron.
export function CategoryFilterDrawer() {
  const open = useShellStore((s) => s.filterDrawerOpen);
  const close = useShellStore((s) => s.closeFilterDrawer);
  const navTarget = useShellStore((s) => s.navTarget);
  const category = useShellStore((s) => s.category);
  const setCategory = useShellStore((s) => s.setCategory);
  const type = typeForNavTarget(navTarget);

  const handleSelect = useCallback(
    (c: RailCategory) => {
      setCategory(c);
      close();
    },
    [setCategory, close],
  );

  return (
    <Modal
      visible={open}
      animationType="slide"
      transparent
      onRequestClose={close}
    >
      <View style={styles.drawerBackdrop}>
        <View style={styles.drawerSheet}>
          <View style={styles.drawerHeader}>
            <Text style={styles.drawerTitle}>Filter groups</Text>
            <Pressable
              onPress={close}
              style={({ pressed }) => [
                styles.drawerClose,
                pressed && styles.drawerClosePressed,
              ]}
              accessibilityLabel="Close filter"
            >
              <CloseIcon />
            </Pressable>
          </View>
          <FilterPanelBody
            type={type}
            category={category}
            setCategory={handleSelect}
          />
        </View>
        <Pressable style={styles.drawerDismiss} onPress={close} />
      </View>
    </Modal>
  );
}

interface FilterPanelBodyProps {
  type: ContentType | null;
  category: RailCategory;
  setCategory: (c: RailCategory) => void;
}

function FilterPanelBody({ type, category, setCategory }: FilterPanelBodyProps) {
  const { groups, total, loading, error } = useGroupList(type);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    setFilter('');
  }, [type]);

  const filtered = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) return groups;
    return groups.filter((g) => g.name.toLowerCase().includes(needle));
  }, [groups, filter]);

  const allActive =
    !!type && category.kind === 'type' && category.type === type;
  const activeGroupName =
    type && category.kind === 'group' && category.type === type
      ? category.groupName
      : null;

  // Virtualize via FlashList — providers commonly ship 500+ groups; a
  // ScrollView that mounts every row simultaneously was a primary driver
  // of the 2026-04-19 view-count audit (5.5k idle views). The "All" pinned
  // row, error line, and empty-state copy go through ListHeaderComponent.
  const renderGroup = useCallback(
    ({ item }: { item: GroupCount }) => (
      <GroupRow
        label={item.name}
        count={item.count}
        active={activeGroupName === item.name}
        onPress={() => {
          if (!type) return;
          setCategory({ kind: 'group', type, groupName: item.name });
        }}
      />
    ),
    [activeGroupName, setCategory, type],
  );

  if (!type) {
    return (
      <View style={styles.panel}>
        <View style={styles.empty}>
          <Text style={styles.emptyText}>No groups available.</Text>
        </View>
      </View>
    );
  }

  const header = (
    <>
      <GroupRow
        label="All"
        count={total}
        active={allActive}
        onPress={() => setCategory({ kind: 'type', type })}
        pinned
      />
      {error && (
        <Text style={styles.errorText} numberOfLines={2}>
          {error}
        </Text>
      )}
      {!loading && filtered.length === 0 && groups.length > 0 && (
        <Text style={styles.emptyHint}>No groups match.</Text>
      )}
      {!loading && groups.length === 0 && !error && (
        <Text style={styles.emptyHint}>No groups yet.</Text>
      )}
    </>
  );

  return (
    <View style={styles.panel}>
      <View style={styles.searchWrap}>
        <TextInput
          style={styles.searchInput}
          placeholder="Filter groups"
          placeholderTextColor={colors.surface400}
          value={filter}
          onChangeText={setFilter}
          autoCorrect={false}
          autoCapitalize="none"
        />
      </View>
      <View style={styles.listScroll}>
        <FlashList
          data={filtered}
          keyExtractor={(g) => g.name}
          renderItem={renderGroup}
          estimatedItemSize={36}
          ListHeaderComponent={header}
          contentContainerStyle={styles.listContent}
          showsVerticalScrollIndicator={false}
          drawDistance={400}
        />
      </View>
    </View>
  );
}

interface GroupRowProps {
  label: string;
  count: number;
  active: boolean;
  onPress: () => void;
  pinned?: boolean;
}

function GroupRow({ label, count, active, onPress, pinned }: GroupRowProps) {
  return (
    <Pressable
      onPress={onPress}
      style={({ focused, pressed }) => [
        styles.row,
        pinned && styles.rowPinned,
        active && styles.rowActive,
        focused && styles.rowFocused,
        pressed && !Platform.isTV && styles.rowPressed,
      ]}
    >
      {({ focused }) => (
        <>
          <Text
            style={[
              styles.rowLabel,
              active && styles.rowLabelActive,
              focused && styles.rowLabelFocused,
            ]}
            numberOfLines={1}
          >
            {label}
          </Text>
          <Text
            style={[
              styles.rowCount,
              active && styles.rowCountActive,
              focused && styles.rowCountFocused,
            ]}
          >
            {count}
          </Text>
        </>
      )}
    </Pressable>
  );
}

function CloseIcon() {
  return (
    <Svg width={20} height={20} viewBox="0 0 24 24" fill="none">
      <Path
        d="M6 6l12 12M18 6L6 18"
        stroke={colors.surface200}
        strokeWidth={1.8}
        strokeLinecap="round"
      />
    </Svg>
  );
}

const styles = StyleSheet.create({
  panel: {
    flex: 1,
    backgroundColor: colors.surface900,
    borderRightWidth: 1,
    borderRightColor: colors.glassBorder,
  },
  searchWrap: {
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
    paddingBottom: spacing.sm,
  },
  searchInput: {
    height: 36,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.glassBorder,
    backgroundColor: colors.glassSubtle,
    paddingHorizontal: spacing.md,
    color: colors.white,
    fontSize: 13,
  },
  listScroll: {
    flex: 1,
  },
  listContent: {
    paddingHorizontal: spacing.sm,
    paddingBottom: spacing.md,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 8,
    paddingHorizontal: spacing.md,
    marginBottom: 2,
    borderRadius: radii.md,
    borderLeftWidth: 3,
    borderLeftColor: 'transparent',
  },
  rowPinned: {
    marginBottom: spacing.xs,
  },
  rowActive: {
    backgroundColor: colors.glassSubtle,
    borderLeftColor: colors.accent,
  },
  rowFocused: {
    backgroundColor: colors.glass,
    borderLeftColor: colors.focus,
  },
  rowPressed: {
    backgroundColor: colors.glass,
  },
  rowLabel: {
    flex: 1,
    color: colors.surface300,
    fontSize: 13,
    fontWeight: '600',
    marginRight: spacing.sm,
  },
  rowLabelActive: {
    color: colors.accent,
  },
  rowLabelFocused: {
    color: colors.focus,
  },
  rowCount: {
    color: colors.surface500,
    fontSize: 11,
    fontWeight: '700',
  },
  rowCountActive: {
    color: colors.accent,
  },
  rowCountFocused: {
    color: colors.focus,
  },
  empty: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.lg,
  },
  emptyText: {
    color: colors.surface400,
    fontSize: 13,
    textAlign: 'center',
  },
  emptyHint: {
    color: colors.surface500,
    fontSize: 12,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  errorText: {
    color: colors.red400,
    fontSize: 12,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },

  // Phone drawer
  drawerBackdrop: {
    flex: 1,
    flexDirection: 'row',
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  drawerSheet: {
    width: 300,
    maxWidth: '85%',
    backgroundColor: colors.surface900,
    borderRightWidth: 1,
    borderRightColor: colors.glassBorder,
  },
  drawerDismiss: {
    flex: 1,
  },
  drawerHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.md,
    paddingTop: spacing.lg,
    paddingBottom: spacing.sm,
  },
  drawerTitle: {
    color: colors.white,
    fontSize: 16,
    fontWeight: '800',
  },
  drawerClose: {
    width: 36,
    height: 36,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radii.md,
  },
  drawerClosePressed: {
    backgroundColor: colors.glass,
  },
});
