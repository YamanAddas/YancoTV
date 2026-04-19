import React from 'react';
import {
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import type { DrawerContentComponentProps } from '@react-navigation/drawer';
import { useSourcesStore } from '../stores/sources-store';
import { colors, glow, radii, sidebar, spacing } from '../styles/theme';
import type { MainTabsParamList } from './RootNavigator';
// Metro resolves `require()` for asset URIs — ES6 imports don't work for
// bundler-handled images. Lint rule carved out here only.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const LOGO = require('../assets/yancotv_logo.png');

interface NavItem {
  route: keyof MainTabsParamList;
  label: string;
  icon: string;
}

// Unicode geometric glyphs — not emoji. MB-10 tracks replacing these with SVG
// icons project-wide.
const NAV_ITEMS: NavItem[] = [
  { route: 'Home', label: 'Home', icon: '\u2302' },
  { route: 'Live', label: 'Live TV', icon: '\u25CF' },
  { route: 'Movies', label: 'Movies', icon: '\u25B6' },
  { route: 'Series', label: 'Series', icon: '\u25A4' },
  { route: 'Sources', label: 'Sources', icon: '\u2699' },
];

/**
 * Drawer content for TV — renders the YancoTV sidebar and dispatches to
 * drawer routes via react-navigation. The drawer is configured as permanent,
 * so there is no open/close interaction to manage here.
 */
export function TvDrawerContent(props: DrawerContentComponentProps) {
  const { navigation, state } = props;
  const sourceCount = useSourcesStore((s) => s.sources.length);
  const channelCount = useSourcesStore((s) => s.channels.length);

  const currentRouteName = state.routeNames[state.index] as
    | keyof MainTabsParamList
    | undefined;

  return (
    <View style={styles.root}>
      <View style={styles.logoWrap}>
        <Image source={LOGO} style={styles.logo} resizeMode="contain" />
      </View>

      <View style={styles.nav}>
        {NAV_ITEMS.map((item) => {
          const active = currentRouteName === item.route;
          return (
            <Pressable
              key={item.route}
              onPress={() => navigation.navigate(item.route)}
              style={({ pressed, focused }) => [
                styles.item,
                active && styles.itemActive,
                (pressed || focused) && !active && styles.itemFocus,
              ]}
            >
              <Text style={[styles.icon, active && styles.iconActive]}>
                {item.icon}
              </Text>
              <Text
                style={[styles.label, active && styles.labelActive]}
                numberOfLines={1}
              >
                {item.label}
              </Text>
              {active ? <View style={styles.activeBar} /> : null}
            </Pressable>
          );
        })}
      </View>

      <View style={styles.footer}>
        <Text style={styles.stat}>
          {sourceCount} {sourceCount === 1 ? 'source' : 'sources'}
        </Text>
        <Text style={styles.statDim}>
          {channelCount.toLocaleString()} items
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    width: sidebar.width,
    backgroundColor: colors.glassStrong,
    borderRightWidth: 1,
    borderRightColor: colors.glassBorder,
    paddingVertical: spacing.sm,
  },
  logoWrap: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.md,
    height: 70,
    justifyContent: 'center',
  },
  logo: {
    width: '100%',
    height: '100%',
  },
  nav: {
    flex: 1,
    paddingHorizontal: spacing.sm,
    gap: 4,
  },
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderRadius: radii.md,
    position: 'relative',
  },
  itemActive: {
    backgroundColor: 'rgba(0, 255, 170, 0.10)',
    ...glow.sm,
  },
  itemFocus: {
    backgroundColor: 'rgba(255, 255, 255, 0.04)',
  },
  icon: {
    color: colors.surface400,
    fontSize: 16,
    fontWeight: '700',
    width: 22,
    textAlign: 'center',
  },
  iconActive: {
    color: colors.accent,
  },
  label: {
    marginLeft: 10,
    color: colors.surface200,
    fontSize: 14,
    fontWeight: '600',
    flex: 1,
  },
  labelActive: {
    color: colors.accent,
  },
  activeBar: {
    position: 'absolute',
    left: 0,
    top: 8,
    bottom: 8,
    width: 3,
    backgroundColor: colors.accent,
    borderRadius: 2,
  },
  footer: {
    paddingHorizontal: 14,
    paddingTop: spacing.md,
    borderTopWidth: 1,
    borderTopColor: 'rgba(0, 255, 170, 0.08)',
  },
  stat: {
    color: colors.surface300,
    fontSize: 13,
    fontWeight: '600',
  },
  statDim: {
    marginTop: 2,
    color: colors.surface500,
    fontSize: 11,
  },
});
