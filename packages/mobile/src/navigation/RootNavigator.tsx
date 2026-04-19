import React from 'react';
import { Platform, StyleSheet, View } from 'react-native';
import {
  DarkTheme,
  NavigationContainer,
  type Theme,
} from '@react-navigation/native';
import {
  createNativeStackNavigator,
  type NativeStackScreenProps,
} from '@react-navigation/native-stack';
import {
  createDrawerNavigator,
  type DrawerContentComponentProps,
} from '@react-navigation/drawer';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';

import { HomeScreen } from '../screens/HomeScreen';
import { ChannelListScreen } from '../screens/ChannelListScreen';
import { SourcesScreen } from '../screens/SourcesScreen';
import { ChannelDetailScreen } from '../screens/ChannelDetailScreen';
import { PlayerScreen } from '../screens/PlayerScreen';
import { TvDrawerContent } from './TvDrawerContent';
import { colors } from '../styles/theme';

// ---------- Param lists ----------
//
// Kept in one place so screens can type `useNavigation<...>()` / `useRoute<...>()`
// off of these rather than duplicating route names.

export type RootStackParamList = {
  Main: undefined;
  Detail: { channelId: string };
  Player: { channelId: string; episodeId?: string };
};

export type MainTabsParamList = {
  Home: undefined;
  Live: undefined;
  Movies: undefined;
  Series: undefined;
  Sources: undefined;
};

export type DetailScreenProps = NativeStackScreenProps<
  RootStackParamList,
  'Detail'
>;
export type PlayerScreenProps = NativeStackScreenProps<
  RootStackParamList,
  'Player'
>;

const RootStack = createNativeStackNavigator<RootStackParamList>();
const Drawer = createDrawerNavigator<MainTabsParamList>();
const Tabs = createBottomTabNavigator<MainTabsParamList>();

// ---------- List-screen wrappers ----------
//
// ChannelListScreen takes `type` + `title` props. The drawer/tabs use distinct
// routes per content type, so each route gets a thin wrapper that supplies the
// props. Avoids route-param plumbing for static values.

function LiveRoute() {
  return <ChannelListScreen type="live" title="Live TV" />;
}
function MoviesRoute() {
  return <ChannelListScreen type="movie" title="Movies" />;
}
function SeriesRoute() {
  return <ChannelListScreen type="series" title="Series" />;
}

// ---------- Main shells ----------

function TvDrawerShell() {
  // Permanent drawer keeps the Sidebar always visible, matching the desktop
  // persistent left rail. D-pad focus can reach both the drawer and the screen.
  return (
    <Drawer.Navigator
      screenOptions={{
        headerShown: false,
        drawerType: 'permanent',
        sceneStyle: styles.scene,
        drawerStyle: styles.drawerPermanent,
        swipeEnabled: false,
      }}
      drawerContent={(props: DrawerContentComponentProps) => (
        <TvDrawerContent {...props} />
      )}
    >
      <Drawer.Screen name="Home" component={HomeScreen} />
      <Drawer.Screen name="Live" component={LiveRoute} />
      <Drawer.Screen name="Movies" component={MoviesRoute} />
      <Drawer.Screen name="Series" component={SeriesRoute} />
      <Drawer.Screen name="Sources" component={SourcesScreen} />
    </Drawer.Navigator>
  );
}

function PhoneTabsShell() {
  // Bottom tabs on phone. Icon glyphs mirror the Sidebar for continuity;
  // replace with SVG in the MB-10 icon cleanup.
  return (
    <Tabs.Navigator
      screenOptions={{
        headerShown: false,
        tabBarStyle: styles.tabBar,
        tabBarActiveTintColor: colors.accent,
        tabBarInactiveTintColor: colors.surface400,
        tabBarLabelStyle: styles.tabBarLabel,
        sceneStyle: styles.scene,
      }}
    >
      <Tabs.Screen
        name="Home"
        component={HomeScreen}
        options={{ tabBarLabel: 'Home' }}
      />
      <Tabs.Screen
        name="Live"
        component={LiveRoute}
        options={{ tabBarLabel: 'Live' }}
      />
      <Tabs.Screen
        name="Movies"
        component={MoviesRoute}
        options={{ tabBarLabel: 'Movies' }}
      />
      <Tabs.Screen
        name="Series"
        component={SeriesRoute}
        options={{ tabBarLabel: 'Series' }}
      />
      <Tabs.Screen
        name="Sources"
        component={SourcesScreen}
        options={{ tabBarLabel: 'Sources' }}
      />
    </Tabs.Navigator>
  );
}

function MainShell() {
  return Platform.isTV ? <TvDrawerShell /> : <PhoneTabsShell />;
}

// ---------- Root ----------

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
    <NavigationContainer theme={navTheme}>
      <View style={styles.root}>
        <RootStack.Navigator screenOptions={{ headerShown: false }}>
          <RootStack.Screen name="Main" component={MainShell} />
          <RootStack.Screen name="Detail" component={ChannelDetailScreen} />
          <RootStack.Screen
            name="Player"
            component={PlayerScreen}
            options={{ animation: 'fade', gestureEnabled: false }}
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
  scene: {
    backgroundColor: colors.bg,
  },
  drawerPermanent: {
    width: 260,
    backgroundColor: 'transparent',
    borderRightWidth: 0,
  },
  tabBar: {
    backgroundColor: colors.glassStrong,
    borderTopColor: colors.glassBorder,
  },
  tabBarLabel: {
    fontSize: 11,
    fontWeight: '700',
  },
});
