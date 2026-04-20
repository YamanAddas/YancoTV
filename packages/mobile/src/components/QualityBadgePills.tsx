import React, { useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors, radii } from '../styles/theme';

// M4R.D.4 — Parses a channel title for TiviMate-style quality tokens and
// renders 1-3 pills. Unknown titles render nothing.
//
// Tiers:
//   uhd    → 4K / UHD / 2160p                 (accent cyan)
//   hd     → 1440p / 1080p / FHD / 720p / HD  (amber)
//   sd     → SD                               (neutral)
//
// The regex walks left-to-right, de-duplicates by tier (so "4K UHD 2160p"
// still yields one pill), and caps at 3 pills. Keeping it cheap so it can
// run inline on every FlashList row without pulling scroll FPS down.

const QUALITY_REGEX = /\b(4K|UHD|2160p|1440p|1080p|FHD|720p|HD|SD)\b/gi;

type Tier = 'uhd' | 'hd' | 'sd';

interface Pill {
  label: string;
  tier: Tier;
}

function tierFor(token: string): Tier {
  const up = token.toUpperCase();
  if (up === '4K' || up === 'UHD' || up === '2160P') return 'uhd';
  if (up === 'SD') return 'sd';
  return 'hd';
}

function labelFor(token: string, tier: Tier): string {
  const up = token.toUpperCase();
  if (tier === 'uhd') {
    if (up === '2160P') return '4K';
    return up; // "4K" or "UHD"
  }
  if (tier === 'sd') return 'SD';
  // HD tier — collapse FHD/1080p/720p/1440p/HD to their own label
  if (up === 'FHD' || up === '1080P') return 'FHD';
  if (up === '1440P') return '2K';
  if (up === '720P') return 'HD';
  return up;
}

export function parseQualityPills(title: string | undefined | null): Pill[] {
  if (!title) return [];
  QUALITY_REGEX.lastIndex = 0;
  const seen = new Set<string>();
  const pills: Pill[] = [];
  let match: RegExpExecArray | null;
  while ((match = QUALITY_REGEX.exec(title)) !== null) {
    const token = match[1];
    const tier = tierFor(token);
    const label = labelFor(token, tier);
    const key = `${tier}:${label}`;
    if (seen.has(key)) continue;
    seen.add(key);
    pills.push({ label, tier });
    if (pills.length === 3) break;
  }
  return pills;
}

interface Props {
  title: string | undefined | null;
}

export function QualityBadgePills({ title }: Props) {
  const pills = useMemo(() => parseQualityPills(title), [title]);
  if (pills.length === 0) return null;
  return (
    <View style={styles.row}>
      {pills.map((p) => (
        <View key={`${p.tier}:${p.label}`} style={[styles.pill, pillStyle(p.tier)]}>
          <Text style={[styles.pillText, pillTextStyle(p.tier)]}>{p.label}</Text>
        </View>
      ))}
    </View>
  );
}

function pillStyle(tier: Tier) {
  if (tier === 'uhd') return styles.pillUhd;
  if (tier === 'hd') return styles.pillHd;
  return styles.pillSd;
}

function pillTextStyle(tier: Tier) {
  if (tier === 'uhd') return styles.pillTextUhd;
  if (tier === 'hd') return styles.pillTextHd;
  return styles.pillTextSd;
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  pill: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: radii.sm,
    borderWidth: 1,
  },
  pillText: {
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  pillUhd: {
    backgroundColor: 'rgba(0, 255, 170, 0.12)',
    borderColor: colors.accent,
  },
  pillTextUhd: {
    color: colors.accent,
  },
  pillHd: {
    backgroundColor: 'rgba(251, 191, 36, 0.12)',
    borderColor: colors.amber,
  },
  pillTextHd: {
    color: colors.amber,
  },
  pillSd: {
    backgroundColor: 'rgba(90, 110, 130, 0.18)',
    borderColor: colors.surface500,
  },
  pillTextSd: {
    color: colors.surface300,
  },
});
