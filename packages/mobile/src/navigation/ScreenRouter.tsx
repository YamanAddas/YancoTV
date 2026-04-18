import React from 'react';
import { useNavStore } from '../stores/nav-store';
import { HomeScreen } from '../screens/HomeScreen';
import { SourcesScreen } from '../screens/SourcesScreen';
import { ChannelListScreen } from '../screens/ChannelListScreen';
import { ChannelDetailScreen } from '../screens/ChannelDetailScreen';
import { PlayerScreen } from '../screens/PlayerScreen';

export function ScreenRouter() {
  const screen = useNavStore((s) => s.screen);

  switch (screen) {
    case 'sources':
      return <SourcesScreen />;
    case 'detail':
      return <ChannelDetailScreen />;
    case 'player':
      return <PlayerScreen />;
    case 'live':
      return <ChannelListScreen type="live" title="Live TV" />;
    case 'movies':
      return <ChannelListScreen type="movie" title="Movies" />;
    case 'series':
      return <ChannelListScreen type="series" title="Series" />;
    case 'settings':
    case 'home':
    default:
      return <HomeScreen />;
  }
}
