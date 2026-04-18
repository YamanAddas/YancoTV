import React, { useMemo } from 'react';
import { View, Text, FlatList, StatusBar, StyleSheet } from 'react-native';
import type { ContentType } from '@yancotv/core';
import { TvButton } from '../components/tv/TvButton';
import { Focusable } from '../focus/Focusable';
import { useNavStore } from '../stores/nav-store';
import { useSourcesStore } from '../stores/sources-store';
import { colors } from '../styles/theme';

interface Props {
  type: ContentType;
  title: string;
}

export function ChannelListScreen({ type, title }: Props) {
  const navigate = useNavStore((s) => s.navigate);
  const openDetail = useNavStore((s) => s.openDetail);
  const channels = useSourcesStore((s) => s.channels);

  const items = useMemo(
    () => channels.filter((ch) => ch.type === type),
    [channels, type],
  );

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor={colors.surface900} />

      <View style={styles.header}>
        <View>
          <Text style={styles.title}>{title}</Text>
          <Text style={styles.count}>
            {items.length} {items.length === 1 ? 'item' : 'items'}
          </Text>
        </View>
        <TvButton label="Back" onSelect={() => navigate('home')} />
      </View>

      {items.length === 0 ? (
        <View style={styles.emptyBox}>
          <Text style={styles.emptyText}>
            No {title.toLowerCase()} yet. Add a source first.
          </Text>
          <View style={styles.emptyCta}>
            <TvButton label="Go to Sources" onSelect={() => navigate('sources')} autoFocus />
          </View>
        </View>
      ) : (
        <FlatList
          data={items}
          keyExtractor={(item) => item.id}
          contentContainerStyle={styles.listContent}
          renderItem={({ item, index }) => (
            <Focusable
              hasTVPreferredFocus={index === 0}
              onSelect={() => openDetail(item.id)}
            >
              {({ focused }) => (
                <View
                  style={[
                    styles.row,
                    focused ? styles.rowFocused : styles.rowDefault,
                  ]}
                >
                  <Text style={styles.rowTitle} numberOfLines={1}>
                    {item.title}
                  </Text>
                  {item.groupName && (
                    <Text
                      style={[
                        styles.rowGroup,
                        focused ? styles.rowGroupFocused : styles.rowGroupDefault,
                      ]}
                      numberOfLines={1}
                    >
                      {item.groupName}
                    </Text>
                  )}
                </View>
              )}
            </Focusable>
          )}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.surface900,
  },
  header: {
    paddingHorizontal: 48,
    paddingTop: 48,
    paddingBottom: 24,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  title: {
    fontSize: 36,
    fontWeight: '800',
    color: colors.white,
  },
  count: {
    marginTop: 4,
    fontSize: 14,
    color: colors.surface500,
  },
  emptyBox: {
    paddingHorizontal: 48,
    paddingTop: 32,
  },
  emptyText: {
    color: colors.surface500,
  },
  emptyCta: {
    marginTop: 16,
    width: 192,
  },
  listContent: {
    paddingHorizontal: 48,
    paddingBottom: 48,
  },
  row: {
    marginBottom: 8,
    borderRadius: 12,
    paddingHorizontal: 20,
    paddingVertical: 12,
  },
  rowDefault: {
    backgroundColor: colors.surface800,
  },
  rowFocused: {
    backgroundColor: colors.brand,
  },
  rowTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: colors.white,
  },
  rowGroup: {
    marginTop: 4,
    fontSize: 12,
  },
  rowGroupDefault: {
    color: colors.surface500,
  },
  rowGroupFocused: {
    color: 'rgba(255,255,255,0.8)',
  },
});
