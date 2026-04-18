import React from 'react';
import { StatusBar, StyleSheet, View } from 'react-native';
import LinearGradient from 'react-native-linear-gradient';
import { colors } from '../../styles/theme';
import { Sidebar } from './Sidebar';

interface Props {
  children: React.ReactNode;
  /** Set true for the full-bleed player screen to hide chrome. */
  bare?: boolean;
}

// Desktop `.bg-space` — three radial gradients over surface-950. We can only
// paint linear gradients natively without extra libs; stacking two diagonals
// approximates the glow hotspots in a way that reads the same.
export function AppLayout({ children, bare = false }: Props) {
  if (bare) {
    return <View style={styles.rootBare}>{children}</View>;
  }

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor={colors.bg} />

      {/* Space atmosphere — layered diagonal gradients to emulate the
          desktop bg-space radial hotspots. */}
      <LinearGradient
        colors={[
          'rgba(0, 255, 170, 0.07)',
          'transparent',
          'rgba(0, 204, 255, 0.05)',
        ]}
        start={{ x: 0, y: 0.5 }}
        end={{ x: 1, y: 0 }}
        style={StyleSheet.absoluteFill}
        pointerEvents="none"
      />
      <LinearGradient
        colors={['transparent', 'rgba(0, 255, 200, 0.04)']}
        start={{ x: 0.5, y: 0.2 }}
        end={{ x: 0.5, y: 1 }}
        style={StyleSheet.absoluteFill}
        pointerEvents="none"
      />

      <View style={styles.row}>
        <Sidebar />
        <View style={styles.main}>{children}</View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.bg,
  },
  rootBare: {
    flex: 1,
    backgroundColor: '#000',
  },
  row: {
    flex: 1,
    flexDirection: 'row',
  },
  main: {
    flex: 1,
  },
});
