import React, { useCallback, useMemo, useState } from 'react';
import {
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { prettifyGroupName } from '@yancotv/core';
import {
  useCategoryGroups,
  type EnhancedSection,
} from '../../hooks/use-category-groups';
import type { GroupContentType } from '../../stores/group-preferences-store';
import { colors, radii, spacing } from '../../styles/theme';

/**
 * Mobile mirror of the desktop `CategorySidebar`. Same information model —
 * pinned sections, language sections, ungrouped — but a D-pad-friendly
 * interaction model:
 *   - Tapping a section label selects all its children (array select).
 *   - Tapping the chevron expands/collapses children.
 *   - Long-press on a section or child opens the action sheet (Pin/Hide).
 * Drag-to-reorder is intentionally omitted — not natural on TV remotes and
 * desktop-synced sort order already covers the motivating use case.
 */

export type CategorySelection = string | string[] | null;

interface Props {
  categories: string[];
  selected: CategorySelection;
  onSelect: (selection: CategorySelection) => void;
  contentType: GroupContentType;
  categoryCounts?: Map<string, number>;
  totalCount?: number;
}

interface ActionSheetTarget {
  groupKey: string;
  label: string;
  isPinned: boolean;
  isHidden: boolean;
  kind: 'section' | 'child';
}

function isChildSelected(selected: CategorySelection, groupName: string) {
  if (selected === groupName) return true;
  if (Array.isArray(selected) && selected.includes(groupName)) return true;
  return false;
}

function isSectionSelected(
  selected: CategorySelection,
  children: { originalGroupName: string }[],
) {
  if (!Array.isArray(selected)) return false;
  const names = children.map((c) => c.originalGroupName);
  if (selected.length !== names.length) return false;
  const set = new Set(selected);
  return names.every((n) => set.has(n));
}

function sectionHasChildSelected(
  selected: CategorySelection,
  children: { originalGroupName: string }[],
) {
  if (!selected || Array.isArray(selected)) return false;
  return children.some((c) => c.originalGroupName === selected);
}

export function CategorySidebar({
  categories,
  selected,
  onSelect,
  contentType,
  categoryCounts,
  totalCount,
}: Props) {
  const {
    grouped,
    expandedGroups,
    toggleGroup,
    onTogglePin,
    onToggleHide,
  } = useCategoryGroups(categories, contentType);

  const [search, setSearch] = useState('');
  const [actionTarget, setActionTarget] = useState<ActionSheetTarget | null>(
    null,
  );

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return grouped;
    const filterSection = (s: EnhancedSection): EnhancedSection | null => {
      const label = (s.customName || s.label).toLowerCase();
      if (label.includes(q)) return s;
      const matching = s.children.filter((c) =>
        c.originalGroupName.toLowerCase().includes(q),
      );
      if (matching.length === 0) return null;
      return { ...s, children: matching };
    };
    return {
      pinned: grouped.pinned
        .map(filterSection)
        .filter(Boolean) as EnhancedSection[],
      sections: grouped.sections
        .map(filterSection)
        .filter(Boolean) as EnhancedSection[],
      ungrouped: grouped.ungrouped.filter((u) =>
        u.originalGroupName.toLowerCase().includes(q),
      ),
    };
  }, [grouped, search]);

  const sectionCount = useCallback(
    (section: EnhancedSection) => {
      if (!categoryCounts) return undefined;
      let total = 0;
      for (const c of section.children) {
        total += categoryCounts.get(c.originalGroupName) ?? 0;
      }
      return total;
    },
    [categoryCounts],
  );

  const openSectionMenu = useCallback(
    (section: EnhancedSection) => {
      setActionTarget({
        groupKey: section.key,
        label: section.customName || section.label,
        isPinned: section.isPinned,
        isHidden: false,
        kind: 'section',
      });
    },
    [],
  );

  const openChildMenu = useCallback((groupKey: string) => {
    setActionTarget({
      groupKey,
      label: prettifyGroupName(groupKey),
      isPinned: false,
      isHidden: false,
      kind: 'child',
    });
  }, []);

  const handleAction = useCallback(
    async (action: 'pin' | 'hide') => {
      if (!actionTarget) return;
      const { groupKey } = actionTarget;
      setActionTarget(null);
      if (action === 'pin') await onTogglePin(groupKey);
      else await onToggleHide(groupKey);
    },
    [actionTarget, onTogglePin, onToggleHide],
  );

  if (categories.length === 0) return null;

  const allSelected = selected === null;
  const hasSections =
    filtered.pinned.length > 0 || filtered.sections.length > 0;

  const renderSection = (section: EnhancedSection) => {
    const active = isSectionSelected(selected, section.children);
    const hasActive = sectionHasChildSelected(selected, section.children);
    const expanded = expandedGroups.has(section.key);
    const count = sectionCount(section);

    return (
      <View key={section.key} style={styles.section}>
        <View style={styles.sectionHeader}>
          <Pressable
            onPress={() => toggleGroup(section.key)}
            style={({ pressed, focused }) => [
              styles.chevronButton,
              (pressed || focused) && styles.chevronButtonFocus,
            ]}
          >
            <Text style={styles.chevron}>{expanded ? '\u25BE' : '\u25B8'}</Text>
          </Pressable>
          <Pressable
            onPress={() =>
              onSelect(section.children.map((c) => c.originalGroupName))
            }
            onLongPress={() => openSectionMenu(section)}
            style={({ pressed, focused }) => [
              styles.sectionLabelButton,
              active && styles.sectionLabelActive,
              hasActive && !active && styles.sectionLabelTinted,
              (pressed || focused) && !active && styles.rowFocus,
            ]}
          >
            {section.icon ? (
              <Text style={styles.sectionIcon}>{section.icon}</Text>
            ) : null}
            <Text
              style={[
                styles.sectionLabel,
                active && styles.sectionLabelTextActive,
              ]}
              numberOfLines={1}
            >
              {section.customName || section.label}
            </Text>
            <CountBadge count={count} active={active} />
          </Pressable>
        </View>

        {expanded ? (
          <View style={styles.childList}>
            {section.children.map((child) => {
              const childActive = isChildSelected(
                selected,
                child.originalGroupName,
              );
              const childCount = categoryCounts?.get(child.originalGroupName);
              return (
                <Pressable
                  key={child.originalGroupName}
                  onPress={() => onSelect(child.originalGroupName)}
                  onLongPress={() => openChildMenu(child.originalGroupName)}
                  style={({ pressed, focused }) => [
                    styles.childButton,
                    childActive && styles.childButtonActive,
                    (pressed || focused) && !childActive && styles.rowFocus,
                  ]}
                >
                  <Text
                    style={[
                      styles.childLabel,
                      childActive && styles.childLabelActive,
                    ]}
                    numberOfLines={1}
                  >
                    {prettifyGroupName(child.originalGroupName)}
                  </Text>
                  <CountBadge count={childCount} active={childActive} />
                </Pressable>
              );
            })}
          </View>
        ) : null}
      </View>
    );
  };

  return (
    <View style={styles.root}>
      <TextInput
        value={search}
        onChangeText={setSearch}
        placeholder="Filter groups..."
        placeholderTextColor={colors.surface500}
        style={styles.search}
      />

      <ScrollView contentContainerStyle={styles.scrollContent}>
        <Pressable
          onPress={() => onSelect(null)}
          style={({ pressed, focused }) => [
            styles.allButton,
            allSelected && styles.allButtonActive,
            (pressed || focused) && !allSelected && styles.rowFocus,
          ]}
        >
          <Text
            style={[styles.allLabel, allSelected && styles.allLabelActive]}
          >
            All
          </Text>
          <CountBadge count={totalCount} active={allSelected} />
        </Pressable>

        {filtered.pinned.length > 0 ? (
          <>
            <Text style={styles.groupHeading}>PINNED</Text>
            {filtered.pinned.map(renderSection)}
            <View style={styles.divider} />
          </>
        ) : null}

        {filtered.sections.map(renderSection)}

        {hasSections && filtered.ungrouped.length > 0 ? (
          <>
            <View style={styles.divider} />
            <Text style={styles.groupHeading}>OTHER</Text>
          </>
        ) : null}

        {filtered.ungrouped.map((child) => {
          const childActive = isChildSelected(
            selected,
            child.originalGroupName,
          );
          const childCount = categoryCounts?.get(child.originalGroupName);
          return (
            <Pressable
              key={child.originalGroupName}
              onPress={() => onSelect(child.originalGroupName)}
              onLongPress={() => openChildMenu(child.originalGroupName)}
              style={({ pressed, focused }) => [
                styles.ungroupedButton,
                childActive && styles.childButtonActive,
                (pressed || focused) && !childActive && styles.rowFocus,
              ]}
            >
              <Text
                style={[
                  styles.childLabel,
                  childActive && styles.childLabelActive,
                ]}
                numberOfLines={1}
              >
                {child.originalGroupName}
              </Text>
              <CountBadge count={childCount} active={childActive} />
            </Pressable>
          );
        })}
      </ScrollView>

      <Modal
        visible={!!actionTarget}
        transparent
        animationType="fade"
        onRequestClose={() => setActionTarget(null)}
      >
        <Pressable
          style={styles.modalBackdrop}
          onPress={() => setActionTarget(null)}
        >
          <Pressable
            style={styles.actionSheet}
            onPress={(e) => e.stopPropagation()}
          >
            <Text style={styles.actionSheetTitle} numberOfLines={2}>
              {actionTarget?.label}
            </Text>
            <ActionRow
              label={actionTarget?.isPinned ? 'Unpin' : 'Pin'}
              onPress={() => handleAction('pin')}
            />
            <ActionRow label="Hide" onPress={() => handleAction('hide')} />
            <ActionRow
              label="Cancel"
              onPress={() => setActionTarget(null)}
              muted
            />
          </Pressable>
        </Pressable>
      </Modal>
    </View>
  );
}

