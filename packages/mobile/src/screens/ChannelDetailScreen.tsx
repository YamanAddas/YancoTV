import React from 'react';
import {
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useNavStore } from '../stores/nav-store';
import { useSourcesStore } from '../stores/sources-store';
import { colors, radii, spacing } from '../styles/theme';

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
        <Text style={styles.missingTitle}>Channel not found</Text>
        <Pressable
          onPress={back}
          style={({ pressed }) => [styles.primaryBtn, pressed && { opacity: 0.8 }]}
        >
          <Text style={styles.primaryBtnText}>Back</Text>
        </Pressable>
      </View>
    );
  }

  const canPlay = channel.streamUrl.length > 0;

  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.heroRow}>
        <Pressable
          onPress={back}
          style={({ pressed, focused }) => [
            styles.backBtn,
            (pressed || focused) && styles.backBtnFocus,
          ]}
        >
          <Text style={styles.backBtnText}>← Back</Text>
        </Pressable>
      </View>

      <View style={styles.hero}>
        {channel.logoUrl ? (
          <Image
            source={{ uri: channel.logoUrl }}
            style={styles.logo}
            resizeMode="contain"
          />
        ) : (
          <View style={styles.logoPlaceholder}>
            <Text style={styles.logoPlaceholderText}>
              {channel.title.charAt(0).toUpperCase()}
            </Text>
          </View>
        )}

        <View style={styles.heroText}>
          <Text style={styles.eyebrow}>
            {TYPE_LABEL[channel.type] ?? channel.type}
          </Text>
          <Text style={styles.title} numberOfLines={3}>
            {channel.title}
          </Text>
          {channel.groupName ? (
            <Text style={styles.group}>{channel.groupName}</Text>
          ) : null}

          <View style={styles.actions}>
            <Pressable
              onPress={() => canPlay && openPlayer(channel.id)}
              disabled={!canPlay}
              style={({ pressed, focused }) => [
                styles.primaryBtn,
                !canPlay && styles.primaryBtnDisabled,
                focused && canPlay && styles.primaryBtnFocus,
                pressed && { opacity: 0.85 },
              ]}
            >
              <Text
                style={[
                  styles.primaryBtnText,
                  !canPlay && styles.primaryBtnTextDisabled,
                ]}
              >
                {canPlay ? '▶  Play' : 'No stream URL'}
              </Text>
            </Pressable>
          </View>
        </View>
      </View>

      <View style={styles.detailsBox}>
        <Text style={styles.detailsHeader}>Details</Text>
        <DetailRow label="Source" value={source?.name ?? '—'} />
        <DetailRow label="Group" value={channel.groupName ?? '—'} />
        <DetailRow label="TVG-ID" value={channel.tvgId ?? '—'} />
        <DetailRow label="Stream URL" value={channel.streamUrl || '—'} mono />
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
  content: {
    padding: spacing.xl,
    paddingBottom: spacing.xxl,
  },
  missing: {
    flex: 1,
    padding: spacing.xl,
    alignItems: 'flex-start',
  },
  missingTitle: {
    marginBottom: spacing.md,
    fontSize: 24,
    fontWeight: '700',
    color: colors.white,
  },
  heroRow: {
    marginBottom: spacing.md,
  },
  backBtn: {
    alignSelf: 'flex-start',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.06)',
  },
  backBtnFocus: {
    borderColor: colors.accent,
  },
  backBtnText: {
    color: colors.surface200,
    fontSize: 12,
    fontWeight: '700',
  },
  hero: {
    flexDirection: 'row',
    gap: spacing.lg,
    padding: spacing.lg,
    borderRadius: radii.lg,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
    marginBottom: spacing.lg,
  },
  logo: {
    height: 140,
    width: 140,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
  },
  logoPlaceholder: {
    height: 140,
    width: 140,
    borderRadius: radii.md,
    backgroundColor: colors.surface800,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoPlaceholderText: {
    fontSize: 60,
    color: colors.surface500,
    fontWeight: '900',
    fontStyle: 'italic',
  },
  heroText: {
    flex: 1,
  },
  eyebrow: {
    color: colors.accent,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 2,
    textTransform: 'uppercase',
  },
  title: {
    marginTop: 6,
    fontSize: 28,
    fontWeight: '800',
    color: colors.white,
    lineHeight: 32,
  },
  group: {
    marginTop: 6,
    fontSize: 13,
    color: colors.surface400,
  },
  actions: {
    marginTop: spacing.md,
    flexDirection: 'row',
    gap: spacing.sm,
  },
  primaryBtn: {
    paddingHorizontal: 22,
    paddingVertical: 12,
    backgroundColor: colors.accent,
    borderRadius: radii.md,
  },
  primaryBtnDisabled: {
    backgroundColor: colors.surface700,
  },
  primaryBtnFocus: {
    shadowColor: colors.accent,
    shadowOpacity: 0.7,
    shadowRadius: 14,
    shadowOffset: { width: 0, height: 0 },
    elevation: 10,
  },
  primaryBtnText: {
    color: colors.bg,
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 1,
  },
  primaryBtnTextDisabled: {
    color: colors.surface500,
  },
  detailsBox: {
    padding: spacing.lg,
    borderRadius: radii.lg,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
  },
  detailsHeader: {
    marginBottom: spacing.md,
    fontSize: 11,
    fontWeight: '800',
    color: colors.surface400,
    letterSpacing: 2,
    textTransform: 'uppercase',
  },
  detailRow: {
    marginBottom: spacing.md,
  },
  detailLabel: {
    marginBottom: 4,
    fontSize: 10,
    textTransform: 'uppercase',
    color: colors.surface500,
    letterSpacing: 1,
    fontWeight: '700',
  },
  detailValue: {
    fontSize: 13,
    color: colors.white,
  },
  detailValueMono: {
    fontFamily: 'monospace',
    color: colors.surface300,
    fontSize: 11,
  },
});
