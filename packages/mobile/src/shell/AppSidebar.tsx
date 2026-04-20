import React, { useCallback } from 'react';
import {
  Image,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import Svg, { Path, Polygon } from 'react-native-svg';
import {
  isContentNavTarget,
  useShellStore,
  type NavTarget,
} from '../stores/shell-store';
import { useSourcesStore } from '../stores/sources-store';
import { colors, radii, spacing } from '../styles/theme';

// Metro's static asset loader resolves `require()` calls at bundle time.
// This is the only way to ship a bundled PNG on React Native — `import`
// from an asset file isn't typed without an ambient module declaration
// and doesn't materially differ at runtime.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const LOGO_SOURCE = require('../assets/yancotv_logo.png');

// AppSidebar — desktop Sidebar parity on mobile (M4R.D.1).
//
// Layout top → bottom:
//   • Hex-badge logo header
//   • Search button (opens SearchOverlay; full wire-up lands in M4R.9)
//   • Global nav (Home / Live TV / TV Guide / Movies / Series / Favorites /
//     Recordings / Downloads / Settings) — D-pad Down walks the full list
//   • Sources button pinned at the bottom
//
// Content-type nav items (Live TV / Movies / Series / Favorites) drive the
// existing ContentPanel; the other five land placeholder panels until their
// milestone ships (home = M4R.8 info/overview, guide = M6R, recordings /
// downloads / settings = M7R+).

interface NavItemDef {
  target: NavTarget;
  label: string;
  icon: IconName;
}

// SVG path data ported from src/renderer/components/Sidebar.tsx. Keeps the
// mobile sidebar visually aligned with the desktop set.
type IconName =
  | 'home'
  | 'tv'
  | 'guide'
  | 'film'
  | 'layers'
  | 'heart'
  | 'record'
  | 'download'
  | 'settings'
  | 'search'
  | 'sources';

const ICON_PATHS: Record<IconName, string> = {
  home:
    'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6',
  tv: 'M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
  guide:
    'M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z',
  film: 'M7 4v16M17 4v16M3 8h4m10 0h4M3 12h18M3 16h4m10 0h4M4 20h16a1 1 0 001-1V5a1 1 0 00-1-1H4a1 1 0 00-1 1v14a1 1 0 001 1z',
  layers:
    'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10',
  heart:
    'M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12z',
  record: 'M12 19a7 7 0 100-14 7 7 0 000 14zm0-3a4 4 0 110-8 4 4 0 010 8z',
  download: 'M12 4v12m0 0l-4-4m4 4l4-4M4 20h16',
  settings:
    'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z',
  search:
    'M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 15.803a7.5 7.5 0 0010.607 10.607z',
  sources:
    'M4 7h16M4 12h16M4 17h10',
};

const NAV_ITEMS: NavItemDef[] = [
  { target: 'home', label: 'Home', icon: 'home' },
  { target: 'live', label: 'Live TV', icon: 'tv' },
  { target: 'guide', label: 'TV Guide', icon: 'guide' },
  { target: 'movies', label: 'Movies', icon: 'film' },
  { target: 'series', label: 'Series', icon: 'layers' },
  { target: 'favorites', label: 'Favorites', icon: 'heart' },
  { target: 'recordings', label: 'Recordings', icon: 'record' },
  { target: 'downloads', label: 'Downloads', icon: 'download' },
  { target: 'settings', label: 'Settings', icon: 'settings' },
];

export function AppSidebar() {
  const navTarget = useShellStore((s) => s.navTarget);
  const setNavTarget = useShellStore((s) => s.setNavTarget);
  const openSources = useShellStore((s) => s.openSourcesModal);
  const openSearch = useShellStore((s) => s.openSearchOverlay);
  const sourceCount = useSourcesStore((s) => s.sources.length);

  if (!Platform.isTV) {
    return (
      <PhoneBar
        navTarget={navTarget}
        setNavTarget={setNavTarget}
        openSearch={openSearch}
        openSources={openSources}
        sourceCount={sourceCount}
      />
    );
  }

  return (
    <View style={styles.root}>
      <LogoBadge />
      <SearchButton onPress={openSearch} />
      <ScrollView
        style={styles.navScroll}
        contentContainerStyle={styles.navList}
        showsVerticalScrollIndicator={false}
      >
        {NAV_ITEMS.map((item) => (
          <NavRow
            key={item.target}
            item={item}
            active={item.target === navTarget}
            onPress={setNavTarget}
          />
        ))}
      </ScrollView>
      <SourcesButton count={sourceCount} onPress={openSources} />
    </View>
  );
}

// Phone variant — 56px horizontal bar. Search icon (left), horizontally
// scrollable chip row of the 9 nav items (active = cyan fill), sources icon
// (right). Logo wordmark is dropped on phone; the hex logo lives inside the
// search overlay header when that lands (M4R.9).
interface PhoneBarProps {
  navTarget: NavTarget;
  setNavTarget: (t: NavTarget) => void;
  openSearch: () => void;
  openSources: () => void;
  sourceCount: number;
}

function PhoneBar({
  navTarget,
  setNavTarget,
  openSearch,
  openSources,
  sourceCount,
}: PhoneBarProps) {
  return (
    <View style={styles.phoneRoot}>
      <Pressable
        onPress={openSearch}
        style={({ pressed }) => [
          styles.phoneIconButton,
          pressed && styles.phoneIconButtonPressed,
        ]}
        accessibilityLabel="Search"
      >
        <NavIcon name="search" color={colors.surface200} />
      </Pressable>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        style={styles.phoneChipsScroll}
        contentContainerStyle={styles.phoneChipsContent}
      >
        {NAV_ITEMS.map((item) => (
          <PhoneChip
            key={item.target}
            item={item}
            active={item.target === navTarget}
            onPress={setNavTarget}
          />
        ))}
      </ScrollView>
      <Pressable
        onPress={openSources}
        style={({ pressed }) => [
          styles.phoneIconButton,
          pressed && styles.phoneIconButtonPressed,
        ]}
        accessibilityLabel={`Sources (${sourceCount})`}
      >
        <NavIcon name="sources" color={colors.surface200} />
        {sourceCount > 0 && (
          <View style={styles.phoneBadge}>
            <Text style={styles.phoneBadgeText}>{sourceCount}</Text>
          </View>
        )}
      </Pressable>
    </View>
  );
}

interface PhoneChipProps {
  item: NavItemDef;
  active: boolean;
  onPress: (t: NavTarget) => void;
}

function PhoneChip({ item, active, onPress }: PhoneChipProps) {
  const handlePress = useCallback(() => onPress(item.target), [item.target, onPress]);
  const iconColor = active ? colors.bg : colors.surface200;
  return (
    <Pressable
      onPress={handlePress}
      style={[styles.phoneChip, active && styles.phoneChipActive]}
    >
      <NavIcon name={item.icon} color={iconColor} />
      <Text
        style={[styles.phoneChipLabel, active && styles.phoneChipLabelActive]}
        numberOfLines={1}
      >
        {item.label}
      </Text>
    </Pressable>
  );
}

function LogoBadge() {
  // Stroked flat-top hexagon frame wrapping the YancoTV mark. Outline-only
  // per M4R.D non-goals — no MaskedView, no child clipping.
  return (
    <View style={styles.logoWrap}>
      <View style={styles.logoBadge}>
        <Svg width="100%" height="100%" viewBox="0 0 100 100" style={StyleSheet.absoluteFill}>
          <Polygon
            points="25,4 75,4 96,50 75,96 25,96 4,50"
            fill="none"
            stroke={colors.accent}
            strokeWidth={3}
          />
        </Svg>
        <Image
          source={LOGO_SOURCE}
          style={styles.logoImage}
          resizeMode="contain"
        />
      </View>
      <Text style={styles.logoWordmark}>YancoTV</Text>
    </View>
  );
}

function SearchButton({ onPress }: { onPress: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      style={({ focused }) => [
        styles.searchRow,
        focused && styles.searchRowFocused,
      ]}
    >
      {({ focused }) => (
        <>
          <NavIcon name="search" color={focused ? colors.focus : colors.surface300} />
          <Text
            style={[styles.searchLabel, focused && styles.searchLabelFocused]}
          >
            Search
          </Text>
        </>
      )}
    </Pressable>
  );
}

interface NavRowProps {
  item: NavItemDef;
  active: boolean;
  onPress: (t: NavTarget) => void;
}

function NavRow({ item, active, onPress }: NavRowProps) {
  const handlePress = useCallback(() => onPress(item.target), [item.target, onPress]);
  const isContent = isContentNavTarget(item.target);
  return (
    <Pressable
      onPress={handlePress}
      style={({ focused }) => [
        styles.navRow,
        active && styles.navRowActive,
        focused && styles.navRowFocused,
      ]}
    >
      {({ focused }) => {
        const iconColor = focused
          ? colors.focus
          : active
            ? colors.accent
            : colors.surface400;
        const labelStyle = [
          styles.navLabel,
          active && styles.navLabelActive,
          focused && styles.navLabelFocused,
        ];
        return (
          <>
            <NavIcon name={item.icon} color={iconColor} />
            <Text style={labelStyle} numberOfLines={1}>
              {item.label}
            </Text>
            {!isContent && (
              <Text style={styles.navSoonBadge} numberOfLines={1}>
                Soon
              </Text>
            )}
          </>
        );
      }}
    </Pressable>
  );
}

function SourcesButton({
  count,
  onPress,
}: {
  count: number;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={({ focused }) => [
        styles.sourcesRow,
        focused && styles.sourcesRowFocused,
      ]}
    >
      {({ focused }) => (
        <>
          <NavIcon
            name="sources"
            color={focused ? colors.focus : colors.surface300}
          />
          <Text
            style={[
              styles.sourcesLabel,
              focused && styles.sourcesLabelFocused,
            ]}
          >
            Sources ({count})
          </Text>
        </>
      )}
    </Pressable>
  );
}

function NavIcon({ name, color }: { name: IconName; color: string }) {
  return (
    <Svg width={20} height={20} viewBox="0 0 24 24" fill="none">
      <Path
        d={ICON_PATHS[name]}
        stroke={color}
        strokeWidth={1.6}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Svg>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    paddingVertical: spacing.lg,
  },
  logoWrap: {
    alignItems: 'center',
    paddingHorizontal: spacing.md,
    marginBottom: spacing.lg,
  },
  logoBadge: {
    width: 64,
    height: 64,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: spacing.sm,
  },
  logoImage: {
    width: 40,
    height: 40,
  },
  logoWordmark: {
    color: colors.accent,
    fontSize: 16,
    fontWeight: '800',
    letterSpacing: 1.5,
  },
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: spacing.md,
    marginBottom: spacing.md,
    paddingVertical: spacing.sm + 2,
    paddingHorizontal: spacing.md,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.glassBorder,
    backgroundColor: colors.glassSubtle,
  },
  searchRowFocused: {
    borderColor: colors.focus,
    backgroundColor: colors.glass,
  },
  searchLabel: {
    color: colors.surface300,
    fontSize: 14,
    fontWeight: '600',
    marginLeft: spacing.sm,
  },
  searchLabelFocused: {
    color: colors.focus,
  },
  navScroll: {
    flex: 1,
  },
  navList: {
    paddingHorizontal: spacing.sm,
    paddingBottom: spacing.sm,
  },
  navRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: spacing.sm + 2,
    paddingHorizontal: spacing.md,
    marginBottom: 2,
    borderRadius: radii.md,
    borderLeftWidth: 3,
    borderLeftColor: 'transparent',
  },
  navRowActive: {
    backgroundColor: colors.glassSubtle,
    borderLeftColor: colors.accent,
  },
  navRowFocused: {
    backgroundColor: colors.glass,
    borderLeftColor: colors.focus,
  },
  navLabel: {
    flex: 1,
    color: colors.surface400,
    fontSize: 15,
    fontWeight: '600',
    marginLeft: spacing.md,
  },
  navLabelActive: {
    color: colors.accent,
  },
  navLabelFocused: {
    color: colors.focus,
  },
  navSoonBadge: {
    color: colors.surface500,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 0.5,
    marginLeft: spacing.sm,
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: radii.sm,
    backgroundColor: colors.surface800,
    textTransform: 'uppercase',
  },
  sourcesRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: spacing.md,
    marginTop: spacing.md,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.md,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.glassBorder,
  },
  sourcesRowFocused: {
    borderColor: colors.focus,
    backgroundColor: colors.glass,
  },
  sourcesLabel: {
    color: colors.surface300,
    fontSize: 13,
    fontWeight: '700',
    letterSpacing: 0.5,
    marginLeft: spacing.sm,
  },
  sourcesLabelFocused: {
    color: colors.focus,
  },

  // Phone variant — 56px tall horizontal bar.
  phoneRoot: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.sm,
  },
  phoneIconButton: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radii.md,
  },
  phoneIconButtonPressed: {
    backgroundColor: colors.glass,
  },
  phoneChipsScroll: {
    flex: 1,
  },
  phoneChipsContent: {
    alignItems: 'center',
    paddingHorizontal: spacing.sm,
  },
  phoneChip: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.md,
    paddingVertical: 6,
    marginRight: spacing.sm,
    borderRadius: radii.pill,
    borderWidth: 1,
    borderColor: colors.glassBorder,
    backgroundColor: colors.surface900,
  },
  phoneChipActive: {
    backgroundColor: colors.accent,
    borderColor: colors.accent,
  },
  phoneChipLabel: {
    color: colors.surface200,
    fontSize: 13,
    fontWeight: '700',
    marginLeft: 6,
  },
  phoneChipLabelActive: {
    color: colors.bg,
  },
  phoneBadge: {
    position: 'absolute',
    top: 4,
    right: 2,
    minWidth: 14,
    height: 14,
    paddingHorizontal: 3,
    borderRadius: 7,
    backgroundColor: colors.accent,
    alignItems: 'center',
    justifyContent: 'center',
  },
  phoneBadgeText: {
    color: colors.bg,
    fontSize: 9,
    fontWeight: '800',
  },
});
