import React from 'react';
import { StatusBar, StyleSheet, Text, View } from 'react-native';
import { TvButton } from '../components/tv/TvButton';
import { useNavStore } from '../stores/nav-store';
import { useSourcesStore } from '../stores/sources-store';

export function PlayerScreen() {
  const back = useNavStore((s) => s.back);
  const selectedId = useNavStore((s) => s.selectedChannelId);
  const channel = useSourcesStore((s) =>
    s.channels.find((c) => c.id === selectedId),
  );

  return (
    <View style={styles.centered}>
      <StatusBar barStyle="light-content" backgroundColor="#000" />
      <Text style={styles.title}>Playback not yet wired</Text>
      <Text style={styles.detail}>
        {channel ? channel.title : 'No channel selected'}
      </Text>
      {channel?.streamUrl ? (
        <Text style={styles.url} numberOfLines={2}>
          {channel.streamUrl}
        </Text>
      ) : null}
      <View style={styles.backBtn}>
        <TvButton label="Back" onSelect={back} autoFocus active />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#0a0a0f',
    padding: 48,
  },
  title: {
    color: '#fff',
    fontSize: 22,
    fontWeight: '700',
    marginBottom: 8,
  },
  detail: {
    color: '#9ca3af',
    fontSize: 16,
    marginBottom: 16,
  },
  url: {
    color: '#6b7280',
    fontSize: 12,
    marginBottom: 24,
    textAlign: 'center',
  },
  backBtn: {
    width: 160,
  },
});
