import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../styles/theme';

export function HomeShell() {
  return (
    <View style={styles.root}>
      <Text style={styles.title}>HomeShell</Text>
      <Text style={styles.sub}>M4R.3 will build the real layout here.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.bg,
  },
  title: {
    color: colors.white,
    fontSize: 28,
    fontWeight: '800',
  },
  sub: {
    color: colors.surface400,
    fontSize: 14,
    marginTop: 8,
  },
});
