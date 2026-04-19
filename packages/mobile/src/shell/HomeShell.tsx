import React from 'react';
import { Platform, StyleSheet, Text, View } from 'react-native';
import { colors, radii, spacing } from '../styles/theme';

// Three-column TV layout: LeftRail · ContentPanel · (InfoPanel stacked over
// MiniPlayer). Phone stacks them vertically. Each region is a labeled
// placeholder that M4R.4–M4R.8 will replace with its real component.
export function HomeShell() {
  return Platform.isTV ? <TvLayout /> : <PhoneLayout />;
}

function TvLayout() {
  return (
    <View style={styles.tvRoot}>
      <View style={styles.railSlot}>
        <RegionLabel text="LeftRail · M4R.4" />
      </View>
      <View style={styles.contentSlot}>
        <RegionLabel text="ContentPanel · M4R.5" />
      </View>
      <View style={styles.rightColumn}>
        <View style={styles.playerSlot}>
          <RegionLabel text="MiniPlayer · M4R.7" />
        </View>
        <View style={styles.infoSlot}>
          <RegionLabel text="InfoPanel · M4R.8" />
        </View>
      </View>
    </View>
  );
}

function PhoneLayout() {
  return (
    <View style={styles.phoneRoot}>
      <View style={styles.phoneRail}>
        <RegionLabel text="LeftRail · M4R.4" />
      </View>
      <View style={styles.phoneContent}>
        <RegionLabel text="ContentPanel · M4R.5" />
      </View>
      <View style={styles.phonePlayerSlot}>
        <RegionLabel text="MiniPlayer · M4R.7" />
      </View>
    </View>
  );
}

function RegionLabel({ text }: { text: string }) {
  return (
    <View style={styles.labelFill}>
      <Text style={styles.labelText}>{text}</Text>
    </View>
  );
}

// TV: 3-column flex. Rail width tuned for D-pad reach without crowding the
// content panel. Right column splits ~45/55 between player and info.
const RAIL_WIDTH = 260;
const RIGHT_WIDTH = 360;

const styles = StyleSheet.create({
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
});
