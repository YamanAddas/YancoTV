import { describe, it, expect } from 'vitest';
import { groupCategoriesSmart } from '@yancotv/core';

/**
 * Tests for the grouping function the app actually uses.
 *
 * This file previously covered `groupCategories`, which had been deprecated in
 * favour of `groupCategoriesSmart` and was called by nothing but these tests —
 * so the only grouping coverage in the repo pointed at dead code while the live
 * path had none. The deprecated function is gone; these assertions were written
 * against observed behaviour of the replacement and then checked for
 * sensibleness rather than simply enshrining whatever it happened to do.
 *
 * `groupCategoriesSmart` organises raw provider group names under detected
 * language/country sections. Group names are shown in FULL — the prefix is a
 * routing signal, never stripped from the label, because the full name is also
 * what content filtering keys on.
 */
describe('groupCategoriesSmart', () => {
  const names = (children: { originalGroupName: string }[]) =>
    children.map((c) => c.originalGroupName);

  it('returns empty structures for no input', () => {
    expect(groupCategoriesSmart([])).toEqual({ sections: [], ungrouped: [] });
  });

  it('groups by a language prefix', () => {
    const r = groupCategoriesSmart(['AR | BEIN SPORTS', 'AR | MBC']);
    expect(r.sections).toHaveLength(1);
    expect(r.sections[0].key).toBe('ar');
    expect(r.sections[0].label).toBe('Arabic');
    expect(names(r.sections[0].children)).toEqual(['AR | BEIN SPORTS', 'AR | MBC']);
    expect(r.ungrouped).toEqual([]);
  });

  it('keeps the full original group name, prefix included', () => {
    // The label doubles as the content filter key, so stripping the prefix
    // here would silently break filtering as well as the display.
    const r = groupCategoriesSmart(['AR | MBC', 'AR | ROTANA']);
    expect(names(r.sections[0].children)).toContain('AR | MBC');
  });

  it('recognises a prefix regardless of which separator follows it', () => {
    const pipe = groupCategoriesSmart(['AR | MBC', 'AR | ROTANA']);
    const dash = groupCategoriesSmart(['AR - MBC', 'AR - ROTANA']);
    expect(pipe.sections[0].key).toBe(dash.sections[0].key);
  });

  it('detects Arabic from the script alone, with no prefix', () => {
    const r = groupCategoriesSmart(['قنوات عربية', 'أفلام']);
    expect(r.sections).toHaveLength(1);
    expect(r.sections[0].key).toBe('ar');
    expect(r.ungrouped).toEqual([]);
  });

  it('leaves names with no language signal ungrouped', () => {
    const r = groupCategoriesSmart(['Sports', 'Music']);
    expect(r.sections).toEqual([]);
    expect(names(r.ungrouped).sort()).toEqual(['Music', 'Sports']);
  });

  /**
   * A section of one is noise: it costs a collapsible header to show a single
   * row. Pinned because it is the rule most likely to be "simplified" away by
   * someone who sees a detected prefix land in `ungrouped` and reads it as a
   * detection failure.
   */
  it('does not create a section for a single member', () => {
    const r = groupCategoriesSmart(['EN | NEWS']);
    expect(r.sections).toEqual([]);
    expect(names(r.ungrouped)).toEqual(['EN | NEWS']);
  });

  it('needs two members of the SAME language, not two members overall', () => {
    const r = groupCategoriesSmart(['AR | MBC', 'EN | NEWS']);
    expect(r.sections).toEqual([]);
    expect(names(r.ungrouped).sort()).toEqual(['AR | MBC', 'EN | NEWS']);
  });

  it('separates several languages into their own sections', () => {
    const r = groupCategoriesSmart([
      'AR | MBC', 'AR | ROTANA',
      'FR | CINEMA', 'FR | SPORT',
      'DE | NEWS', 'DE | SPORT',
    ]);
    expect(r.sections.map((s) => s.key).sort()).toEqual(['ar', 'de', 'fr']);
    for (const section of r.sections) expect(section.children).toHaveLength(2);
    expect(r.ungrouped).toEqual([]);
  });

  it('mixes grouped and ungrouped in one result', () => {
    const r = groupCategoriesSmart(['AR | MBC', 'AR | ROTANA', 'Sports']);
    expect(r.sections).toHaveLength(1);
    expect(names(r.ungrouped)).toEqual(['Sports']);
  });

  it('survives empty strings and duplicates without throwing', () => {
    expect(() => groupCategoriesSmart(['', '', 'AR | MBC', 'AR | MBC'])).not.toThrow();
  });

  it('gives every section a stable key and a human label', () => {
    const r = groupCategoriesSmart(['FR | CINEMA', 'FR | SPORT']);
    for (const s of r.sections) {
      expect(s.key).toBeTruthy();
      expect(s.label).toBeTruthy();
      // The icon is optional — a detected language need not have a flag.
      expect(s.icon === null || typeof s.icon === 'string').toBe(true);
    }
  });
});
