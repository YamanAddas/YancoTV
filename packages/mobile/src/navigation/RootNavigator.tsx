import React from 'react';
import { StyleSheet, View } from 'react-native';
import {
  DarkTheme,
  NavigationContainer,
  type LinkingOptions,
  type Theme,
} from '@react-navigation/native';
import {
  createNativeStackNavigator,
  type NativeStackScreenProps,
} from '@react-navigation/native-stack';

import { HomeShell } from '../shell/HomeShell';
import { FullscreenPlayer } from '../screens/FullscreenPlayer';
import { colors } from '../styles/theme';

// The navigator holds exactly two routes after the 2026-04-19 reboot:
//   • Shell            — the only regular screen; contains LeftRail,
//                        ContentPanel, InfoPanel, MiniPlayer, overlays.
//   • FullscreenPlayer — the expanded-from-MiniPlayer playback surface.
// Anything that used to be its own screen is now a panel or modal inside
// HomeShell. See PRODUCTION_PLAN_ANDROID.md § M4R.
export type RootStackParamList = {
  Shell: undefined;
  FullscreenPlayer: { url: string; title?: string; contentId?: string };
};

export type FullscreenPlayerProps = NativeStackScreenProps<
  RootStackParamList,
  'FullscreenPlayer'
>;

const RootStack = createNativeStackNavigator<RootStackParamList>();

const linking: LinkingOptions<RootStackParamList> = {
  prefixes: ['yancotv://'],
  config: {
    screens: {
      Shell: '',
      FullscreenPlayer: 'play',
    },
  },
};

const navTheme: Theme = {
  ...DarkTheme,
  colors: {
    ...DarkTheme.colors,
    background: colors.bg,
    card: colors.surface900,
    text: colors.white,
    border: colors.glassBorder,
    primary: colors.accent,
  },
};

export function RootNavigator() {
  return (
    <NavigationContainer theme={navTheme} linking={linking}>
      <View style={styles.root}>
        <RootStack.Navigator screenOptions={{ headerShown: false }}>
          <RootStack.Screen name="Shell" component={HomeShell} />
          <RootStack.Screen
            name="FullscreenPlayer"
            component={FullscreenPlayer}
            options={{
              animation: 'fade',
              gestureEnabled: false,
              // transparentModal keeps HomeShell (and its PersistentPlayerHost
              // child) mounted beneath the controls overlay — critical for
              // the single-<Video> architecture (M4R rule 5).
              presentation: 'transparentModal',
              contentStyle: { backgroundColor: 'transparent' },
            }}
          />
        </RootStack.Navigator>
      </View>
    </NavigationContainer>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.bg,
  },
});
