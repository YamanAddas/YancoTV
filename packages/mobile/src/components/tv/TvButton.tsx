import React, { useState, useCallback } from 'react';
import { Text, View, Pressable, StyleSheet } from 'react-native';
import { colors } from '../../styles/theme';

interface TvButtonProps {
  label: string;
  onSelect: () => void;
  autoFocus?: boolean;
  active?: boolean;
}

export function TvButton({ label, onSelect, autoFocus, active }: TvButtonProps) {
  const [focused, setFocused] = useState(false);
  const [pressed, setPressed] = useState(false);

  const handleFocus = useCallback(() => setFocused(true), []);
  const handleBlur = useCallback(() => setFocused(false), []);
  const handlePressIn = useCallback(() => setPressed(true), []);
  const handlePressOut = useCallback(() => setPressed(false), []);

  return (
    <Pressable
      hasTVPreferredFocus={autoFocus}
      onFocus={handleFocus}
      onBlur={handleBlur}
      onPressIn={handlePressIn}
      onPressOut={handlePressOut}
      onPress={onSelect}
      style={({ pressed: p }) => [
        styles.button,
        active ? styles.buttonActive : styles.buttonInactive,
        (focused || pressed || p) && styles.buttonFocused,
      ]}
    >
      <View>
        <Text style={styles.label}>{label}</Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    borderRadius: 16,
    paddingHorizontal: 32,
    paddingVertical: 16,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  buttonActive: {
    backgroundColor: colors.brand,
  },
  buttonInactive: {
    backgroundColor: colors.surface700,
  },
  buttonFocused: {
    borderColor: colors.focus,
    backgroundColor: colors.surface600,
  },
  label: {
    fontSize: 18,
    fontWeight: '600',
    color: colors.white,
  },
});
