import React from 'react';
import { Text, View } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';
import { Focusable } from '../../focus/Focusable';

interface TvButtonProps {
  label: string;
  onSelect: () => void;
  autoFocus?: boolean;
}

/**
 * TV-optimized button: scales up + glows on D-pad focus.
 * Reanimated worklet keeps the animation on the UI thread (60fps).
 */
export function TvButton({ label, onSelect, autoFocus }: TvButtonProps) {
  const scale = useSharedValue(1);
  const glowOpacity = useSharedValue(0);

  const animatedStyle = useAnimatedStyle(() => ({
    transform: [{ scale: scale.value }],
  }));

  const glowStyle = useAnimatedStyle(() => ({
    opacity: glowOpacity.value,
  }));

  return (
    <Focusable
      hasTVPreferredFocus={autoFocus}
      onFocus={() => {
        scale.value = withSpring(1.08, { damping: 14, stiffness: 180 });
        glowOpacity.value = withSpring(1, { damping: 20 });
      }}
      onBlur={() => {
        scale.value = withSpring(1);
        glowOpacity.value = withSpring(0);
      }}
      onSelect={onSelect}
    >
      <Animated.View
        style={animatedStyle}
        className="relative rounded-2xl bg-surface-700 px-8 py-4"
      >
        <Animated.View
          style={glowStyle}
          className="absolute inset-0 rounded-2xl border-2 border-focus"
          pointerEvents="none"
        />
        <View>
          <Text className="text-lg font-semibold text-white">{label}</Text>
        </View>
      </Animated.View>
    </Focusable>
  );
}
