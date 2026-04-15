import { describe, it, expect } from 'vitest';
import { groupCategories } from '../../src/renderer/utils/category-grouping';

describe('Category Grouping', () => {
  describe('groupCategories', () => {
    it('returns empty groups and ungrouped for empty input', () => {
      const result = groupCategories([]);
      expect(result.groups).toEqual([]);
      expect(result.ungrouped).toEqual([]);
    });

    it('puts single categories into ungrouped', () => {
      const result = groupCategories(['News', 'Sports', 'Music']);
      expect(result.groups).toEqual([]);
      expect(result.ungrouped).toEqual(['Music', 'News', 'Sports']); // sorted
    });

    it('groups categories sharing a pipe-separated prefix', () => {
      const result = groupCategories([
        'AR | Sports',
        'AR | News',
        'AR | Movies',
      ]);
      expect(result.groups).toHaveLength(1);
      expect(result.groups[0].prefix).toBe('AR');
      expect(result.groups[0].children).toEqual([
        'AR | Movies',
        'AR | News',
        'AR | Sports',
      ]); // sorted by suffix
      expect(result.groups[0].childLabels).toEqual(['Movies', 'News', 'Sports']);
      expect(result.ungrouped).toEqual([]);
    });

    it('groups categories sharing a dash-separated prefix', () => {
      const result = groupCategories([
        'UK - Drama',
        'UK - Comedy',
        'UK - News',
      ]);
      expect(result.groups).toHaveLength(1);
      expect(result.groups[0].prefix).toBe('UK');
      expect(result.groups[0].childLabels).toEqual(['Comedy', 'Drama', 'News']);
    });

    it('groups categories sharing a slash-separated prefix', () => {
      const result = groupCategories([
        'Arabic / Sports',
        'Arabic / Entertainment',
      ]);
      expect(result.groups).toHaveLength(1);
      expect(result.groups[0].prefix).toBe('Arabic');
    });

    it('groups categories sharing a colon-separated prefix', () => {
      const result = groupCategories([
        'FR : Cinema',
        'FR : Documentaire',
      ]);
      expect(result.groups).toHaveLength(1);
      expect(result.groups[0].prefix).toBe('FR');
    });

    it('requires 2+ categories to form a group', () => {
      const result = groupCategories([
        'US | Sports',
        'UK | News',
        'UK | Drama',
      ]);
      // UK forms a group (2 members), US is ungrouped (only 1)
      expect(result.groups).toHaveLength(1);
      expect(result.groups[0].prefix).toBe('UK');
      expect(result.ungrouped).toContain('US | Sports');
    });

    it('handles mixed grouped and ungrouped categories', () => {
      const result = groupCategories([
        'AR | News',
        'AR | Sports',
        'General',
        'Music',
      ]);
      expect(result.groups).toHaveLength(1);
      expect(result.groups[0].prefix).toBe('AR');
      expect(result.ungrouped).toEqual(['General', 'Music']); // sorted
    });

    it('handles multiple different prefix groups', () => {
      const result = groupCategories([
        'US | News',
        'US | Sports',
        'UK | Drama',
        'UK | Comedy',
        'FR | Cinema',
        'FR | Documentaire',
      ]);
      expect(result.groups).toHaveLength(3);
      expect(result.groups.map((g) => g.prefix)).toEqual(['FR', 'UK', 'US']); // sorted
    });

    it('prefers pipe separator over dash (higher priority)', () => {
      // " | " is checked before " - " in SEPARATORS
      const result = groupCategories([
        'AR | Sport - Live',
        'AR | Sport - Replay',
      ]);
      expect(result.groups).toHaveLength(1);
      expect(result.groups[0].prefix).toBe('AR');
      expect(result.groups[0].childLabels).toEqual(['Sport - Live', 'Sport - Replay']);
    });

    it('skips empty strings', () => {
      const result = groupCategories(['', 'News', '', 'Sports']);
      expect(result.groups).toEqual([]);
      expect(result.ungrouped).toEqual(['News', 'Sports']);
    });

    it('sorts groups alphabetically by prefix', () => {
      const result = groupCategories([
        'ZZ | A',
        'ZZ | B',
        'AA | C',
        'AA | D',
      ]);
      expect(result.groups[0].prefix).toBe('AA');
      expect(result.groups[1].prefix).toBe('ZZ');
    });

    it('sorts ungrouped categories alphabetically', () => {
      const result = groupCategories(['Zebra', 'Apple', 'Mango']);
      expect(result.ungrouped).toEqual(['Apple', 'Mango', 'Zebra']);
    });

    it('handles separator-only strings gracefully', () => {
      // " | " at start means empty prefix → no parse
      const result = groupCategories([' | Something', 'Normal']);
      // tryParse requires idx > 0 for prefix, so " | Something" starts at idx=0 → no parse
      expect(result.ungrouped).toContain(' | Something');
    });

    it('handles categories with only prefix and no suffix', () => {
      // "AR | " — suffix would be empty after trim → tryParse returns null
      const result = groupCategories(['AR | ', 'AR | Sports']);
      // "AR | " has empty suffix, should fall into ungrouped
      // "AR | Sports" has only 1 member so also ungrouped
      expect(result.groups).toEqual([]);
    });
  });
});
