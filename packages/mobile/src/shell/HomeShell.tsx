import React, { useEffect } from 'react';
import {
  BackHandler,
  Platform,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
// PlayerSurface + ControlsOverlay deleted — playback runs in a native
// Android PlayerActivity (TiviMate-style). RN shell never mounts <Video>.
import Svg, { Path } from 'react-native-svg';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppSidebar } from './AppSidebar';
import {
  CategoryFilterDrawer,
  CategoryFilterPanel,
  FILTER_PANEL_WIDTH,
} from './CategoryFilterPanel';
import { ContentPanel } from './ContentPanel';
import { MiniPlayer } from './MiniPlayer';
import { SourcesModal } from './SourcesModal';
import { usePlayerStore } from '../stores/player-store';
import {
  isContentNavTarget,
  useShellStore,
  type NavTarget,
} from '../stores/shell-store';
import { colors, radii, spacing } from '../styles/theme';

// One screen, state-driven. Playback is a native Android Activity — the
// shell never renders <Video>. Back-peel collapses the UI layers only.
export function HomeShell() {
  const track = usePlayerStore((s) => s.track);
  const stop = usePlayerStore((s) => s.stop);
  const navTarget = useShellStore((s) => s.navTarget);
  const sidebarCollapsed = useShellStore((s) => s.sidebarCollapsed);
  const filterCollapsed = useShellStore((s) => s.filterCollapsed);
  const setSidebarCollapsed = useShellStore((s) => s.setSidebarCollapsed);
  const setFilterCollapsed = useShellStore((s) => s.setFilterCollapsed);
  const sourcesModalOpen = useShellStore((s) => s.sourcesModalOpen);
  const searchOverlayOpen = useShellStore((s) => s.searchOverlayOpen);
  const insets = useSafeAreaInsets();
  const outerPad = Platform.isTV
    ? null
    : { paddingTop: insets.top, paddingBottom: insets.bottom };
  const showFilter = hasGroupFilter(navTarget);

  // Back press peels layers: filter → collapse, sidebar → collapse, else exit app.
  useEffect(() => {
    if (!Platform.isTV) return;
    const onBack = () => {
      if (sourcesModalOpen || searchOverlayOpen) return false;
      if (showFilter && !filterCollapsed) {
        setFilterCollapsed(true);
        return true;
      }
      if (!sidebarCollapsed) {
        setSidebarCollapsed(true);
        return true;
      }
      return false;
    };
    const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
    return () => sub.remove();
  }, [
    sourcesModalOpen,
    searchOverlayOpen,
    showFilter,
    filterCollapsed,
    sidebarCollapsed,
    setFilterCollapsed,
    setSidebarCollapsed,
  ]);

  // Phone Android back: clear last track if present, else default.
  useEffect(() => {
    if (Platform.isTV) return;
    const onBack = () => {
      if (sourcesModalOpen || searchOverlayOpen) return false;
      if (track) {
        stop();
        return true;
      }
      return false;
    };
    const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
    return () => sub.remove();
  }, [sourcesModalOpen, searchOverlayOpen, track, stop]);

  return (
    <View style={[styles.outer, outerPad]}>
      {!Platform.isTV && (
        <StatusBar
          translucent
          backgroundColor="transparent"
          barStyle="light-content"
        />
      )}
      {Platform.isTV ? (
        <TvLayout
          hasTrack={track !== null}
          showFilter={showFilter}
          sidebarCollapsed={sidebarCollapsed}
          filterCollapsed={filterCollapsed}
        />
      ) : (
        <PhoneLayout hasTrack={track !== null} />
      )}
      <SourcesModal />
      {!Platform.isTV && <CategoryFilterDrawer />}
    </View>
  );
}

function hasGroupFilter(navTarget: NavTarget): boolean {
  return navTarget === 'live' || navTarget === 'movies' || navTarget === 'series';
}

function TvLayout({
  hasTrack,
  showFilter,
  sidebarCollapsed,
  filterCollapsed,
}: {
  hasTrack: boolean;
  showFilter: boolean;
  sidebarCollapsed: boolean;
  filterCollapsed: boolean;
}) {
  const toggleSidebar = useShellStore((s) => s.toggleSidebar);
  const toggleFilter = useShellStore((s) => s.toggleFilter);
  const filterVisible = showFilter && !filterCollapsed;
  return (
    <View style={styles.tvRoot}>
      {!sidebarCollapsed && (
        <View style={styles.railSlot}>
          <AppSidebar />
        </View>
      )}
      {filterVisible && (
        <View style={styles.filterSlot}>
          <CategoryFilterPanel />
        </View>
      )}
      <View style={styles.contentSlot}>
        <MainPanel />
        {sidebarCollapsed && (
          <EdgeExpandButton
            side="menu"
            onPress={toggleSidebar}
            accessibilityLabel="Show menu"
          />
        )}
        {!sidebarCollapsed && showFilter && filterCollapsed && (
          <EdgeExpandButton
            side="filter"
            onPress={toggleFilter}
            accessibilityLabel="Show groups"
          />
        )}
      </View>
      {hasTrack && (
        <View style={styles.rightColumn}>
          <View style={styles.playerSlot}>
            <MiniPlayer />
          </View>
          <View style={styles.infoSlot}>
            <PlaceholderLabel text="InfoPanel · M4R.8" />
          </View>
        </View>
      )}
    </View>
  );
}

