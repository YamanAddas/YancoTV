import React from 'react';
import { View, Text } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { CORE_VERSION } from '@yancotv/core';
import { TvButton } from '../components/tv/TvButton';
import type { RootStackParamList } from '../navigation/RootNavigator';

type Nav = NativeStackNavigationProp<RootStackParamList, 'Home'>;

export function HomeScreen() {
  const navigation = useNavigation<Nav>();

  return (
    <View className="flex-1 bg-surface-900 p-12">
      <View className="mb-12">
        <Text className="text-5xl font-bold text-white">YancoTV</Text>
        <Text className="mt-2 text-base text-surface-600">
          Android TV • core v{CORE_VERSION}
        </Text>
      </View>

      <View className="flex-row gap-4">
        <TvButton
          label="Live TV"
          autoFocus
          onSelect={() => navigation.navigate('LiveTv')}
        />
        <TvButton label="Movies" onSelect={() => {}} />
        <TvButton label="Series" onSelect={() => {}} />
        <TvButton label="Settings" onSelect={() => {}} />
      </View>
    </View>
  );
}
