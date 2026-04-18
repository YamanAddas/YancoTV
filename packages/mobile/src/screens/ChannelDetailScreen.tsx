import React from 'react';
import { View, Text, Image, ScrollView, StatusBar, StyleSheet } from 'react-native';
import { TvButton } from '../components/tv/TvButton';
import { useNavStore } from '../stores/nav-store';
import { useSourcesStore } from '../stores/sources-store';
import { colors } from '../styles/theme';

const TYPE_LABEL: Record<string, string> = {
  live: 'Live channel',
  movie: 'Movie',
  series: 'Series',
};

export function ChannelDetailScreen() {
  const back = useNavStore((s) => s.back);
  const openPlayer = useNavStore((s) => s.openPlayer);
  const selectedId = useNavStore((s) => s.selectedChannelId);
  const channel = useSourcesStore((s) =>
    s.channels.find((c) => c.id === selectedId),
  );
  const source = useSourcesStore((s) =>
    channel ? s.sources.find((src) => src.id === channel.sourceId) : undefined,
  );

  if (!channel) {
    return (
      <View style={styles.missing}>
        <StatusBar barStyle="light-content" backgroundColor={colors.surface900} />
        <Text style={styles.missingTitle}>Channel not found</Text>
        <View style={styles.missingBtn}>
          <TvButton label="Back" onSelect={back} autoFocus />
        </View>
      </View>
    );
  }

  const canPlay = channel.streamUrl.length > 0;
  const handlePlay = () => {
    if (canPlay) openPlayer(channel.id);
  };

  return (
    <ScrollView
      style={styles.root}
      contentContainerStyle={styles.content}
    >
      <StatusBar barStyle="light-content" backgroundColor={colors.surface900} />

      <View style={styles.heroRow}>
        <View style={styles.heroLeft}>
          {channel.logoUrl ? (
            <Image
              source={{ uri: channel.logoUrl }}
              style={styles.logo}
              resizeMode="contain"
            />
          ) : (
            <View style={styles.logoPlaceholder}>
              <Text style={styles.logoPlaceholderText}>?</Text>
            </View>
          )}
          <View style={styles.heroText}>
            <Text style={styles.type}>
              {TYPE_LABEL[channel.type] ?? channel.type}
            </Text>
            <Text style={styles.title} numberOfLines={2}>
              {channel.title}
            </Text>
            {channel.groupName && (
              <Text style={styles.group}>
                {channel.groupName}
              </Text>
            )}
          </View>
        </View>
        <TvButton label="Back" onSelect={back} />
      </View>

      <View style={styles.actions}>
        <TvButton
          label={canPlay ? 'Play' : 'No stream URL'}
          onSelect={handlePlay}
          active={canPlay}
          autoFocus
        />
      </View>

      <View style={styles.detailsBox}>
        <Text style={styles.detailsHeader}>Details</Text>
        <DetailRow label="Source" value={source?.name ?? '—'} />
        <DetailRow label="Group" value={channel.groupName ?? '—'} />
        <DetailRow label="tvg-id" value={channel.tvgId ?? '—'} />
        <DetailRow label="Stream URL" value={channel.streamUrl} mono />
      </View>
    </ScrollView>
  );
}

function DetailRow({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <View style={styles.detailRow}>
      <Text style={styles.detailLabel}>{label}</Text>
      <Text
        style={[styles.detailValue, mono && styles.detailValueMono]}
        selectable
      >
        {value}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.surface900,
  },
  content: {
    padding: 48,
  },
  missing: {
    flex: 1,
    backgroundColor: colors.surface900,
    padding: 48,
  },
  missingTitle: {
    marginBottom: 16,
    fontSize: 24,
    fontWeight: '700',
    color: colors.white,
  },
  missingBtn: {
    width: 128,
  },
  heroRow: {
    marginBottom: 32,
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
  },
  heroLeft: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
  },
  logo: {
    marginRight: 24,
    height: 96,
    width: 96,
    borderRadius: 12,
    backgroundColor: colors.surface700,
  },
  logoPlaceholder: {
    marginRight: 24,
    height: 96,
    width: 96,
    borderRadius: 12,
    backgroundColor: colors.surface700,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoPlaceholderText: {
    fontSize: 30,
    color: colors.surface400,
  },
  heroText: {
    flex: 1,
  },
  type: {
    fontSize: 14,
    textTransform: 'uppercase',
    color: colors.focus,
  },
  title: {
    marginTop: 4,
    fontSize: 36,
    fontWeight: '800',
    color: colors.white,
  },
  group: {
    marginTop: 4,
    fontSize: 16,
    color: colors.surface400,
  },
  actions: {
    marginBottom: 32,
    flexDirection: 'row',
    gap: 12,
  },
  detailsBox: {
    borderRadius: 16,
    backgroundColor: colors.surface800,
    padding: 24,
  },
  detailsHeader: {
    marginBottom: 12,
    fontSize: 18,
    fontWeight: '600',
    color: colors.white,
  },
  detailRow: {
    marginBottom: 12,
  },
  detailLabel: {
    marginBottom: 4,
    fontSize: 12,
    textTransform: 'uppercase',
    color: colors.surface500,
  },
  detailValue: {
    fontSize: 14,
    color: colors.white,
  },
  detailValueMono: {
    fontFamily: 'monospace',
  },
});
