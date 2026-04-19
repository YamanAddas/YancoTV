import React, { useMemo } from 'react';
import { FlatList, StyleSheet, Text, View } from 'react-native';
import { prettifyGroupName, type ContentItem } from '@yancotv/core';
import { HexCard } from '../cards/HexCard';
import { colors, spacing } from '../../styles/theme';

const HEX_WIDTH = 130;
const RAIL_LIMIT = 12;

interface Props {
  sameGroup: ContentItem[];
  sameSource: ContentItem[];
  groupName?: string;
  onItemPress: (id: string) => void;
}

/**
 * Related tab — two HexCard horizontal rails, matching the desktop Sprint 11B
 * layout. "More in <group>" is the same-type+same-group cohort; "You might
 * also like" is the broader same-source fallback, deduped against the first
 * rail so the user never sees the same title twice. Rails are FlatLists so
 * D-pad traverses each axis cleanly and scroll state survives tab switches.
 */
export function RelatedTab({
  sameGroup,
  sameSource,
  groupName,
  onItemPress,
}: Props) {
  const shownIds = useMemo(
    () => new Set(sameGroup.slice(0, RAIL_LIMIT).map((it) => it.id)),
    [sameGroup],
  );
  const sourceRail = useMemo(
    () =>
      sameSource.filter((it) => !shownIds.has(it.id)).slice(0, RAIL_LIMIT),
    [sameSource, shownIds],
  );
  const groupRail = useMemo(() => sameGroup.slice(0, RAIL_LIMIT), [sameGroup]);

  const hasGroup = groupRail.length > 0;
  const hasSource = sourceRail.length > 0;

  if (!hasGroup && !hasSource) {
    return (
      <View style={styles.root}>
        <View style={styles.empty}>
          <Text style={styles.emptyText}>Nothing related yet.</Text>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.root}>
      {hasGroup ? (
        <Rail
          title={
            groupName
              ? `More in ${prettifyGroupName(groupName)}`
              : 'More like this'
          }
          data={groupRail}
          onItemPress={onItemPress}
        />
      ) : null}
      {hasSource ? (
        <Rail
          title="You might also like"
          data={sourceRail}
          onItemPress={onItemPress}
        />
      ) : null}
    </View>
  );
}

function Rail({
  title,
  data,
  onItemPress,
}: {
  title: string;
  data: ContentItem[];
  onItemPress: (id: string) => void;
}) {
  return (
    <View style={styles.rail}>
      <Text style={styles.railHeader}>{title}</Text>
      <FlatList
        horizontal
        data={data}
        keyExtractor={(it) => it.id}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.railContent}
        renderItem={({ item }) => (
          <View style={styles.cell}>
            <HexCard
              title={item.cleanTitle || item.title}
              subtitle={item.groupName}
              imageUrl={item.logoUrl}
              width={HEX_WIDTH}
              onPress={() => onItemPress(item.id)}
            />
          </View>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    paddingHorizontal: spacing.xl,
  },
  empty: {
    padding: spacing.xl,
    borderRadius: 14,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: colors.surface700,
    backgroundColor: 'rgba(15, 20, 28, 0.5)',
    alignItems: 'center',
  },
  emptyText: {
    color: colors.surface500,
    fontSize: 13,
  },
  rail: {
    marginBottom: spacing.lg,
  },
  railHeader: {
    fontSize: 14,
    fontWeight: '800',
    color: colors.white,
    marginBottom: spacing.sm,
  },
  railContent: {
    gap: 12,
    paddingRight: spacing.xl,
  },
  cell: {
    width: HEX_WIDTH,
  },
});
