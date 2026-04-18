import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors, spacing } from '../../styles/theme';

interface Props {
  eyebrow?: string;
  title: string;
  subtitle?: string;
  right?: React.ReactNode;
}

export function PageHeader({ eyebrow, title, subtitle, right }: Props) {
  return (
    <View style={styles.root}>
      <View style={styles.left}>
        {eyebrow ? <Text style={styles.eyebrow}>{eyebrow}</Text> : null}
        <Text style={styles.title}>{title}</Text>
        {subtitle ? <Text style={styles.subtitle}>{subtitle}</Text> : null}
      </View>
      {right ? <View>{right}</View> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.xl,
    paddingBottom: spacing.md,
  },
  left: {
    flex: 1,
  },
  eyebrow: {
    color: colors.accent,
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 2,
    textTransform: 'uppercase',
    marginBottom: 6,
  },
  title: {
    color: colors.white,
    fontSize: 34,
    fontWeight: '800',
    letterSpacing: -0.5,
  },
  subtitle: {
    marginTop: 6,
    color: colors.surface400,
    fontSize: 13,
  },
});
