import React from 'react';
import { StyleSheet, View } from 'react-native';
import {
  DarkTheme,
  NavigationContainer,
  type LinkingOptions,
  type Theme,
} from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { HomeShell } from '../shell/HomeShell';
import { colors } from '../styles/theme';

// One route. Fullscreen playback is a HomeShell state, not a navigator
// frame — that's what makes the video surface stable on Android TV.
// See 2026-04-20 sure-fix rewrite (retires FullscreenPlayer route).
export type RootStackParamList = {
  Shell: undefined;
};

const RootStack = createNativeStackNavigator<RootStackParamList>();

const linking: LinkingOptions<RootStackParamList> = {
  prefixes: ['yancotv://'],
  config: {
    screens: {
      Shell: '',
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
