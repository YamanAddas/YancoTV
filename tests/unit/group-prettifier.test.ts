import { describe, expect, it } from 'vitest';
import { prettifyGroupName } from '@yancotv/core';

describe('prettifyGroupName', () => {
  it('returns empty string for empty input', () => {
    expect(prettifyGroupName('')).toBe('');
    expect(prettifyGroupName(null)).toBe('');
    expect(prettifyGroupName(undefined)).toBe('');
  });

  it('expands a standalone language code', () => {
    expect(prettifyGroupName('AR')).toBe('Arabic');
    expect(prettifyGroupName('TR')).toBe('Turkish');
  });

  it('expands code at the start of a pipe-delimited group', () => {
    expect(prettifyGroupName('AR | Movies')).toBe('Arabic | Movies');
    expect(prettifyGroupName('EN | Sports')).toBe('English | Sports');
  });

  it('expands code at the end of a pipe-delimited group', () => {
    expect(prettifyGroupName('Movies | AR')).toBe('Movies | Arabic');
    expect(prettifyGroupName('Sports | EN')).toBe('Sports | English');
  });

  it('expands code in the middle of multiple separators', () => {
    expect(prettifyGroupName('VIP | TR | Series')).toBe('VIP | Turkish | Series');
  });

  it('handles dash separators', () => {
    expect(prettifyGroupName('AR - Movies')).toBe('Arabic - Movies');
  });

  it('does not rewrite uppercase words that are not language codes', () => {
    expect(prettifyGroupName('VIP | HD | Movies')).toBe('VIP | HD | Movies');
  });

  it('does not rewrite codes inside words without separators', () => {
    // "IN" is a language code but here it's part of "Channel IN 4K" without delimiters.
    expect(prettifyGroupName('Channel IN 4K')).toBe('Channel IN 4K');
  });

  it('handles longer already-spelled tokens (case-insensitive)', () => {
    expect(prettifyGroupName('arabic | movies')).toBe('Arabic | movies');
    expect(prettifyGroupName('TURKISH | news')).toBe('Turkish | news');
  });
});
