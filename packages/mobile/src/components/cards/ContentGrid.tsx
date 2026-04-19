import React, { useCallback, useMemo, useState } from 'react';
import {
  StyleSheet,
  View,
  type LayoutChangeEvent,
  type ViewStyle,
} from 'react-native';
import { FlashList, type ListRenderItem } from '@shopify/flash-list';
import type { ContentItem } from '@yancotv/core';
import { ContentCard, type CardVariant } from './ContentCard';
import { spacing } from '../../styles/theme';

// Column metrics shared with ChannelListScreen's empty-state math. Tweaking
// these changes card density for the whole app.
const TARGET_CARD_WIDTH: Record<CardVariant, number> = {
  hex: 130,
  poster: 120,
  landscape: 220,
};

// FlashList expects an estimated row height (not item height) for virtualization
// to allocate recycle pools. 2:3 poster at ~120 wide => ~180 tall + text ≈ 220.
// 16:9 landscape at ~220 => ~124 + text ≈ 160. Hex cards are taller due to the
// honeycomb silhouette plus caption.
const ESTIMATED_ROW_HEIGHT: Record<CardVariant, number> = {
  hex: 160,
  poster: 220,
  landscape: 160,
};

const GAP = 12;
const HORIZONTAL_PADDING = spacing.xl;

interface Props {
  data: ContentItem[];
  variant: CardVariant;
  onOpen: (id: string) => void;
  contentContainerStyle?: ViewStyle;
}

/**
 * Virtualized grid for ContentItem lists — Live TV (hex), Movies/Series
 * (poster), EPG/timeshift (landscape). Column count is derived from the
 * actual container width via onLayout so the grid is honest inside any
 * ancestor layout (permanent drawer, category sidebar, phone tabs).
 * TV focus works out of the box because FlashList mounts a viewport
 * window of real views (no clipping-subviews gotcha like FlatList).
 */
export const ContentGrid = React.memo(function ContentGrid({
  data,
  variant,
  onOpen,
  contentContainerStyle,
}: Props) {
  const [containerW, setContainerW] = useState(0);

  const onLayout = useCallback((e: LayoutChangeEvent) => {
    setContainerW(e.nativeEvent.layout.width);
  }, []);

  const { columns, cardWidth } = useMemo(() => {
    const availableW = Math.max(0, containerW - HORIZONTAL_PADDING * 2);
    const target = TARGET_CARD_WIDTH[variant];
    const cols = Math.max(2, Math.floor(availableW / (target + GAP)));
    const w = Math.floor((availableW - GAP * (cols - 1)) / cols);
    return { columns: cols, cardWidth: w };
  }, [containerW, variant]);

  const renderItem = useCallback<ListRenderItem<ContentItem>>(
    ({ item, index }) => {
      const col = index % columns;
      return (
        <View
          style={[
            styles.cell,
            { width: cardWidth, marginLeft: col === 0 ? 0 : GAP },
          ]}
        >
          <ContentCard
            title={item.title}
            subtitle={item.groupName}
            imageUrl={item.logoUrl}
            variant={variant}
            width={cardWidth}
            onPress={() => onOpen(item.id)}
          />
        </View>
      );
    },
    [cardWidth, columns, variant, onOpen],
  );

  return (
    <View style={styles.container} onLayout={onLayout}>
      {containerW > 0 ? (
        <FlashList
          // `key` forces a fresh recycler when the column count changes
          // (rotation, sidebar collapse, window resize). Without this,
          // FlashList keeps the old layout and overlaps cards.
          key={`grid-${variant}-${columns}`}
          data={data}
          numColumns={columns}
          keyExtractor={keyExtractor}
          renderItem={renderItem}
          estimatedItemSize={ESTIMATED_ROW_HEIGHT[variant]}
          contentContainerStyle={{
            paddingHorizontal: HORIZONTAL_PADDING,
            paddingBottom: spacing.xxl,
            ...contentContainerStyle,
          }}
          removeClippedSubviews={false}
        />
      ) : null}
    </View>
  );
});

const keyExtractor = (it: ContentItem) => it.id;

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  cell: {
    marginBottom: 16,
  },
});
