import React, { useCallback, useState } from 'react';
import {
  Pressable,
  type PressableProps,
  type GestureResponderEvent,
  type View,
} from 'react-native';

export interface FocusableProps extends Omit<PressableProps, 'children'> {
  children:
    | React.ReactNode
    | ((state: { focused: boolean; pressed: boolean }) => React.ReactNode);
  onFocus?: () => void;
  onBlur?: () => void;
  onSelect?: (e: GestureResponderEvent) => void;
  /** Disable for locked/blocked items — no focus, no select. */
  disabled?: boolean;
}

/**
 * Cross-platform focusable primitive. On TV (react-native-tvos), Pressable
 * receives D-pad focus automatically. On phones, it behaves as a normal
 * pressable button. `onSelect` fires on OK / Enter / tap.
 */
export const Focusable = React.forwardRef<View, FocusableProps>(function Focusable(
  { children, onFocus, onBlur, onSelect, disabled, ...rest },
  ref,
) {
  const [focused, setFocused] = useState(false);

  const handleFocus = useCallback(() => {
    setFocused(true);
    onFocus?.();
  }, [onFocus]);

  const handleBlur = useCallback(() => {
    setFocused(false);
    onBlur?.();
  }, [onBlur]);

  return (
    <Pressable
      ref={ref}
      disabled={disabled}
      onFocus={handleFocus}
      onBlur={handleBlur}
      onPress={onSelect}
      {...rest}
    >
      {({ pressed }) =>
        typeof children === 'function' ? children({ focused, pressed }) : children
      }
    </Pressable>
  );
});
