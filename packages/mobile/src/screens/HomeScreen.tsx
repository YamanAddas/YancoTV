import React from 'react';
import { View, Text, StatusBar, StyleSheet } from 'react-native';
import { CORE_VERSION } from '@yancotv/core';
import { TvButton } from '../components/tv/TvButton';
import { useNavStore, type Screen } from '../stores/nav-store';
import { useSourcesStore } from '../stores/sources-store';
import { colors } from '../styles/theme';

const NAV_ITEMS: { label: string; screen: Screen }[] = [
  { label: 'Live TV', screen: 'live' },
  { label: 'Movies', screen: 'movies' },
  { label: 'Series', screen: 'series' },
  { label: 'Sources', screen: 'sources' },
];

export function HomeScreen() {
  const navigate = useNavStore((s) => s.navigate);
  const sourceCount = useSourcesStore((s) => s.sources.length);
  const channelCount = useSourcesStore((s) => s.channels.length);

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor={colors.surface900} />

      <View style={styles.header}>
        <Text style={styles.title}>YancoTV</Text>
        <Text style={styles.buildTag}>BUILD 2 • StyleSheet only</Text>
        <Text style={styles.subtitle}>
          Android TV • core v{CORE_VERSION}
        </Text>
        <Text style={styles.counts}>
          {sourceCount} {sourceCount === 1 ? 'source' : 'sources'} •{' '}
          {channelCount} {channelCount === 1 ? 'item' : 'items'}
        </Text>
      </View>

      <View style={styles.nav}>
        {NAV_ITEMS.map((item, i) => (
          <TvButton
            key={item.screen}
            label={item.label}
            autoFocus={i === 0}
            onSelect={() => navigate(item.screen)}
          />
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.surface900,
    padding: 48,
  },
  header: {
    marginBottom: 48,
  },
  title: {
    fontSize: 48,
    fontWeight: '800',
    color: colors.white,
  },
  subtitle: {
    marginTop: 8,
    fontSize: 16,
    color: colors.surface400,
  },
  buildTag: {
    marginTop: 4,
    fontSize: 14,
    fontWeight: '700',
    color: colors.focus,
  },
  counts: {
    marginTop: 4,
    fontSize: 14,
    color: colors.surface500,
  },
  nav: {
    flexDirection: 'row',
    gap: 16,
  },
});
