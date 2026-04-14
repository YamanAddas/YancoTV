import { describe, it, expect } from 'vitest';
import {
  classifyEntry,
  normalizeCategory,
} from '../../src/main/services/content-classifier';
import type { M3uEntry } from '../../src/main/services/m3u-parser';

function makeEntry(overrides: Partial<M3uEntry> = {}): M3uEntry {
  return {
    duration: -1,
    title: 'Test Channel',
    groupTitle: '',
    tvgId: '',
    tvgName: '',
    tvgLogo: '',
    streamUrl: 'http://example.com/stream',
    rawAttributes: '',
    ...overrides,
  };
}

describe('Content Classifier — Extended Edge Cases', () => {
  describe('classifyEntry — priority and overlap', () => {
    it('series title pattern wins over movie group', () => {
      // Title says S01E02 but group says "Movies" — should be series
      expect(
        classifyEntry(
          makeEntry({
            title: 'Show S01E02',
            groupTitle: 'Movies',
          }),
        ),
      ).toBe('series');
    });

    it('series group wins over movie URL extension', () => {
      expect(
        classifyEntry(
          makeEntry({
            groupTitle: 'Series | Drama',
            streamUrl: 'http://example.com/video.mp4',
          }),
        ),
      ).toBe('series');
    });

    it('classifies .mov files as movie', () => {
      expect(
        classifyEntry(
          makeEntry({ streamUrl: 'http://example.com/video.mov' }),
        ),
      ).toBe('movie');
    });

    it('classifies "cinema" group as movie', () => {
      expect(classifyEntry(makeEntry({ groupTitle: 'Cinema HD' }))).toBe('movie');
    });

    it('classifies "tv show" group as series', () => {
      expect(classifyEntry(makeEntry({ groupTitle: 'TV Show | Drama' }))).toBe('series');
    });

    it('classifies based on /movie/ URL path', () => {
      expect(
        classifyEntry(
          makeEntry({ streamUrl: 'http://xtream.com/movie/user/pass/123.mp4' }),
        ),
      ).toBe('movie');
    });

    it('classifies based on /series/ URL path', () => {
      expect(
        classifyEntry(
          makeEntry({ streamUrl: 'http://xtream.com/series/user/pass/456.mp4' }),
        ),
      ).toBe('series');
    });

    it('zero duration defaults to live', () => {
      expect(classifyEntry(makeEntry({ duration: 0 }))).toBe('live');
    });

    it('negative duration defaults to live', () => {
      expect(classifyEntry(makeEntry({ duration: -1 }))).toBe('live');
    });

    it('positive duration with no other indicator = movie', () => {
      expect(classifyEntry(makeEntry({ duration: 5400 }))).toBe('movie');
    });
  });

  describe('normalizeCategory — additional edge cases', () => {
    it('normalizes "Documentaries" to "Documentary"', () => {
      expect(normalizeCategory('Documentaries')).toBe('Documentary');
    });

    it('normalizes "Children" to "Kids"', () => {
      expect(normalizeCategory('Children')).toBe('Kids');
    });

    it('normalizes "Animated" to "Animation"', () => {
      expect(normalizeCategory('Animated')).toBe('Animation');
    });

    it('normalizes mixed case input', () => {
      expect(normalizeCategory('SPORTS')).toBe('Sports');
      expect(normalizeCategory('sports')).toBe('Sports');
    });

    it('handles multiple spaces', () => {
      expect(normalizeCategory('US  |  News')).toBe('News');
    });

    it('preserves complex category names', () => {
      const result = normalizeCategory('News & Politics');
      expect(result).toBe('News & Politics');
    });

    it('handles category with only prefix', () => {
      const result = normalizeCategory('US:');
      // After stripping "US:", only whitespace remains — returns original trimmed
      expect(result).toBe('US:');
    });

    it('normalizes "Sci Fi" with space to "Sci-Fi"', () => {
      expect(normalizeCategory('Sci Fi')).toBe('Sci-Fi');
    });

    it('normalizes "Anime" consistently', () => {
      expect(normalizeCategory('Anime')).toBe('Anime');
    });

    it('normalizes "Education" and "Educational"', () => {
      expect(normalizeCategory('Education')).toBe('Education');
      expect(normalizeCategory('Educational')).toBe('Education');
    });
  });
});
