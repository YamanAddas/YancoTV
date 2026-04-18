import React, { useState } from 'react';
import { View, Text, StatusBar } from 'react-native';
import { CORE_VERSION } from '@yancotv/core';
import { TvButton } from '../components/tv/TvButton';

const NAV_ITEMS = ['Live TV', 'Movies', 'Series', 'Settings'] as const;

export function HomeScreen() {
  const [activeIndex, setActiveIndex] = useState(0);

  return (
    <View className="flex-1 bg-surface-900 p-12">
      <StatusBar barStyle="light-content" backgroundColor="#0a0a0f" />

      <View className="mb-12">
        <Text className="text-5xl font-bold text-white">YancoTV</Text>
        <Text className="mt-2 text-base text-surface-600">
          Android TV • core v{CORE_VERSION}
        </Text>
      </View>

      <View className="flex-row gap-4">
        {NAV_ITEMS.map((label, i) => (
          <TvButton
            key={label}
            label={label}
            autoFocus={i === 0}
            onSelect={() => setActiveIndex(i)}
            active={activeIndex === i}
          />
        ))}
      </View>

      <View className="mt-8">
        <Text className="text-surface-600 text-sm">
          Selected: {NAV_ITEMS[activeIndex]}
        </Text>
      </View>
    </View>
  );
}
