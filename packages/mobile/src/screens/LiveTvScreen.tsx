import React from 'react';
import { View, Text } from 'react-native';

export function LiveTvScreen() {
  return (
    <View className="flex-1 items-center justify-center bg-surface-900">
      <Text className="text-3xl font-semibold text-white">Live TV</Text>
      <Text className="mt-2 text-surface-600">
        Channels grid — wired in Phase 2
      </Text>
    </View>
  );
}