function CountBadge({
  count,
  active,
}: {
  count?: number;
  active: boolean;
}) {
  if (count == null) return null;
  return (
    <Text style={[styles.countBadge, active && styles.countBadgeActive]}>
      {count.toLocaleString()}
    </Text>
  );
}

function ActionRow({
  label,
  onPress,
  muted,
}: {
  label: string;
  onPress: () => void;
  muted?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={({ pressed, focused }) => [
        styles.actionRow,
        (pressed || focused) && styles.actionRowFocus,
      ]}
    >
      <Text style={[styles.actionRowText, muted && styles.actionRowTextMuted]}>
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: {
    width: 260,
    flex: 1,
    backgroundColor: colors.glassSubtle,
    borderRightWidth: 1,
    borderRightColor: colors.glassBorderSoft,
    paddingHorizontal: spacing.sm,
    paddingTop: spacing.sm,
  },
  search: {
    backgroundColor: colors.surface800,
    color: colors.surface100,
    fontSize: 13,
    borderRadius: radii.md,
    paddingHorizontal: spacing.sm + 2,
    paddingVertical: 8,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
    marginBottom: spacing.sm,
  },
  scrollContent: {
    paddingBottom: spacing.xl,
  },
  allButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: radii.md,
    marginBottom: 4,
  },
  allButtonActive: {
    backgroundColor: 'rgba(0, 255, 170, 0.10)',
  },
  allLabel: {
    flex: 1,
    color: colors.surface200,
    fontSize: 13,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  allLabelActive: {
    color: colors.accent,
  },
  groupHeading: {
    paddingHorizontal: 10,
    paddingTop: spacing.sm,
    paddingBottom: 4,
    color: colors.surface500,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 2,
  },
  divider: {
    height: 1,
    backgroundColor: 'rgba(255,255,255,0.05)',
    marginVertical: spacing.sm,
    marginHorizontal: spacing.xs,
  },
  section: {
    marginBottom: 2,
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'stretch',
  },
  chevronButton: {
    width: 28,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radii.sm,
  },
  chevronButtonFocus: {
    backgroundColor: 'rgba(255,255,255,0.04)',
  },
  chevron: {
    color: colors.surface400,
    fontSize: 10,
  },
  sectionLabelButton: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 8,
    borderRadius: radii.md,
  },
  sectionLabelActive: {
    backgroundColor: 'rgba(0, 255, 170, 0.12)',
  },
  sectionLabelTinted: {
    backgroundColor: 'rgba(0, 255, 170, 0.04)',
  },
  sectionIcon: {
    fontSize: 14,
    marginRight: 6,
  },
  sectionLabel: {
    flex: 1,
    color: colors.surface100,
    fontSize: 13,
    fontWeight: '700',
    letterSpacing: 0.3,
  },
  sectionLabelTextActive: {
    color: colors.accent,
  },
  childList: {
    marginLeft: 18,
    borderLeftWidth: 1,
    borderLeftColor: 'rgba(255,255,255,0.05)',
    paddingLeft: 6,
  },
  childButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: radii.sm,
    marginVertical: 1,
  },
  childButtonActive: {
    backgroundColor: 'rgba(0, 255, 170, 0.10)',
  },
  childLabel: {
    flex: 1,
    color: colors.surface300,
    fontSize: 12,
  },
  childLabelActive: {
    color: colors.accent,
    fontWeight: '600',
  },
  ungroupedButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderRadius: radii.md,
    marginVertical: 1,
  },
  rowFocus: {
    backgroundColor: 'rgba(255,255,255,0.04)',
  },
  countBadge: {
    marginLeft: 8,
    fontSize: 10,
    fontWeight: '700',
    color: colors.surface500,
    backgroundColor: 'rgba(255,255,255,0.05)',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: radii.sm,
    minWidth: 22,
    textAlign: 'center',
  },
  countBadgeActive: {
    color: colors.accent,
    backgroundColor: 'rgba(0, 255, 170, 0.12)',
  },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.55)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  actionSheet: {
    width: 320,
    maxWidth: '90%',
    backgroundColor: colors.surface900,
    borderRadius: radii.lg,
    borderWidth: 1,
    borderColor: colors.glassBorder,
    paddingVertical: spacing.sm,
  },
  actionSheetTitle: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    color: colors.surface200,
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 0.4,
    textTransform: 'uppercase',
  },
  actionRow: {
    paddingHorizontal: spacing.md,
    paddingVertical: 14,
  },
  actionRowFocus: {
    backgroundColor: 'rgba(0, 255, 170, 0.08)',
  },
  actionRowText: {
    color: colors.accent,
    fontSize: 15,
    fontWeight: '600',
  },
  actionRowTextMuted: {
    color: colors.surface400,
  },
});
