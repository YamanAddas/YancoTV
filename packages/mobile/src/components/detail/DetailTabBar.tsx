import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors, radii, spacing } from '../../styles/theme';

export interface TabDef<K extends string> {
  key: K;
  label: string;
}

interface Props<K extends string> {
  tabs: TabDef<K>[];
  active: K;
  onChange: (next: K) => void;
}

export function DetailTabBar<K extends string>({ tabs, active, onChange }: Props<K>) {
  return (
    <View style={styles.root}>
      {tabs.map((tab) => {
        const isActive = tab.key === active;
        return (
          <Pressable
            key={tab.key}
            onPress={() => onChange(tab.key)}
            style={({ pressed, focused }) => [
              styles.tab,
              isActive && styles.tabActive,
              focused && styles.tabFocused,
              pressed && { opacity: 0.85 },
            ]}
          >
            <Text
              style={[styles.label, isActive && styles.labelActive]}
              numberOfLines={1}
            >
              {tab.label}
            </Text>
            {isActive ? <View style={styles.indicator} /> : null}
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flexDirection: 'row',
    paddingHorizontal: spacing.xl,
    marginBottom: spacing.lg,
    gap: spacing.sm,
  },
  tab: {
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: radii.md,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
    minWidth: 96,
    alignItems: 'center',
  },
  tabActive: {
    backgroundColor: colors.surface800,
    borderColor: 'rgba(0, 255, 170, 0.25)',
  },
  tabFocused: {
    borderColor: colors.accent,
  },
  label: {
    color: colors.surface300,
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1.4,
    textTransform: 'uppercase',
  },
  labelActive: {
    color: colors.accent,
  },
  indicator: {
    position: 'absolute',
    bottom: -1,
    left: 12,
    right: 12,
    height: 2,
    backgroundColor: colors.accent,
    borderRadius: 1,
  },
});
