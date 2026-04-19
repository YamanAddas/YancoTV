import React, { useState } from 'react';
import {
  Modal,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
  type ViewStyle,
} from 'react-native';
import type { SortOption } from '@yancotv/core';
import { Focusable } from '../../focus/Focusable';
import { colors, radii, spacing } from '../../styles/theme';

interface OptionDef {
  value: SortOption;
  label: string;
}

const OPTIONS: OptionDef[] = [
  { value: 'provider', label: 'Provider order' },
  { value: 'name-asc', label: 'Name A\u2013Z' },
  { value: 'name-desc', label: 'Name Z\u2013A' },
  { value: 'group', label: 'Group' },
  { value: 'recent', label: 'Recently added' },
];

interface Props {
  value: SortOption;
  onChange: (next: SortOption) => void;
}

export function SortDropdown({ value, onChange }: Props) {
  const [open, setOpen] = useState(false);
  const current = OPTIONS.find((o) => o.value === value) ?? OPTIONS[0];

  return (
    <>
      <Focusable onSelect={() => setOpen(true)}>
        {({ focused, pressed }) => (
          <View
            style={[
              styles.trigger,
              focused && styles.triggerFocused,
              pressed && styles.triggerPressed,
            ]}
          >
            <Text style={styles.triggerLabelEyebrow}>Sort</Text>
            <Text style={styles.triggerLabel}>{current.label}</Text>
            <Text style={styles.caret}>{'\u25BE'}</Text>
          </View>
        )}
      </Focusable>
      <Modal
        visible={open}
        transparent
        animationType="fade"
        onRequestClose={() => setOpen(false)}
      >
        <Pressable style={styles.backdrop} onPress={() => setOpen(false)}>
          {/* Inner Pressable absorbs taps on the menu card so they don't bubble
              up and close the modal before the option's onSelect fires. */}
          <Pressable onPress={() => undefined} style={styles.menuWrap}>
            <View style={styles.menu}>
              <Text style={styles.menuEyebrow}>Sort channels by</Text>
              {OPTIONS.map((opt) => {
                const active = opt.value === value;
                return (
                  <Focusable
                    key={opt.value}
                    onSelect={() => {
                      onChange(opt.value);
                      setOpen(false);
                    }}
                    // Put initial TV focus on the currently-selected option so
                    // D-pad up/down immediately moves relative to it.
                    {...(Platform.isTV && active
                      ? { hasTVPreferredFocus: true }
                      : null)}
                  >
                    {({ focused }) => (
                      <View
                        style={[
                          styles.item,
                          active && styles.itemActive,
                          focused && styles.itemFocused,
                        ]}
                      >
                        <Text
                          style={[
                            styles.itemText,
                            active && styles.itemTextActive,
                          ]}
                        >
                          {opt.label}
                        </Text>
                        {active ? <Text style={styles.itemCheck}>{'\u2713'}</Text> : null}
                      </View>
                    )}
                  </Focusable>
                );
              })}
            </View>
          </Pressable>
        </Pressable>
      </Modal>
    </>
  );
}

const styles = StyleSheet.create({
  trigger: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: colors.glassBorderSoft,
  } as ViewStyle,
  triggerFocused: {
    borderColor: colors.accent,
    backgroundColor: colors.surface700,
  } as ViewStyle,
  triggerPressed: {
    opacity: 0.85,
  } as ViewStyle,
  triggerLabelEyebrow: {
    color: colors.surface400,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 1.5,
    textTransform: 'uppercase',
  },
  triggerLabel: {
    color: colors.white,
    fontSize: 13,
    fontWeight: '600',
  },
  caret: {
    color: colors.surface400,
    fontSize: 12,
    marginLeft: 2,
  },
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.55)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: spacing.xl,
  } as ViewStyle,
  menuWrap: {
    maxWidth: 360,
    width: '100%',
  } as ViewStyle,
  menu: {
    backgroundColor: colors.surface900,
    borderRadius: radii.lg,
    borderWidth: 1,
    borderColor: colors.glassBorder,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.sm,
  } as ViewStyle,
  menuEyebrow: {
    color: colors.accent,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 2,
    textTransform: 'uppercase',
    paddingHorizontal: spacing.md,
    paddingTop: spacing.xs,
    paddingBottom: spacing.sm,
  },
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.md,
    paddingVertical: 12,
    borderRadius: radii.md,
  } as ViewStyle,
  itemFocused: {
    backgroundColor: colors.surface700,
  } as ViewStyle,
  itemActive: {
    backgroundColor: 'rgba(0, 255, 170, 0.08)',
  } as ViewStyle,
  itemText: {
    color: colors.surface200,
    fontSize: 14,
    fontWeight: '500',
  },
  itemTextActive: {
    color: colors.accent,
    fontWeight: '700',
  },
  itemCheck: {
    color: colors.accent,
    fontSize: 14,
    fontWeight: '800',
  },
});
