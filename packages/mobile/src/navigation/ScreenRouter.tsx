import React from 'react';
import { useNavStore } from '../stores/nav-store';
import { AppLayout } from '../components/layout/AppLayout';
import { HomeScreen } from '../screens/HomeScreen';
import { SourcesScreen } from '../screens/SourcesScreen';
import { ChannelListScreen } from '../screens/ChannelListScreen';
import { ChannelDetailScreen } from '../screens/ChannelDetailScreen';
import { PlayerScreen } from '../screens/PlayerScreen';

export function ScreenRouter() {
  const screen = useNavStore((s) => s.screen);

  if (screen === 'player') {
    return (
      <AppLayout bare>
        <PlayerScreen />
      </AppLayout>
    );
  }

  let content: React.ReactNode;
  switch (screen) {
    case 'sources':
      content = <SourcesScreen />;
      break;
    case 'detail':
      content = <ChannelDetailScreen />;
      break;
    case 'live':
      content = <ChannelListScreen type="live" title="Live TV" />;
      break;
    case 'movies':
      content = <ChannelListScreen type="movie" title="Movies" />;
      break;
    case 'series':
      content = <ChannelListScreen type="series" title="Series" />;
      break;
    case 'settings':
    case 'home':
    default:
      content = <HomeScreen />;
  }

  return <AppLayout>{content}</AppLayout>;
}
