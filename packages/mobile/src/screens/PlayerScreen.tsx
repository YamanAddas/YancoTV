import React from 'react';
import { View, Text } from 'react-native';
import { useRoute, type RouteProp } from '@react-navigation/native';
import type { RootStackParamList } from '../navigation/RootNavigator';

type PlayerRoute = RouteProp<RootStackParamList, 'Player'>;

export function PlayerScreen() {
  const { params } = useRoute<PlayerRoute>();

  return (
    <View className="flex-1 items-center justify-center bg-black">
      <Text className="text-2xl text-white">Player</Text>
      <Text className="mt-2 text-surface-600">{params?.title ?? ''}</Text>
      <Text className="mt-6 text-xs text-surface-600">
        react-native-video wired in Phase 4
      </Text>
    </View>
  );
}