function EdgeExpandButton({
  side,
  onPress,
  accessibilityLabel,
}: {
  side: 'menu' | 'filter';
  onPress: () => void;
  accessibilityLabel: string;
}) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityLabel={accessibilityLabel}
      style={({ focused, pressed }) => [
        styles.edgeBtn,
        side === 'filter' && styles.edgeBtnFilter,
        focused && styles.edgeBtnFocused,
        pressed && styles.edgeBtnPressed,
      ]}
    >
      <Svg width={14} height={20} viewBox="0 0 14 20" fill="none">
        <Path
          d="M4 4l6 6-6 6"
          stroke={colors.surface200}
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </Svg>
    </Pressable>
  );
}

function PhoneLayout({ hasTrack }: { hasTrack: boolean }) {
  return (
    <View style={styles.phoneRoot}>
      <View style={styles.phoneRail}>
        <AppSidebar />
      </View>
      <View style={styles.phoneContent}>
        <MainPanel />
      </View>
      {hasTrack && (
        <View style={styles.phonePlayerSlot}>
          <MiniPlayer />
        </View>
      )}
    </View>
  );
}

function MainPanel() {
  const navTarget = useShellStore((s) => s.navTarget);
  if (isContentNavTarget(navTarget)) return <ContentPanel />;
  return <NavPlaceholder target={navTarget} />;
}

function NavPlaceholder({ target }: { target: NavTarget }) {
  const copy = PLACEHOLDER_COPY[target];
  if (!copy) return null;
  return (
    <View style={styles.placeholderRoot}>
      <Text style={styles.placeholderTitle}>{copy.title}</Text>
      <Text style={styles.placeholderBody}>{copy.body}</Text>
    </View>
  );
}

const PLACEHOLDER_COPY: Partial<Record<NavTarget, { title: string; body: string }>> = {
  home: {
    title: 'Home',
    body: 'Overview rails land with InfoPanel in M4R.8.',
  },
  guide: {
    title: 'TV Guide',
    body: 'Full-screen EPG grid arrives in M6R.',
  },
  recordings: {
    title: 'Recordings',
    body: 'Recording playback + management ships in M7R.',
  },
  downloads: {
    title: 'Downloads',
    body: 'Offline downloads ship alongside recordings in M7R.',
  },
  settings: {
    title: 'Settings',
    body: 'Settings modal lands in M7R.',
  },
};

function PlaceholderLabel({ text }: { text: string }) {
  return (
    <View style={styles.labelFill}>
      <Text style={styles.labelText}>{text}</Text>
    </View>
  );
}

const RAIL_WIDTH = 260;
const RIGHT_WIDTH = 360;

const styles = StyleSheet.create({
  outer: {
    flex: 1,
    backgroundColor: colors.bg,
  },
  fullscreenRoot: {
    flex: 1,
    backgroundColor: '#000',
  },
  tvRoot: {
    flex: 1,
    flexDirection: 'row',
    backgroundColor: colors.bg,
  },
  railSlot: {
    width: RAIL_WIDTH,
    backgroundColor: colors.surface900,
    borderRightWidth: 1,
    borderRightColor: colors.glassBorder,
  },
  filterSlot: {
    width: FILTER_PANEL_WIDTH,
  },
  contentSlot: {
    flex: 1,
    backgroundColor: colors.bg,
  },
  rightColumn: {
    width: RIGHT_WIDTH,
    backgroundColor: colors.surface900,
    borderLeftWidth: 1,
    borderLeftColor: colors.glassBorder,
  },
  playerSlot: {
    aspectRatio: 16 / 9,
    backgroundColor: '#000',
    margin: spacing.md,
    borderRadius: radii.md,
    overflow: 'hidden',
  },
  infoSlot: {
    flex: 1,
    margin: spacing.md,
    marginTop: 0,
    backgroundColor: colors.glass,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.glassBorder,
  },
  phoneRoot: {
    flex: 1,
    backgroundColor: colors.bg,
  },
  phoneRail: {
    height: 56,
    backgroundColor: colors.surface900,
    borderBottomWidth: 1,
    borderBottomColor: colors.glassBorder,
  },
  phoneContent: {
    flex: 1,
    backgroundColor: colors.bg,
  },
  phonePlayerSlot: {
    aspectRatio: 16 / 9,
    backgroundColor: '#000',
    margin: spacing.sm,
    borderRadius: radii.md,
    overflow: 'hidden',
  },
  labelFill: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  labelText: {
    color: colors.surface400,
    fontSize: 13,
    fontWeight: '600',
    letterSpacing: 0.5,
  },
  placeholderRoot: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xl,
  },
  placeholderTitle: {
    color: colors.white,
    fontSize: 24,
    fontWeight: '800',
    marginBottom: spacing.sm,
  },
  placeholderBody: {
    color: colors.surface400,
    fontSize: 14,
    fontWeight: '500',
    textAlign: 'center',
    maxWidth: 360,
  },
  edgeBtn: {
    position: 'absolute',
    left: 0,
    top: '50%',
    width: 28,
    height: 72,
    marginTop: -36,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surface900,
    borderTopRightRadius: radii.md,
    borderBottomRightRadius: radii.md,
    borderWidth: 1,
    borderLeftWidth: 0,
    borderColor: colors.glassBorder,
  },
  edgeBtnFilter: {
    top: '35%',
  },
  edgeBtnFocused: {
    backgroundColor: colors.glass,
    borderColor: colors.focus,
  },
  edgeBtnPressed: {
    backgroundColor: colors.glass,
  },
});
