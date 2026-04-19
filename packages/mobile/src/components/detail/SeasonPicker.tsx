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
import { Focusable } from '../../focus/Focusable';
import { colors, radii, spacing } from '../../styles/theme';

interface SeasonOption {
  seasonNumber: number;
  count: number;
}

interface Props {
  seasons: SeasonOption[];
  value: number;
  onChange: (next: number) => void;
}

/**
 * Season picker dropdown for the Episodes tab. Mirrors the SortDropdown
 * modal pattern so focus, keyboard, and tap behave consistently across TV
 * and phone. Hidden by the caller when there's only one season.
 */
export function SeasonPicker({ seasons, value, onChange }: Props) {
  const [open, setOpen] = useState(false);
  const current = seasons.find((s) => s.seasonNumber === value) ?? seasons[0];

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
            <Text style={styles.triggerEyebrow}>Season</Text>
            <Text style={styles.triggerLabel}>
              {current ? `Season ${current.seasonNumber}` : 'Season'}
            </Text>
            {current ? (
              <Text style={styles.triggerCount}>
                {current.count} ep{current.count === 1 ? '' : 's'}
              </Text>
            ) : null}
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
          <Pressable onPress={() => undefined} style={styles.menuWrap}>
            <View style={styles.menu}>
              <Text style={styles.menuEyebrow}>Pick a season</Text>
              {seasons.map((opt) => {
                const active = opt.seasonNumber === value;
                return (
                  <Focusable
                    key={opt.seasonNumber}
                    onSelect={() => {
                      onChange(opt.seasonNumber);
                      setOpen(false);
                    }}
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
                          Season {opt.seasonNumber}
                        </Text>
                        <Text style={styles.itemMeta}>
                          {opt.count} ep{opt.count === 1 ? '' : 's'}
                          {active ? '  \u2713' : ''}
                        </Text>
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
    alignSelf: 'flex-start',
  } as ViewStyle,
  triggerFocused: {
    borderColor: colors.accent,
    backgroundColor: colors.surface700,
  } as ViewStyle,
  triggerPressed: {
    opacity: 0.85,
  } as ViewStyle,
  triggerEyebrow: {
    color: colors.surface400,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 1.5,
    textTransform: 'uppercase',
  },
  triggerLabel: {
    color: colors.white,
    fontSize: 13,
    fontWeight: '700',
  },
  triggerCount: {
    color: colors.surface400,
    fontSize: 11,
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
  itemMeta: {
    color: colors.surface400,
    fontSize: 12,
    fontWeight: '600',
  },
});
