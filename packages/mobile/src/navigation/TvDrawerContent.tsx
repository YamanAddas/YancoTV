import React from 'react';
import {
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import type { DrawerContentComponentProps } from '@react-navigation/drawer';
import Svg, { Circle, Path, Polygon, Rect } from 'react-native-svg';
import { useSourcesStore } from '../stores/sources-store';
import { colors, glow, radii, sidebar, spacing } from '../styles/theme';
import type { MainTabsParamList } from './RootNavigator';
// Metro resolves `require()` for asset URIs — ES6 imports don't work for
// bundler-handled images. Lint rule carved out here only.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const LOGO = require('../assets/yancotv_logo.png');

type NavRoute = keyof MainTabsParamList;

interface NavItem {
  route: NavRoute;
  label: string;
}

const NAV_ITEMS: NavItem[] = [
  { route: 'Home', label: 'Home' },
  { route: 'Live', label: 'Live TV' },
  { route: 'Movies', label: 'Movies' },
  { route: 'Series', label: 'Series' },
  { route: 'Sources', label: 'Sources' },
];

// MB-10: SVG icons replace Unicode glyphs. Hand-rolled inline so we don't pull
// an icon-pack dep for five shapes — keeps APK lean and lets the active colour
// flow straight from the theme.
function NavIcon({ route, color }: { route: NavRoute; color: string }) {
  const size = 18;
  const stroke = 1.8;
  switch (route) {
    case 'Home':
      return (
        <Svg width={size} height={size} viewBox="0 0 24 24">
          <Path
            d="M3 11l9-7 9 7v9a1 1 0 01-1 1h-5v-7H10v7H4a1 1 0 01-1-1z"
            fill="none"
            stroke={color}
            strokeWidth={stroke}
            strokeLinejoin="round"
          />
        </Svg>
      );
    case 'Live':
      return (
        <Svg width={size} height={size} viewBox="0 0 24 24">
          <Rect
            x={2.5}
            y={5}
            width={19}
            height={13}
            rx={2}
            fill="none"
            stroke={color}
            strokeWidth={stroke}
          />
          <Path
            d="M8 21h8M12 18v3"
            stroke={color}
            strokeWidth={stroke}
            strokeLinecap="round"
          />
        </Svg>
      );
    case 'Movies':
      return (
        <Svg width={size} height={size} viewBox="0 0 24 24">
          <Polygon
            points="7,4 20,12 7,20"
            fill="none"
            stroke={color}
            strokeWidth={stroke}
            strokeLinejoin="round"
          />
        </Svg>
      );
    case 'Series':
      return (
        <Svg width={size} height={size} viewBox="0 0 24 24">
          <Rect x={3} y={4} width={8} height={7} rx={1.5} fill="none" stroke={color} strokeWidth={stroke} />
          <Rect x={13} y={4} width={8} height={7} rx={1.5} fill="none" stroke={color} strokeWidth={stroke} />
          <Rect x={3} y={13} width={8} height={7} rx={1.5} fill="none" stroke={color} strokeWidth={stroke} />
          <Rect x={13} y={13} width={8} height={7} rx={1.5} fill="none" stroke={color} strokeWidth={stroke} />
        </Svg>
      );
    case 'Sources':
      return (
        <Svg width={size} height={size} viewBox="0 0 24 24">
          <Circle cx={12} cy={12} r={3} fill="none" stroke={color} strokeWidth={stroke} />
          <Path
            d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1"
            stroke={color}
            strokeWidth={stroke}
            strokeLinecap="round"
          />
        </Svg>
      );
  }
}

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
              <View style={styles.icon}>
                <NavIcon
                  route={item.route}
                  color={active ? colors.accent : colors.surface400}
                />
              </View>
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
    width: 22,
    alignItems: 'center',
    justifyContent: 'center',
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
