import React from 'react';
import { Platform, StyleSheet, View } from 'react-native';
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
import {
  createDrawerNavigator,
  type DrawerContentComponentProps,
} from '@react-navigation/drawer';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';

import { HomeScreen } from '../screens/HomeScreen';
import { LiveTvScreen } from '../screens/LiveTvScreen';
import { MoviesScreen } from '../screens/MoviesScreen';
import { SeriesScreen } from '../screens/SeriesScreen';
import { SourcesScreen } from '../screens/SourcesScreen';
import { SearchScreen } from '../screens/SearchScreen';
import { FavoritesScreen } from '../screens/FavoritesScreen';
import { ContentDetailScreen } from '../screens/ContentDetailScreen';
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
  Search: undefined;
  Favorites: undefined;
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
      <Drawer.Screen name="Live" component={LiveTvScreen} />
      <Drawer.Screen name="Movies" component={MoviesScreen} />
      <Drawer.Screen name="Series" component={SeriesScreen} />
      <Drawer.Screen name="Search" component={SearchScreen} />
      <Drawer.Screen name="Favorites" component={FavoritesScreen} />
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
        component={LiveTvScreen}
        options={{ tabBarLabel: 'Live' }}
      />
      <Tabs.Screen
        name="Movies"
        component={MoviesScreen}
        options={{ tabBarLabel: 'Movies' }}
      />
      <Tabs.Screen
        name="Series"
        component={SeriesScreen}
        options={{ tabBarLabel: 'Series' }}
      />
      <Tabs.Screen
        name="Search"
        component={SearchScreen}
        options={{ tabBarLabel: 'Search' }}
      />
      <Tabs.Screen
        name="Favorites"
        component={FavoritesScreen}
        options={{ tabBarLabel: 'Favorites' }}
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

// M3.6: deep-link scheme. The Android intent-filter for `yancotv://` lives in
// AndroidManifest.xml — this config tells React Navigation how to map an
// incoming URL to a screen + params.
//
// Supported URLs:
//   yancotv://home              → Main / Home
//   yancotv://live              → Main / Live
//   yancotv://movies            → Main / Movies
//   yancotv://series            → Main / Series
//   yancotv://sources           → Main / Sources
//   yancotv://detail/:channelId → Detail
//   yancotv://player/:channelId → Player
const linking: LinkingOptions<RootStackParamList> = {
  prefixes: ['yancotv://'],
  config: {
    screens: {
      Main: {
        screens: {
          Home: 'home',
          Live: 'live',
          Movies: 'movies',
          Series: 'series',
          Search: 'search',
          Favorites: 'favorites',
          Sources: 'sources',
        },
      },
      Detail: 'detail/:channelId',
      Player: 'player/:channelId',
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
          <RootStack.Screen name="Main" component={MainShell} />
          <RootStack.Screen name="Detail" component={ContentDetailScreen} />
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
