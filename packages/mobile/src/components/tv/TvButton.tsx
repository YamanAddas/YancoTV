import React, { useRef, useCallback } from 'react';
import { Text, View, Animated, Pressable } from 'react-native';

interface TvButtonProps {
  label: string;
  onSelect: () => void;
  autoFocus?: boolean;
  active?: boolean;
}

export function TvButton({ label, onSelect, autoFocus, active }: TvButtonProps) {
  const scale = useRef(new Animated.Value(1)).current;
  const borderOpacity = useRef(new Animated.Value(0)).current;

  const handleFocus = useCallback(() => {
    Animated.parallel([
      Animated.spring(scale, { toValue: 1.08, useNativeDriver: true, speed: 14, bounciness: 8 }),
      Animated.timing(borderOpacity, { toValue: 1, duration: 120, useNativeDriver: true }),
    ]).start();
  }, [scale, borderOpacity]);

  const handleBlur = useCallback(() => {
    Animated.parallel([
      Animated.spring(scale, { toValue: 1, useNativeDriver: true, speed: 14 }),
      Animated.timing(borderOpacity, { toValue: 0, duration: 120, useNativeDriver: true }),
    ]).start();
  }, [scale, borderOpacity]);

  return (
    <Pressable
      hasTVPreferredFocus={autoFocus}
      onFocus={handleFocus}
      onBlur={handleBlur}
      onPress={onSelect}
    >
      <Animated.View
        style={{ transform: [{ scale }] }}
        className={`relative rounded-2xl px-8 py-4 ${active ? 'bg-brand' : 'bg-surface-700'}`}
      >
        <Animated.View
          style={{ opacity: borderOpacity }}
          className="absolute inset-0 rounded-2xl border-2 border-focus"
          pointerEvents="none"
        />
        <View>
          <Text className="text-lg font-semibold text-white">{label}</Text>
        </View>
      </Animated.View>
    </Pressable>
  );
}
