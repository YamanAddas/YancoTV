import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { ContentItem, ContentMetadata } from '@yancotv/core';
import { colors, radii, spacing } from '../../styles/theme';

interface Props {
  item: ContentItem;
  metadata: ContentMetadata;
  sourceName?: string;
}

/**
 * Info tab — the text-heavy half of the detail experience. Plot, crew, and
 * technical fields all render conditionally so VOD items with thin metadata
 * collapse to "just the plot" rather than showing a long row of em-dashes.
 */
export function InfoTab({ item, metadata, sourceName }: Props) {
  const plot = metadata.plot;
  const hasCrew = !!(metadata.director || metadata.cast);

  return (
    <View style={styles.root}>
      {plot ? (
        <View style={styles.block}>
          <Text style={styles.blockHeader}>Plot</Text>
          <Text style={styles.plotText}>{plot}</Text>
        </View>
      ) : null}

      {hasCrew ? (
        <View style={styles.block}>
          {metadata.director ? (
            <DetailRow label="Director" value={metadata.director} />
          ) : null}
          {metadata.cast ? (
            <DetailRow label="Cast" value={metadata.cast} />
          ) : null}
        </View>
      ) : null}

      <View style={styles.block}>
        <Text style={styles.blockHeader}>Technical</Text>
        <DetailRow label="Source" value={sourceName ?? '\u2014'} />
        <DetailRow label="Group" value={item.groupName ?? '\u2014'} />
        <DetailRow label="TVG-ID" value={item.tvgId ?? '\u2014'} />
        {item.streamUrl ? (
          <DetailRow label="Stream URL" value={item.streamUrl} mono />
        ) : null}
      </View>
    </View>
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
    paddingHorizontal: spacing.xl,
  },
  block: {
    padding: spacing.lg,
    borderRadius: radii.lg,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
    marginBottom: spacing.lg,
  },
  blockHeader: {
    marginBottom: spacing.md,
    fontSize: 11,
    fontWeight: '800',
    color: colors.surface400,
    letterSpacing: 2,
    textTransform: 'uppercase',
  },
  plotText: {
    fontSize: 14,
    color: colors.surface100,
    lineHeight: 22,
  },
  detailRow: { marginBottom: spacing.md },
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
