import React, { useRef, useCallback } from 'react';
import { Text, View, Animated, Pressable, StyleSheet } from 'react-native';
import { colors } from '../../styles/theme';

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
        style={[
          styles.button,
          active ? styles.buttonActive : styles.buttonInactive,
          { transform: [{ scale }] },
        ]}
      >
        <Animated.View
          style={[styles.focusRing, { opacity: borderOpacity }]}
          pointerEvents="none"
        />
        <View>
          <Text style={styles.label}>{label}</Text>
        </View>
      </Animated.View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    position: 'relative',
    borderRadius: 16,
    paddingHorizontal: 32,
    paddingVertical: 16,
  },
  buttonActive: {
    backgroundColor: colors.brand,
  },
  buttonInactive: {
    backgroundColor: colors.surface700,
  },
  focusRing: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    borderRadius: 16,
    borderWidth: 2,
    borderColor: colors.focus,
  },
  label: {
    fontSize: 18,
    fontWeight: '600',
    color: colors.white,
  },
});
