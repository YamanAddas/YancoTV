import React, { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Easing,
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from 'react-native';
import { useNavStore, type Screen } from '../../stores/nav-store';
import { useSourcesStore } from '../../stores/sources-store';
import { colors, glow, radii, sidebar, spacing } from '../../styles/theme';

interface NavItem {
  screen: Screen;
  label: string;
  icon: string;
}

// Emoji/symbol glyphs standing in for the desktop Lucide icons. Readable
// without needing to bundle an icon font.
const NAV_ITEMS: NavItem[] = [
  { screen: 'home', label: 'Home', icon: '⌂' },
  { screen: 'live', label: 'Live TV', icon: '●' },
  { screen: 'movies', label: 'Movies', icon: '▶' },
  { screen: 'series', label: 'Series', icon: '▤' },
  { screen: 'sources', label: 'Sources', icon: '⚙' },
];

const EXPANDED = sidebar.width;
const COLLAPSED = sidebar.widthCollapsed;

export function Sidebar() {
  const current = useNavStore((s) => s.screen);
  const navigate = useNavStore((s) => s.navigate);
  const sourceCount = useSourcesStore((s) => s.sources.length);
  const channelCount = useSourcesStore((s) => s.channels.length);

  // Narrow phones default to collapsed so the grid gets room to breathe.
  // Tablets / Android TV default expanded.
  const { width: screenW } = useWindowDimensions();
  const [expanded, setExpanded] = useState(screenW >= 720);
  const widthAnim = useRef(
    new Animated.Value(screenW >= 720 ? EXPANDED : COLLAPSED),
  ).current;
  const fadeAnim = useRef(new Animated.Value(screenW >= 720 ? 1 : 0)).current;

  useEffect(() => {
    // Spring-like feel via timing with custom easing; parallel width + label fade.
    Animated.parallel([
      Animated.timing(widthAnim, {
        toValue: expanded ? EXPANDED : COLLAPSED,
        duration: 260,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: false,
      }),
      Animated.timing(fadeAnim, {
        toValue: expanded ? 1 : 0,
        duration: expanded ? 220 : 120,
        delay: expanded ? 120 : 0,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: false,
      }),
    ]).start();
  }, [expanded, widthAnim, fadeAnim]);

  return (
    <Animated.View style={[styles.root, { width: widthAnim }]}>
      {/* Toggle / hamburger */}
      <View style={styles.headerRow}>
        <Pressable
          onPress={() => setExpanded((e) => !e)}
          style={({ pressed, focused }) => [
            styles.toggle,
            (pressed || focused) && styles.toggleActive,
          ]}
          hitSlop={8}
        >
          <View style={styles.bar} />
          <View style={styles.bar} />
          <View style={styles.bar} />
        </Pressable>
      </View>

      {/* Logo — only when expanded */}
      <Animated.View
        style={[styles.logoWrap, { opacity: fadeAnim }]}
        pointerEvents={expanded ? 'auto' : 'none'}
      >
        <Image
          source={require('../../assets/yancotv_logo.png')}
          style={styles.logo}
          resizeMode="contain"
        />
      </Animated.View>

      {/* Nav */}
      <View style={styles.nav}>
        {NAV_ITEMS.map((item) => {
          const active = current === item.screen;
          return (
            <Pressable
              key={item.screen}
              onPress={() => navigate(item.screen)}
              style={({ pressed, focused }) => [
                styles.item,
                !expanded && styles.itemCollapsed,
                active && styles.itemActive,
                (pressed || focused) && !active && styles.itemFocus,
              ]}
            >
              <Text style={[styles.icon, active && styles.iconActive]}>
                {item.icon}
              </Text>
              {expanded ? (
                <Animated.Text
                  style={[
                    styles.label,
                    active && styles.labelActive,
                    { opacity: fadeAnim },
                  ]}
                  numberOfLines={1}
                >
                  {item.label}
                </Animated.Text>
              ) : null}
              {active ? <View style={styles.activeBar} /> : null}
            </Pressable>
          );
        })}
      </View>

      {/* Footer */}
      <View style={styles.footer}>
        {expanded ? (
          <Animated.View style={{ opacity: fadeAnim }}>
            <Text style={styles.stat}>
              {sourceCount} {sourceCount === 1 ? 'source' : 'sources'}
            </Text>
            <Text style={styles.statDim}>
              {channelCount.toLocaleString()} items
            </Text>
          </Animated.View>
        ) : (
          <Text style={styles.statCollapsed}>
            {sourceCount}
          </Text>
        )}
      </View>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  root: {
    backgroundColor: colors.glassStrong,
    borderRightWidth: 1,
    borderRightColor: colors.glassBorder,
    paddingVertical: spacing.sm,
    overflow: 'hidden',
  },
  headerRow: {
    height: 44,
    paddingHorizontal: 14,
    justifyContent: 'center',
  },
  toggle: {
    width: 28,
    height: 28,
    justifyContent: 'center',
    gap: 4,
  },
  toggleActive: {
    opacity: 0.8,
  },
  bar: {
    height: 2,
    width: 20,
    borderRadius: 1,
    backgroundColor: colors.surface300,
  },
  logoWrap: {
    paddingHorizontal: spacing.sm,
    paddingBottom: spacing.md,
    height: 54,
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
  itemCollapsed: {
    justifyContent: 'center',
    paddingHorizontal: 0,
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
  statCollapsed: {
    color: colors.surface500,
    fontSize: 10,
    fontWeight: '700',
    textAlign: 'center',
  },
});
