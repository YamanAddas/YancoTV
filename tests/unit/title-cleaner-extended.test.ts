import { describe, it, expect } from 'vitest';
import {
  cleanTitle,
  extractYear,
  extractSeasonEpisode,
  extractShowName,
} from '../../src/main/services/title-cleaner';

describe('Title Cleaner — Extended Edge Cases', () => {
  describe('cleanTitle — real-world provider titles', () => {
    it('cleans typical IPTV provider format: "US: CNN HD [MULTI]"', () => {
      const result = cleanTitle('US: CNN HD [MULTI]');
      expect(result).toBe('CNN');
    });

    it('cleans "001. FR - TF1 FHD"', () => {
      const result = cleanTitle('001. TF1 FHD');
      expect(result).toBe('TF1');
    });

    it('cleans channel with just a logo bracket: "ESPN (Sports)"', () => {
      // Non-tag brackets get stripped by our general bracket pattern
      const result = cleanTitle('ESPN (Sports)');
      expect(result).toBe('ESPN');
    });

    it('preserves titles that have no noise', () => {
      expect(cleanTitle('CNN')).toBe('CNN');
      expect(cleanTitle('BBC One')).toBe('BBC One');
      expect(cleanTitle('The Matrix')).toBe('The Matrix');
    });

    it('handles multiple quality tags', () => {
      const result = cleanTitle('Channel HD FHD');
      expect(result).toBe('Channel');
    });

    it('cleans "UK | Sky Sports [HD] - Backup"', () => {
      const result = cleanTitle('UK | Sky Sports [HD] - Backup');
      expect(result).toBe('Sky Sports');
    });

    it('handles resolution tag: "Movie 1080p"', () => {
      expect(cleanTitle('Movie 1080p')).toBe('Movie');
    });

    it('handles resolution tag: "Show 720p"', () => {
      expect(cleanTitle('Show 720p')).toBe('Show');
    });
  });

  describe('extractYear — edge cases', () => {
    it('handles year at very end: "Interstellar 2014"', () => {
      expect(extractYear('Interstellar 2014')).toBe(2014);
    });

    it('handles year in brackets: "Inception (2010)"', () => {
      expect(extractYear('Inception (2010)')).toBe(2010);
    });

    it('returns null for future year too far ahead', () => {
      expect(extractYear('Movie (2099)')).toBeNull();
    });

    it('handles current year', () => {
      const thisYear = new Date().getFullYear();
      expect(extractYear(`Movie (${thisYear})`)).toBe(thisYear);
    });

    it('handles next year', () => {
      const nextYear = new Date().getFullYear() + 1;
      expect(extractYear(`Movie (${nextYear})`)).toBe(nextYear);
    });

    it('does not match 3-digit numbers', () => {
      expect(extractYear('Channel 123')).toBeNull();
    });

    it('does not match numbers in the middle of a title', () => {
      expect(extractYear('2001 A Space Odyssey')).toBeNull();
    });
  });

  describe('extractSeasonEpisode — edge cases', () => {
    it('handles S01 E02 with space', () => {
      expect(extractSeasonEpisode('Show S01 E02')).toEqual({ season: 1, episode: 2 });
    });

    it('handles single-digit season/episode', () => {
      expect(extractSeasonEpisode('Show S1E2')).toEqual({ season: 1, episode: 2 });
    });

    it('handles high episode numbers', () => {
      expect(extractSeasonEpisode('Show S01E120')).toEqual({ season: 1, episode: 120 });
    });

    it('handles "Episode" keyword', () => {
      expect(extractSeasonEpisode('Show Episode 5')).toEqual({ season: 1, episode: 5 });
    });

    it('does not match E in regular words', () => {
      // "THE" contains E but should not match
      expect(extractSeasonEpisode('THE MATRIX')).toBeNull();
    });
  });

  describe('extractShowName — edge cases', () => {
    it('handles show with S01E02 in the middle', () => {
      expect(extractShowName('Breaking Bad S01E02 Seven Thirty-Seven')).toBe('Breaking Bad');
    });

    it('handles show name with special characters', () => {
      expect(extractShowName("Grey's Anatomy S05E12")).toBe("Grey's Anatomy");
    });

    it('handles show with no season/episode', () => {
      expect(extractShowName('Random Movie Title')).toBe('Random Movie Title');
    });

    it('returns original if show name would be empty after cleaning', () => {
      expect(extractShowName('S01E01')).toBe('S01E01');
    });
  });
});
