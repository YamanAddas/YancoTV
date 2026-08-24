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

describe('Content Classifier', () => {
  describe('classifyEntry', () => {
    it('classifies series by S01E02 pattern in title', () => {
      expect(classifyEntry(makeEntry({ title: 'Breaking Bad S01E02' }))).toBe('series');
      expect(classifyEntry(makeEntry({ title: 'Show s3e12 Episode' }))).toBe('series');
    });

    it('classifies series by Season+Episode / NxMM (MB-387)', () => {
      expect(classifyEntry(makeEntry({ title: 'The Office Season 2 Episode 5' }))).toBe('series');
      expect(classifyEntry(makeEntry({ title: 'Friends 3x10' }))).toBe('series');
    });

    it('does not force series from a bare "Season N" (MB-387)', () => {
      // Movies carry "Season" in their name; must not override a movie signal.
      expect(classifyEntry(makeEntry({ title: 'Open Season 2', groupTitle: 'Movies' }))).toBe('movie');
      expect(classifyEntry(makeEntry({ title: 'Making The Witcher: Season 3', streamUrl: 'http://x/movie/1.mp4' }))).toBe('movie');
    });

    it('classifies series by group name', () => {
      expect(classifyEntry(makeEntry({ groupTitle: 'US | Series' }))).toBe('series');
      expect(classifyEntry(makeEntry({ groupTitle: 'TV Show Drama' }))).toBe('series');
      expect(classifyEntry(makeEntry({ groupTitle: 'Episode List' }))).toBe('series');
    });

    it('classifies series by /series/ in URL', () => {
      expect(
        classifyEntry(
          makeEntry({ streamUrl: 'http://example.com/series/123/1/1.mp4' }),
        ),
      ).toBe('series');
    });

    it('classifies movies by group name', () => {
      expect(classifyEntry(makeEntry({ groupTitle: 'Movies | Action' }))).toBe('movie');
      expect(classifyEntry(makeEntry({ groupTitle: 'VOD' }))).toBe('movie');
      expect(classifyEntry(makeEntry({ groupTitle: 'Film Noir' }))).toBe('movie');
      expect(classifyEntry(makeEntry({ groupTitle: 'Cinema' }))).toBe('movie');
    });

    it('classifies movies by /movie/ in URL', () => {
      expect(
        classifyEntry(
          makeEntry({ streamUrl: 'http://example.com/movie/123.mp4' }),
        ),
      ).toBe('movie');
    });

    it('classifies movies by video file extension', () => {
      expect(
        classifyEntry(makeEntry({ streamUrl: 'http://example.com/video.mp4' })),
      ).toBe('movie');
      expect(
        classifyEntry(makeEntry({ streamUrl: 'http://example.com/video.mkv' })),
      ).toBe('movie');
    });

    it('classifies as series even with video extension if title has S01E02', () => {
      expect(
        classifyEntry(
          makeEntry({
            title: 'Show S01E02',
            streamUrl: 'http://example.com/video.mp4',
          }),
        ),
      ).toBe('series');
    });

    it('classifies movies by positive duration', () => {
      expect(classifyEntry(makeEntry({ duration: 3600 }))).toBe('movie');
    });

    it('defaults to live', () => {
      expect(classifyEntry(makeEntry())).toBe('live');
      expect(classifyEntry(makeEntry({ duration: -1 }))).toBe('live');
    });
  });

  describe('normalizeCategory', () => {
    it('strips country prefixes', () => {
      expect(normalizeCategory('US | News')).toBe('News');
      expect(normalizeCategory('UK: Sports')).toBe('Sports');
      expect(normalizeCategory('FR - Entertainment')).toBe('Entertainment');
    });

    it('strips trailing country suffix', () => {
      expect(normalizeCategory('Sports | US')).toBe('Sports');
    });

    it('normalizes common variations', () => {
      expect(normalizeCategory('Sport')).toBe('Sports');
      expect(normalizeCategory('Documentary')).toBe('Documentary');
      expect(normalizeCategory('Documentaries')).toBe('Documentary');
      expect(normalizeCategory("Children's")).toBe('Kids');
      expect(normalizeCategory('Kids')).toBe('Kids');
      expect(normalizeCategory('Sci Fi')).toBe('Sci-Fi');
      expect(normalizeCategory('Animation')).toBe('Animation');
      expect(normalizeCategory('Animated')).toBe('Animation');
    });

    it('title-cases the result', () => {
      expect(normalizeCategory('news & politics')).toBe('News & Politics');
    });

    it('returns empty string for empty input', () => {
      expect(normalizeCategory('')).toBe('');
    });

    it('preserves short acronyms', () => {
      expect(normalizeCategory('US Sports')).toBe('US Sports');
    });
  });
});
