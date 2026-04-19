import React from 'react';
import { FlatList, StyleSheet, Text, View } from 'react-native';
import type { ContentItem } from '@yancotv/core';
import { ContentCard, type CardVariant } from '../cards/ContentCard';
import { colors, spacing } from '../../styles/theme';

interface Props {
  sameGroup: ContentItem[];
  sameSource: ContentItem[];
  groupName?: string;
  onItemPress: (id: string) => void;
}

/**
 * Related tab — two horizontal rails matching the desktop RelatedTab. The
 * first rail is the "More in <group>" cohort (same groupName, same type);
 * the second is "More from this source" as a broader fallback when the
 * group rail is thin. Rails render as FlatLists so D-pad navigates each
 * axis cleanly and scroll state persists across tab switches.
 */
export function RelatedTab({
  sameGroup,
  sameSource,
  groupName,
  onItemPress,
}: Props) {
  const hasGroup = sameGroup.length > 0;
  const hasSource = sameSource.length > 0;

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
          title={groupName ? `More in ${groupName}` : 'More like this'}
          data={sameGroup}
          onItemPress={onItemPress}
        />
      ) : null}
      {hasSource ? (
        <Rail
          title="More from this source"
          data={sameSource}
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
        renderItem={({ item }) => {
          const variant: CardVariant = item.type === 'live' ? 'hex' : 'poster';
          const width = variant === 'hex' ? 130 : 120;
          return (
            <View style={{ width }}>
              <ContentCard
                title={item.cleanTitle || item.title}
                subtitle={item.groupName}
                imageUrl={item.logoUrl}
                variant={variant}
                width={width}
                onPress={() => onItemPress(item.id)}
              />
            </View>
          );
        }}
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
  },
});
