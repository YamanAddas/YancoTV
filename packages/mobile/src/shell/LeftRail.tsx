import React, { useCallback } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  categoryKey,
  useShellStore,
  type RailCategory,
} from '../stores/shell-store';
import { colors, spacing } from '../styles/theme';

// Flat category list: Live / Movies / Series / Favorites.
// Groups (per-type) land in M5R.1 under each type row.
// Focus memory + restoration lands in M4R.10 via the new Focusable primitive.

interface RailItem {
  category: RailCategory;
  label: string;
}

const ITEMS: RailItem[] = [
  { category: { kind: 'type', type: 'live' }, label: 'Live TV' },
  { category: { kind: 'type', type: 'movie' }, label: 'Movies' },
  { category: { kind: 'type', type: 'series' }, label: 'Series' },
  { category: { kind: 'favorites' }, label: 'Favorites' },
];

export function LeftRail() {
  const activeKey = useShellStore((s) => categoryKey(s.category));
  const setCategory = useShellStore((s) => s.setCategory);

  return (
    <View style={styles.root}>
      <Text style={styles.title}>YancoTV</Text>
      <View style={styles.list}>
        {ITEMS.map((item) => (
          <RailRow
            key={categoryKey(item.category)}
            item={item}
            active={categoryKey(item.category) === activeKey}
            onPress={setCategory}
          />
        ))}
      </View>
    </View>
  );
}

interface RowProps {
  item: RailItem;
  active: boolean;
  onPress: (c: RailCategory) => void;
}

function RailRow({ item, active, onPress }: RowProps) {
  const handlePress = useCallback(() => onPress(item.category), [item, onPress]);
  return (
    <Pressable
      onPress={handlePress}
      style={({ focused }) => [
        styles.row,
        active && styles.rowActive,
        focused && styles.rowFocused,
      ]}
    >
      {({ focused }) => (
        <Text
          style={[
            styles.rowLabel,
            active && styles.rowLabelActive,
            focused && styles.rowLabelFocused,
          ]}
        >
          {item.label}
        </Text>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    paddingVertical: spacing.lg,
  },
  title: {
    color: colors.accent,
    fontSize: 20,
    fontWeight: '800',
    letterSpacing: 1,
    paddingHorizontal: spacing.lg,
    marginBottom: spacing.lg,
  },
  list: {
    flex: 1,
  },
  row: {
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    borderLeftWidth: 3,
    borderLeftColor: 'transparent',
  },
  rowActive: {
    borderLeftColor: colors.accent,
    backgroundColor: colors.glassSubtle,
  },
  rowFocused: {
    backgroundColor: colors.glass,
    borderLeftColor: colors.focus,
  },
  rowLabel: {
    color: colors.surface200,
    fontSize: 16,
    fontWeight: '600',
  },
  rowLabelActive: {
    color: colors.white,
  },
  rowLabelFocused: {
    color: colors.focus,
  },
});
