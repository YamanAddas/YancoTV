import React from 'react';
import { Platform, StatusBar, StyleSheet, Text, View } from 'react-native';
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
import { PersistentPlayerHost } from '../player/PersistentPlayerHost';
import { usePlayerStore } from '../stores/player-store';
import {
  isContentNavTarget,
  useShellStore,
  type NavTarget,
} from '../stores/shell-store';
import { colors, radii, spacing } from '../styles/theme';

// Three-column TV layout: AppSidebar · ContentPanel (or placeholder) ·
// (InfoPanel stacked over MiniPlayer). Phone stacks them vertically.
// InfoPanel is still a placeholder; M4R.8 replaces it.
//
// PersistentPlayerHost is rendered last so its absolute-positioned mini
// wrapper paints over the MiniPlayer slot, and its fullscreen wrapper
// paints over the whole shell (M4R rule 5).
export function HomeShell() {
  const hasTrack = usePlayerStore((s) => s.track !== null);
  const navTarget = useShellStore((s) => s.navTarget);
  const insets = useSafeAreaInsets();
  // Phone needs top/bottom padding to clear the status bar + gesture area.
  // TV runs full-bleed — no status bar, no nav gestures.
  const outerPad = Platform.isTV
    ? null
    : { paddingTop: insets.top, paddingBottom: insets.bottom };
  // Group filter panel only applies to type-backed nav targets
  // (live / movies / series). Favorites has no groups; non-content targets
  // render placeholder panels with no filter column.
  const showFilter = hasGroupFilter(navTarget);
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
        <TvLayout hasTrack={hasTrack} showFilter={showFilter} />
      ) : (
        <PhoneLayout hasTrack={hasTrack} />
      )}
      <PersistentPlayerHost />
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
}: {
  hasTrack: boolean;
  showFilter: boolean;
}) {
  return (
    <View style={styles.tvRoot}>
      <View style={styles.railSlot}>
        <AppSidebar />
      </View>
      {showFilter && (
        <View style={styles.filterSlot}>
          <CategoryFilterPanel />
        </View>
      )}
      <View style={styles.contentSlot}>
        <MainPanel />
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

// Routes the main column based on navTarget. Content-bearing targets
// (live / movies / series / favorites) keep the ContentPanel behavior the
// ship criterion depends on; the other five land placeholders until their
// milestone ships (M4R.D non-goals list).
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
});
